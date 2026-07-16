package com.razorclient.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.razorclient.feature.module.Module;
import com.razorclient.feature.module.ModuleManager;
import com.razorclient.feature.module.impl.ClickPatternStore;
import com.razorclient.feature.setting.ActionSetting;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.DecimalSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.IntRangeSetting;
import com.razorclient.feature.setting.NumberSetting;
import com.razorclient.feature.setting.Setting;
import com.razorclient.inject.AgentLog;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long SAVE_DEBOUNCE_NANOS = 150_000_000L;
    private static final int MAX_CLICK_PATTERN_DELAYS = 4096;
    private static ConfigManager instance;
    private static boolean suppressSave;
    private static boolean pendingSave;
    private static long saveDeadlineNanos;

    private final ModuleManager moduleManager;
    private final File configDirectory;
    private final File currentConfigFile;
    private String currentConfigName;

    public ConfigManager(ModuleManager moduleManager) {
        this.moduleManager = moduleManager;
        File base = new File(Minecraft.getMinecraft().mcDataDir, "razorclient");
        this.configDirectory = new File(base, "configs");
        this.currentConfigFile = new File(base, "current-config.txt");
        instance = this;
    }

    public static synchronized void saveActiveConfig() {
        if (instance != null && !suppressSave) {
            pendingSave = true;
            saveDeadlineNanos = System.nanoTime() + SAVE_DEBOUNCE_NANOS;
        }
    }

    /** Called from the client tick to coalesce slider, drag, and keybind updates. */
    public static void flushPendingSave() {
        ConfigManager manager;
        synchronized (ConfigManager.class) {
            if (!pendingSave || suppressSave || System.nanoTime() < saveDeadlineNanos) return;
            pendingSave = false;
            manager = instance;
        }
        if (manager != null) {
            manager.saveCurrentInternal();
            manager.moduleManager.refreshConfigModule();
        }
    }

    public static void flushPendingSaveNow() {
        ConfigManager manager;
        synchronized (ConfigManager.class) {
            if (!pendingSave || suppressSave) return;
            pendingSave = false;
            manager = instance;
        }
        if (manager != null) {
            manager.saveCurrentInternal();
            manager.moduleManager.refreshConfigModule();
        }
    }

    public void initialize() {
        ensureDirectory();
        ensureBuiltInConfigs();
        currentConfigName = readCurrentConfigName();
        List<String> configs = listConfigs();
        if (currentConfigName == null || !getConfigFile(currentConfigName).isFile()) {
            currentConfigName = configs.isEmpty() ? "default" : configs.get(0);
        }
        if (!getConfigFile(currentConfigName).isFile()) {
            saveAs(currentConfigName);
        }
        applyConfig(currentConfigName);
    }

    public List<String> listConfigs() {
        ensureDirectory();
        File[] files = configDirectory.listFiles();
        List<String> names = new ArrayList<String>();
        if (files == null) {
            return names;
        }

        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".json")) {
                names.add(file.getName().substring(0, file.getName().length() - 5));
            }
        }

        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public String getCurrentConfigName() {
        return currentConfigName;
    }

    public void saveCurrent() {
        synchronized (ConfigManager.class) {
            pendingSave = false;
        }
        saveCurrentInternal();
    }

    private void saveCurrentInternal() {
        if (currentConfigName == null) {
            currentConfigName = "default";
        }
        saveAs(currentConfigName);
    }

    public void createNextConfig() {
        saveCurrent();
        String name = nextConfigName();
        saveAs(name);
        currentConfigName = name;
        persistCurrentConfigName();
    }

    public void deleteCurrentConfig() {
        List<String> configs = listConfigs();
        if (currentConfigName == null || configs.isEmpty()) {
            return;
        }

        File current = getConfigFile(currentConfigName);
        if (current.isFile() && !current.delete()) {
            return;
        }

        List<String> remaining = listConfigs();
        if (remaining.isEmpty()) {
            currentConfigName = "default";
            saveAs(currentConfigName);
            persistCurrentConfigName();
        } else {
            applyConfig(remaining.get(0));
        }
    }

    public void load(String name) {
        saveCurrent();
        applyConfig(name);
    }

    private synchronized boolean applyConfig(String name) {
        File file = getConfigFile(name);
        if (!file.isFile()) {
            return false;
        }

        ConfigPlan plan;
        try {
            String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("Config root must be an object");
            plan = stageConfig(parsed.getAsJsonObject());
        } catch (Exception failure) {
            AgentLog.error("Unable to validate config " + name, failure);
            return false;
        }

        RuntimeState previous = captureRuntimeState();
        String previousConfigName = currentConfigName;
        boolean previousSuppression;
        synchronized (ConfigManager.class) {
            previousSuppression = suppressSave;
            suppressSave = true;
        }
        try {
            applyRecordedPattern(plan.clickPattern);
            applyModuleValues(plan.modules);
            applyModuleEnabledStates(plan.modules);
            currentConfigName = name;
            moduleManager.refreshConfigModule();
            persistCurrentConfigNameOrThrow();
            return true;
        } catch (Exception failure) {
            currentConfigName = previousConfigName;
            try {
                restoreRuntimeState(previous);
                moduleManager.refreshConfigModule();
            } catch (Exception rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            AgentLog.error("Unable to load config " + name + "; previous state restored", failure);
            return false;
        } finally {
            synchronized (ConfigManager.class) {
                suppressSave = previousSuppression;
            }
        }
    }

    private ConfigPlan stageConfig(JsonObject root) {
        List<Integer> clickPattern = stageRecordedPattern(root);
        List<ModulePlan> modulePlans = new ArrayList<ModulePlan>();
        JsonObject modules = new JsonObject();
        if (root.has("modules")) {
            JsonElement modulesElement = root.get("modules");
            if (!modulesElement.isJsonObject()) {
                throw new IllegalArgumentException("modules must be an object");
            }
            modules = modulesElement.getAsJsonObject();
        }

        for (Module module : moduleManager.getModules()) {
            if (!modules.has(module.getName())) continue;
            JsonElement moduleElement = modules.get(module.getName());
            if (!moduleElement.isJsonObject()) {
                throw new IllegalArgumentException("Module " + module.getName() + " must be an object");
            }
            JsonObject moduleJson = moduleElement.getAsJsonObject();
            Boolean enabled = moduleJson.has("enabled")
                ? Boolean.valueOf(requireBoolean(moduleJson.get("enabled"), module.getName() + ".enabled")) : null;
            Integer keyCode = null;
            if (moduleJson.has("keyCode")) {
                int value = requireInt(moduleJson.get("keyCode"), module.getName() + ".keyCode");
                if (value == org.lwjgl.input.Keyboard.KEY_NONE && !module.canBeUnbound()) {
                    throw new IllegalArgumentException(module.getName() + " cannot be unbound");
                }
                keyCode = Integer.valueOf(value);
            }

            List<SettingValue> values = new ArrayList<SettingValue>();
            if (moduleJson.has("settings")) {
                JsonElement settingsElement = moduleJson.get("settings");
                if (!settingsElement.isJsonObject()) {
                    throw new IllegalArgumentException("Module " + module.getName() + " settings must be an object");
                }
                JsonObject settings = settingsElement.getAsJsonObject();
                for (Setting setting : module.getSettings()) {
                    if (setting instanceof ActionSetting || !settings.has(setting.getName())) continue;
                    String path = module.getName() + '.' + setting.getName();
                    values.add(new SettingValue(setting, SettingCodec.decode(setting, settings.get(setting.getName()), path)));
                }
            }
            modulePlans.add(new ModulePlan(module, enabled, keyCode, values));
        }
        return new ConfigPlan(clickPattern, modulePlans);
    }

    private List<Integer> stageRecordedPattern(JsonObject root) {
        List<Integer> delays = new ArrayList<Integer>();
        if (!root.has("clickPattern")) return delays;
        JsonElement patternElement = root.get("clickPattern");
        if (!patternElement.isJsonObject()) {
            throw new IllegalArgumentException("clickPattern must be an object");
        }
        JsonObject pattern = patternElement.getAsJsonObject();
        for (int index = 0; index < MAX_CLICK_PATTERN_DELAYS; index++) {
            String key = Integer.toString(index);
            if (!pattern.has(key)) return delays;
            delays.add(Integer.valueOf(Math.max(0, requireInt(pattern.get(key), "clickPattern." + key))));
        }
        if (pattern.has(Integer.toString(MAX_CLICK_PATTERN_DELAYS))) {
            throw new IllegalArgumentException("clickPattern exceeds " + MAX_CLICK_PATTERN_DELAYS + " entries");
        }
        return delays;
    }

    private RuntimeState captureRuntimeState() {
        List<ModuleState> modules = new ArrayList<ModuleState>(moduleManager.getModules().size());
        for (Module module : moduleManager.getModules()) {
            List<SettingValue> values = new ArrayList<SettingValue>(module.getSettings().size());
            for (Setting setting : module.getSettings()) {
                if (!(setting instanceof ActionSetting)) {
                    values.add(new SettingValue(setting, SettingCodec.capture(setting)));
                }
            }
            modules.add(new ModuleState(module, module.isEnabled(), module.getKeyCode(), values));
        }
        return new RuntimeState(new ArrayList<Integer>(ClickPatternStore.getDelays()), modules);
    }

    private void applyModuleValues(List<ModulePlan> plans) {
        for (ModulePlan plan : plans) {
            for (SettingValue value : plan.settings) applySettingValue(value);
            if (plan.keyCode != null) {
                plan.module.setKeyCode(plan.keyCode.intValue());
                if (plan.module.getKeyCode() != plan.keyCode.intValue()) {
                    throw new IllegalStateException("Unable to apply keybind for " + plan.module.getName());
                }
            }
        }
    }

    private void applyModuleEnabledStates(List<ModulePlan> plans) {
        for (ModulePlan plan : plans) {
            if (plan.enabled == null) continue;
            plan.module.setEnabled(plan.enabled.booleanValue());
            if (plan.module.isEnabled() != plan.enabled.booleanValue()) {
                throw new IllegalStateException("Unable to apply enabled state for " + plan.module.getName());
            }
        }
    }

    private void restoreRuntimeState(RuntimeState state) {
        RuntimeException failure = null;
        try {
            applyRecordedPattern(state.clickPattern);
        } catch (RuntimeException current) {
            failure = appendRollbackFailure(failure, current);
        }
        for (ModuleState module : state.modules) {
            for (SettingValue value : module.settings) {
                try {
                    applySettingValue(value);
                } catch (RuntimeException current) {
                    failure = appendRollbackFailure(failure, current);
                }
            }
            try {
                module.module.setKeyCode(module.keyCode);
                if (module.module.getKeyCode() != module.keyCode) {
                    throw new IllegalStateException("Unable to restore keybind for " + module.module.getName());
                }
            } catch (RuntimeException current) {
                failure = appendRollbackFailure(failure, current);
            }
        }
        for (ModuleState module : state.modules) {
            try {
                module.module.setEnabled(module.enabled);
                if (module.module.isEnabled() != module.enabled) {
                    throw new IllegalStateException("Unable to restore enabled state for " + module.module.getName());
                }
            } catch (RuntimeException current) {
                failure = appendRollbackFailure(failure, current);
            }
        }
        if (failure != null) throw failure;
    }

    private static RuntimeException appendRollbackFailure(RuntimeException aggregate, RuntimeException current) {
        if (aggregate == null) aggregate = new IllegalStateException("Config rollback was incomplete");
        aggregate.addSuppressed(current);
        return aggregate;
    }

    private void applySettingValue(SettingValue value) {
        SettingCodec.apply(value.setting, value.value);
    }

    private void applyRecordedPattern(List<Integer> delays) {
        ClickPatternStore.clear();
        for (Integer delay : delays) ClickPatternStore.addDelay(delay.intValue());
    }

    private static boolean requireBoolean(JsonElement element, String path) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(path + " must be a boolean");
        }
        return element.getAsBoolean();
    }

    private static int requireInt(JsonElement element, String path) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(path + " must be an integer");
        }
        try {
            return element.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException(path + " must be a 32-bit integer", failure);
        }
    }

    public void openFolder() {
        ensureDirectory();
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(configDirectory);
            }
        } catch (IOException failure) {
            AgentLog.error("Unable to open config folder " + configDirectory.getAbsolutePath(), failure);
        }
    }

    private synchronized void saveAs(String name) {
        ensureDirectory();
        File file = getConfigFile(name);
        JsonObject root = new JsonObject();
        JsonObject modulesJson = new JsonObject();
        root.add("clickPattern", serializeRecordedPattern());

        for (Module module : moduleManager.getModules()) {
            JsonObject moduleJson = new JsonObject();
            moduleJson.addProperty("enabled", module.isEnabled());
            moduleJson.addProperty("keyCode", module.getKeyCode());

            JsonObject settingsJson = new JsonObject();
            for (Setting setting : module.getSettings()) {
                JsonElement encoded = SettingCodec.encode(setting);
                if (encoded != null) settingsJson.add(setting.getName(), encoded);
            }

            moduleJson.add("settings", settingsJson);
            modulesJson.add(module.getName(), moduleJson);
        }

        root.add("modules", modulesJson);
        try {
            writeJsonAtomically(file, root);
        } catch (IOException failure) {
            AgentLog.error("Unable to save config " + file.getAbsolutePath(), failure);
        }
    }

    private void ensureBuiltInConfigs() {
        File survival = getConfigFile("survival");
        File bedwarsSurvival = getConfigFile("bedwars-survival");
        File bedwarsLegit = getConfigFile("bedwars-legit");
        File bedwarsAggressive = getConfigFile("bedwars-aggressive");
        File bedwarsAggressiveV2 = getConfigFile("bedwars-aggressive-v2");
        boolean createSurvival = !survival.isFile();
        boolean createBedwarsSurvival = !bedwarsSurvival.isFile();
        boolean createBedwarsLegit = !bedwarsLegit.isFile();
        boolean createBedwarsAggressive = !bedwarsAggressive.isFile();
        boolean createBedwarsAggressiveV2 = !bedwarsAggressiveV2.isFile();
        if (!createSurvival && !createBedwarsSurvival && !createBedwarsLegit
                && !createBedwarsAggressive && !createBedwarsAggressiveV2) {
            ensureUtilityProfileEntries(survival);
            ensureUtilityProfileEntries(bedwarsSurvival);
            ensureUtilityProfileEntries(bedwarsLegit);
            ensureUtilityProfileEntries(bedwarsAggressive);
            ensureUtilityProfileEntries(bedwarsAggressiveV2);
            return;
        }

        JsonObject root = new JsonObject();
        root.add("clickPattern", new JsonObject());
        JsonObject modules = new JsonObject();

        addModule(modules, "Sprint", true, org.lwjgl.input.Keyboard.KEY_NONE);
        addModule(modules, "HUD", true, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Mode", "CLASSIC"),
            setting("Red", 45),
            setting("Green", 210),
            setting("Blue", 110),
            setting("Text GUI", true),
            setting("Text GUI Pinned", true),
            setting("Text GUI Scale", 85),
            setting("Text GUI Sort", "LENGTH"),
            setting("Suffix Mode", "BASIC"),
            setting("Text GUI Color", "GUI"),
            setting("Text GUI Shadow", true),
            setting("Text GUI Background", false),
            setting("Watermark", true),
            setting("Click Disable", false),
            setting("Target Info", true),
            setting("Target Info Pinned", false),
            setting("Target Info Scale", 90),
            setting("Target Show Hovered", true),
            setting("Target Background", true),
            setting("Radar", true),
            setting("Radar Pinned", true),
            setting("Radar Scale", 85),
            setting("Radar Size", 92),
            setting("Radar Range", 80),
            setting("Radar Background", true),
            setting("Radar Cross", true),
            setting("Radar Clamp", true),
            setting("Radar Color", "GUI")
        );
        addModule(modules, "ClickGUI", false, org.lwjgl.input.Keyboard.KEY_RSHIFT,
            setting("Color Preset", "RAZOR_GREEN"),
            setting("GUI Effects", true),
            setting("Glow Intensity", 70),
            setting("Sweep Animation", true),
            setting("Custom Cursor", true),
            setting("Cursor Scale", 85)
        );
        addModule(modules, "Config", false, org.lwjgl.input.Keyboard.KEY_NONE);
        addModule(modules, "Self Destruct", false, org.lwjgl.input.Keyboard.KEY_NONE);
        addModule(modules, "Fullbright", true, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Brightness", 10)
        );
        addModule(modules, "Auto Tool", true, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Return To Slot", true)
        );
        addModule(modules, "Fast Place", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Delay", 0),
            setting("Blocks Only", true)
        );
        addModule(modules, "Item Physics", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("No Bob", true),
            setting("No Spin", true)
        );

        addModule(modules, "BedPlates", true, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Range", 96),
            setting("Layers", 2),
            setting("Show Distance", true)
        );
        addModule(modules, "PlayerESP", true, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Mode", "CLASSIC"),
            setting("Render Mode", "BOTH"),
            setting("Projection Mode", "BOTH"),
            setting("Target Type", "BOTH"),
            setting("Red", 170),
            setting("Green", 95),
            setting("Blue", 255),
            setting("Hidden Red", 235),
            setting("Hidden Green", 70),
            setting("Hidden Blue", 70),
            setting("Target Red", 255),
            setting("Target Green", 190),
            setting("Target Blue", 70),
            setting("See Invis", false),
            setting("Through Walls", true),
            setting("Show Names", true),
            setting("Show Health", true),
            setting("Health Bar", true),
            setting("Health Value", true),
            setting("Show Distance", true),
            setting("Armor", true),
            setting("Held Item", true),
            setting("Tracers", false),
            setting("Target Highlight", true),
            setting("Max Distance", 96),
            setting("Line Width", 2),
            setting("Fill Alpha", 12),
            setting("Use Team Colors", false)
        );
        addModule(modules, "Trajectories", true, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Aiming Red", 120),
            setting("Aiming Green", 255),
            setting("Aiming Blue", 120),
            setting("Trajectory Red", 255),
            setting("Trajectory Green", 255),
            setting("Trajectory Blue", 255),
            setting("Target Red", 255),
            setting("Target Green", 90),
            setting("Target Blue", 90),
            setting("Thickness", 2)
        );

        addModule(modules, "LegitScaffold", true, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Pitch Check", false),
            setting("Sneak Delay", 85)
        );
        addModule(modules, "Clutch", true, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Trigger", "PREDICTED_DANGER"),
            setting("Blocks", 6.0D),
            setting("Silent Aim", false),
            setting("Rotate Back", true),
            setting("Return To Slot", true),
            setting("Clutch Move Delay", 0),
            setting("Max Blocks", 16),
            setting("Rotation Speed", 72.0D),
            setting("Filter Mode", "NONE"),
            setting("Range", 4),
            setting("FOV", 160),
            setting("Minimum Height", 2),
            setting("Click Speed", 14),
            setting("Randomization", 8),
            setting("Select Blocks", "ALWAYS"),
            setting("Only Place Sideways", false),
            setting("Aim Acceleration", 35),
            setting("Acceleration Strength", 65),
            setting("Multipoint", true),
            setting("Snap Back Delay", 2),
            setting("Snap Back Duration", 4),
            setting("Keep Jump Direction", true),
            setting("Disable Afterwards", false),
            setting("Only Mid-Air", true),
            setting("Recently Damaged", false),
            setting("Moving Backwards", false),
            setting("Recovery Mode", "EMERGENCY_BRIDGE"),
            setting("Prediction Ticks", 4),
            setting("Confirmation Ticks", 2)
        );
        addModule(modules, "AntiFireball", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("FOV", 180),
            setting("Range", 8.0D),
            setting("Target CPS", 10.0D),
            setting("Rotation Speed", 12),
            setting("On Ground", false),
            setting("Sneak While Active", false)
        );

        addModule(modules, "AimAssist", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Rotation Speed", 3),
            setting("Randomization", 4),
            setting("FOV", 80),
            setting("Distance", 4.0D),
            setting("Target Type", "MOBS"),
            setting("Click Aim", true),
            setting("Weapon Only", false),
            setting("Target Invis", false),
            setting("Break Blocks", true)
        );
        addModule(modules, "KillAura", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Target CPS", 8.0D),
            setting("Range (Attack)", 3.0D),
            setting("Range (Swing)", 4.0D),
            setting("Range (Aim)", 4.0D),
            setting("Switch Delay", 250),
            setting("Targets", 1),
            setting("Target Type", "MOBS"),
            setting("Target Invis", false),
            setting("Hit Through Entities", false),
            setting("Disable In Inventory", true),
            setting("Disable While Mining", true),
            setting("Not Using Item", false),
            setting("Weapon Only", false)
        );
        addModule(modules, "LeftClicker", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Mode", "NORMAL"),
            setting("Break Blocks", true),
            setting("Weapon Only", false),
            setting("Inventory Fill", false),
            setting("Min CPS", 8),
            setting("Max CPS", 12),
            setting("Jitter", 0)
        );
        addModule(modules, "RightClicker", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Mode", "NORMAL"),
            setting("Only Blocks", true),
            setting("Min CPS", 8),
            setting("Max CPS", 12),
            setting("Jitter", 0)
        );
        addModule(modules, "Reach", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Reach", 3.0D),
            setting("Chance", 100)
        );
        addModule(modules, "AntiBot", true, org.lwjgl.input.Keyboard.KEY_NONE);
        addModule(modules, "Knockback Delay", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("delay (ms)", range(200, 300)),
            setting("chance %", 100)
        );

        addModule(modules, "Velocity", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Mode", "REGULAR"),
            setting("Horizontal", 90),
            setting("Vertical", 100),
            setting("Chance", 100),
            setting("Explosions", true),
            setting("FOV", 360),
            setting("Reduce Delay", 0),
            setting("Only When Targeting", false),
            setting("Mouse Pressed", false),
            setting("Only Moving", true),
            setting("Only On Ground", false),
            setting("Randomize", true),
            setting("Moving Forward", false),
            setting("Holding Weapon", false),
            setting("Water Check", true),
            setting("Vertical Mode", "NEVER"),
            setting("Require Sprinting", false),
            setting("Allow Double Clicks", false)
        );
        addModule(modules, "Fake Lag", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Mode", "STATIC"),
            setting("Inbound Delay", 100),
            setting("Outbound Delay", 100),
            setting("Pulse Hold", 350),
            setting("Pulse Flush", 150),
            setting("Realtime Damage", true),
            setting("Holding Weapon", false),
            setting("Require Attack", false),
            setting("In Game Only", true)
        );
        addModule(modules, "Blink", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Direction", "OUTBOUND"),
            setting("Maximum Duration", 2500),
            setting("Allow Keep Alives", true),
            setting("Disable On Attack", true),
            setting("Disable On Block Interact", false),
            setting("Disable On Block Dig", false)
        );
        addModule(modules, "Backtrack", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Target Distance", 4.0D),
            setting("Maximum Delay", 200),
            setting("Maximum Hurt Time", 500),
            setting("Cooldown", 750),
            setting("Real Position Indicator", true),
            setting("Disable On Hit", true),
            setting("Holding Weapon", false)
        );
        addModule(modules, "Lag Range", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Maximum Delay", 180),
            setting("Activation Range", 4.0D),
            setting("Flush On Sprint Reset", true),
            setting("Flush On Splash Potion", true),
            setting("Real Position Indicator", true),
            setting("Holding Weapon", false)
        );
        addModule(modules, "Ping Fix", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Display Ping Offset", 0)
        );

        addModule(modules, "Hit Select", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Pause Duration", 500),
            setting("Mode", "BURST"),
            setting("Fake Swing", true),
            setting("In Combat Cancel Rate", 100),
            setting("Missed Swings Cancel Rate", 40),
            setting("Disable During Knockback", false),
            setting("Only While Damaged", false)
        );
        addModule(modules, "Sprint Reset", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Mode", "W_TAP"),
            setting("Delay After Attack", 225),
            setting("Stop Duration", 50),
            setting("Randomize", true),
            setting("Wait For Damage", false),
            setting("Holding Weapon", true)
        );
        addModule(modules, "Auto Weapon", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Activation Time", 0),
            setting("Require Left Click", true),
            setting("Return To Slot", true)
        );
        addModule(modules, "Teams", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Scoreboard Team", true),
            setting("Match Own Nametag", true)
        );
        addModule(modules, "No Hit Delay", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Mode", "LEGIT")
        );
        addModule(modules, "Criticals", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Mode", "PACKET"),
            setting("Maximum Delay", 150),
            setting("Timer Speed", 50),
            setting("Chance", 100),
            setting("Holding Weapon", true),
            setting("Mouse Pressed", true)
        );

        addModule(modules, "ClickRecorder", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Show Message", false)
        );

        root.add("modules", modules);
        JsonObject legitRoot = createBedwarsProfile(root, false);
        JsonObject aggressiveRoot = createBedwarsProfile(root, true);
        JsonObject aggressiveV2Root = createBedwarsAggressiveV2(aggressiveRoot);
        if (createSurvival) {
            writeConfigJson(survival, root);
        } else {
            ensureUtilityProfileEntries(survival);
        }
        if (createBedwarsSurvival) {
            writeConfigJson(bedwarsSurvival, legitRoot);
        } else {
            ensureUtilityProfileEntries(bedwarsSurvival);
        }
        if (createBedwarsLegit) {
            writeConfigJson(bedwarsLegit, legitRoot);
        } else {
            ensureUtilityProfileEntries(bedwarsLegit);
        }
        if (createBedwarsAggressive) {
            writeConfigJson(bedwarsAggressive, aggressiveRoot);
        } else {
            ensureUtilityProfileEntries(bedwarsAggressive);
        }
        if (createBedwarsAggressiveV2) {
            writeConfigJson(bedwarsAggressiveV2, aggressiveV2Root);
        } else {
            ensureUtilityProfileEntries(bedwarsAggressiveV2);
        }
    }

    private JsonObject createBedwarsAggressiveV2(JsonObject base) {
        JsonObject profile = base.deepCopy();
        setModuleEnabled(profile, "LeftClicker", false);
        setModuleEnabled(profile, "KillAura", true);
        setModuleSetting(profile, "KillAura", "Target CPS", 17.0D);
        setModuleSetting(profile, "KillAura", "Range (Attack)", 3.2D);
        setModuleSetting(profile, "KillAura", "Range (Swing)", 4.5D);
        setModuleSetting(profile, "KillAura", "Range (Aim)", 4.5D);

        setModuleEnabled(profile, "AimAssist", true);
        setModuleSetting(profile, "AimAssist", "Aim Mode", "SILENT");
        setModuleSetting(profile, "AimAssist", "Distance", 4.5D);
        setModuleSetting(profile, "AimAssist", "FOV", 120);
        setModuleSetting(profile, "AimAssist", "Horizontal Speed", 180);
        setModuleSetting(profile, "AimAssist", "Vertical Speed", 120);

        setModuleEnabled(profile, "Reach", true);
        setModuleSetting(profile, "Reach", "Reach", 3.4D);
        setModuleSetting(profile, "Reach", "Chance", 100);
        setModuleEnabled(profile, "Velocity", true);
        setModuleSetting(profile, "Velocity", "Horizontal", 80);
        setModuleSetting(profile, "Velocity", "Vertical", 100);

        setModuleEnabled(profile, "Hit Select", true);
        setModuleSetting(profile, "Hit Select", "Mode", "BURST");
        setModuleSetting(profile, "Hit Select", "Pause Duration", 300);
        setModuleEnabled(profile, "Sprint Reset", true);
        setModuleSetting(profile, "Sprint Reset", "Mode", "NO_STOP");
        setModuleSetting(profile, "Sprint Reset", "Delay After Attack", 200);
        setModuleSetting(profile, "Sprint Reset", "Stop Duration", 40);
        setModuleEnabled(profile, "Auto Weapon", true);
        setModuleEnabled(profile, "Teams", true);
        setModuleEnabled(profile, "No Hit Delay", true);
        setModuleSetting(profile, "No Hit Delay", "Mode", "REGULAR");
        setModuleEnabled(profile, "Criticals", true);
        setModuleSetting(profile, "Criticals", "Mode", "PACKET");
        setModuleSetting(profile, "Criticals", "Maximum Delay", 40);

        setModuleEnabled(profile, "Backtrack", true);
        setModuleSetting(profile, "Backtrack", "Maximum Delay", 180);
        setModuleSetting(profile, "Backtrack", "Cooldown", 250);
        setModuleEnabled(profile, "Lag Range", true);
        setModuleSetting(profile, "Lag Range", "Maximum Delay", 140);

        setModuleSetting(profile, "Clutch", "Silent Aim", true);
        setModuleSetting(profile, "Clutch", "Click Speed", 16);
        setModuleSetting(profile, "Clutch", "Range", 4);
        setModuleSetting(profile, "Clutch", "Minimum Height", 1);
        setModuleSetting(profile, "Clutch", "Max Blocks", 16);
        setModuleSetting(profile, "LegitScaffold", "Diagonal Release Delay", 20);
        setModuleSetting(profile, "LegitScaffold", "Placement CPS", 15);

        setModuleEnabled(profile, "Blink", false);
        setModuleEnabled(profile, "Fake Lag", false);
        setModuleEnabled(profile, "Knockback Delay", false);
        return profile;
    }

    private JsonObject createBedwarsProfile(JsonObject base, boolean aggressive) {
        JsonObject profile = base.deepCopy();
        setModuleEnabled(profile, "AntiBot", true);
        setModuleEnabled(profile, "BedPlates", true);
        setModuleEnabled(profile, "PlayerESP", true);
        setModuleEnabled(profile, "LegitScaffold", true);
        setModuleEnabled(profile, "Clutch", true);
        setModuleEnabled(profile, "Fullbright", true);
        setModuleEnabled(profile, "Auto Tool", true);
        setModuleEnabled(profile, "AimAssist", true);
        setModuleSetting(profile, "AimAssist", "Target Type", "PLAYERS");
        setModuleSetting(profile, "AimAssist", "Ignore Teammates", true);
        setModuleSetting(profile, "AimAssist", "Require Visibility", true);
        setModuleSetting(profile, "AimAssist", "Click Aim", true);
        setModuleSetting(profile, "AimAssist", "Weapon Only", true);
        setModuleSetting(profile, "AimAssist", "Distance", aggressive ? 4.5D : 4.0D);
        setModuleSetting(profile, "AimAssist", "FOV", aggressive ? 120 : 80);
        setModuleSetting(profile, "AimAssist", "Aim Mode", aggressive ? "SILENT" : "REGULAR");
        setModuleSetting(profile, "AimAssist", "Horizontal Speed", aggressive ? 8 : 4);
        setModuleSetting(profile, "AimAssist", "Vertical Speed", aggressive ? 6 : 3);

        setModuleEnabled(profile, "LeftClicker", true);
        setModuleSetting(profile, "LeftClicker", "Mode", "NORMAL");
        setModuleSetting(profile, "LeftClicker", "Click Pattern", aggressive ? "JITTER" : "NORMAL");
        setModuleSetting(profile, "LeftClicker", "Min CPS", aggressive ? 12 : 8);
        setModuleSetting(profile, "LeftClicker", "Max CPS", aggressive ? 16 : 12);
        setModuleSetting(profile, "LeftClicker", "Weapon Only", true);
        setModuleSetting(profile, "LeftClicker", "Not Using Item", true);

        setModuleEnabled(profile, "KillAura", aggressive);
        setModuleSetting(profile, "KillAura", "Target Type", "PLAYERS");
        setModuleSetting(profile, "KillAura", "Target CPS", aggressive ? 10.0D : 8.0D);
        setModuleSetting(profile, "KillAura", "Range (Attack)", aggressive ? 3.2D : 3.0D);
        setModuleSetting(profile, "KillAura", "Range (Swing)", aggressive ? 4.2D : 4.0D);
        setModuleSetting(profile, "KillAura", "Range (Aim)", aggressive ? 4.5D : 4.0D);
        setModuleSetting(profile, "KillAura", "Weapon Only", true);

        setModuleEnabled(profile, "Reach", aggressive);
        setModuleSetting(profile, "Reach", "Reach", aggressive ? 3.4D : 3.0D);
        setModuleSetting(profile, "Reach", "Chance", aggressive ? 90 : 100);
        setModuleEnabled(profile, "Velocity", aggressive);
        setModuleSetting(profile, "Velocity", "Mode", "REGULAR");
        setModuleSetting(profile, "Velocity", "Horizontal", aggressive ? 85 : 90);
        setModuleSetting(profile, "Velocity", "Vertical", 100);
        setModuleSetting(profile, "Velocity", "Only Moving", true);
        setModuleSetting(profile, "Velocity", "Randomize", true);
        setModuleEnabled(profile, "Fast Place", aggressive);
        setModuleEnabled(profile, "No Jump Delay", aggressive);

        for (String lagModule : new String[] {"Fake Lag", "Blink", "Backtrack", "Lag Range", "Knockback Delay"}) {
            setModuleEnabled(profile, lagModule, false);
        }
        return profile;
    }

    private void setModuleEnabled(JsonObject root, String moduleName, boolean enabled) {
        JsonObject module = getProfileModule(root, moduleName);
        module.addProperty("enabled", enabled);
    }

    private void setModuleSetting(JsonObject root, String moduleName, String settingName, boolean value) {
        getProfileSettings(root, moduleName).addProperty(settingName, value);
    }

    private void setModuleSetting(JsonObject root, String moduleName, String settingName, int value) {
        getProfileSettings(root, moduleName).addProperty(settingName, value);
    }

    private void setModuleSetting(JsonObject root, String moduleName, String settingName, double value) {
        getProfileSettings(root, moduleName).addProperty(settingName, value);
    }

    private void setModuleSetting(JsonObject root, String moduleName, String settingName, String value) {
        getProfileSettings(root, moduleName).addProperty(settingName, value);
    }

    private JsonObject getProfileModule(JsonObject root, String moduleName) {
        JsonObject modules = root.getAsJsonObject("modules");
        if (!modules.has(moduleName)) addModule(modules, moduleName, false, org.lwjgl.input.Keyboard.KEY_NONE);
        return modules.getAsJsonObject(moduleName);
    }

    private JsonObject getProfileSettings(JsonObject root, String moduleName) {
        JsonObject module = getProfileModule(root, moduleName);
        if (!module.has("settings")) module.add("settings", new JsonObject());
        return module.getAsJsonObject("settings");
    }

    /** Adds new managed defaults without changing existing built-in profile values. */
    private void ensureUtilityProfileEntries(File file) {
        try {
            JsonObject root = JsonParser.parseString(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject modules = root.has("modules") ? root.getAsJsonObject("modules") : new JsonObject();
            boolean changed = false;
            if (!modules.has("Fullbright")) {
                addModule(modules, "Fullbright", true, org.lwjgl.input.Keyboard.KEY_NONE, setting("Brightness", 10));
                changed = true;
            }
            if (!modules.has("Auto Tool")) {
                addModule(modules, "Auto Tool", true, org.lwjgl.input.Keyboard.KEY_NONE, setting("Return To Slot", true));
                changed = true;
            }
            if (!modules.has("Fast Place")) {
                addModule(modules, "Fast Place", false, org.lwjgl.input.Keyboard.KEY_NONE,
                    setting("Delay", 0), setting("Blocks Only", true));
                changed = true;
            }
            if (!modules.has("Item Physics")) {
                addModule(modules, "Item Physics", false, org.lwjgl.input.Keyboard.KEY_NONE,
                    setting("No Bob", true), setting("No Spin", true));
                changed = true;
            }
            if (!modules.has("Hit Select")) {
                addModule(modules, "Hit Select", false, org.lwjgl.input.Keyboard.KEY_NONE,
                    setting("Pause Duration", 500), setting("Mode", "BURST"), setting("Fake Swing", true),
                    setting("In Combat Cancel Rate", 100), setting("Missed Swings Cancel Rate", 40),
                    setting("Disable During Knockback", false), setting("Only While Damaged", false));
                changed = true;
            }
            if (!modules.has("Sprint Reset")) {
                addModule(modules, "Sprint Reset", false, org.lwjgl.input.Keyboard.KEY_NONE,
                    setting("Mode", "W_TAP"), setting("Delay After Attack", 225), setting("Stop Duration", 50),
                    setting("Randomize", true), setting("Wait For Damage", false), setting("Holding Weapon", true));
                changed = true;
            }
            if (!modules.has("Auto Weapon")) {
                addModule(modules, "Auto Weapon", false, org.lwjgl.input.Keyboard.KEY_NONE,
                    setting("Activation Time", 0), setting("Require Left Click", true), setting("Return To Slot", true));
                changed = true;
            }
            if (!modules.has("Teams")) {
                addModule(modules, "Teams", false, org.lwjgl.input.Keyboard.KEY_NONE,
                    setting("Scoreboard Team", true), setting("Match Own Nametag", true));
                changed = true;
            }
            if (!modules.has("No Hit Delay")) {
                addModule(modules, "No Hit Delay", false, org.lwjgl.input.Keyboard.KEY_NONE, setting("Mode", "LEGIT"));
                changed = true;
            }
            if (!modules.has("Criticals")) {
                addModule(modules, "Criticals", false, org.lwjgl.input.Keyboard.KEY_NONE,
                    setting("Mode", "PACKET"), setting("Maximum Delay", 150), setting("Timer Speed", 50),
                    setting("Chance", 100), setting("Holding Weapon", true), setting("Mouse Pressed", true));
                changed = true;
            }
            if (changed) {
                root.add("modules", modules);
                writeConfigJson(file, root);
            }
        } catch (Exception failure) {
            // A malformed user-edited built-in profile is left untouched.
            AgentLog.error("Unable to update built-in profile " + file.getAbsolutePath(), failure);
        }
    }

    private void writeConfigJson(File file, JsonObject root) {
        try {
            writeJsonAtomically(file, root);
        } catch (IOException failure) {
            AgentLog.error("Unable to write config " + file.getAbsolutePath(), failure);
        }
    }

    private void addModule(JsonObject modules, String name, boolean enabled, int keyCode, JsonObject... settings) {
        JsonObject module = new JsonObject();
        module.addProperty("enabled", enabled);
        module.addProperty("keyCode", keyCode);
        JsonObject values = new JsonObject();
        for (JsonObject setting : settings) {
            for (java.util.Map.Entry<String, JsonElement> entry : setting.entrySet()) {
                values.add(entry.getKey(), entry.getValue());
            }
        }
        module.add("settings", values);
        modules.add(name, module);
    }

    private JsonObject setting(String name, boolean value) {
        JsonObject setting = new JsonObject();
        setting.addProperty(name, value);
        return setting;
    }

    private JsonObject setting(String name, int value) {
        JsonObject setting = new JsonObject();
        setting.addProperty(name, value);
        return setting;
    }

    private JsonObject setting(String name, double value) {
        JsonObject setting = new JsonObject();
        setting.addProperty(name, value);
        return setting;
    }

    private JsonObject setting(String name, String value) {
        JsonObject setting = new JsonObject();
        setting.addProperty(name, value);
        return setting;
    }

    private JsonArray range(int low, int high) {
        JsonArray array = new JsonArray();
        array.add(new JsonPrimitive(low));
        array.add(new JsonPrimitive(high));
        return array;
    }

    private JsonObject setting(String name, JsonArray value) {
        JsonObject setting = new JsonObject();
        setting.add(name, value);
        return setting;
    }

    private JsonObject serializeRecordedPattern() {
        JsonObject patternJson = new JsonObject();
        List<Integer> delays = ClickPatternStore.getDelays();
        for (int i = 0; i < delays.size(); i++) {
            patternJson.addProperty(Integer.toString(i), delays.get(i).intValue());
        }
        return patternJson;
    }

    private File getConfigFile(String name) {
        return new File(configDirectory, sanitize(name) + ".json");
    }

    private void ensureDirectory() {
        if (!configDirectory.isDirectory()) {
            configDirectory.mkdirs();
        }
        File parent = currentConfigFile.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
    }

    private String readCurrentConfigName() {
        if (!currentConfigFile.isFile()) {
            return null;
        }

        try {
            return sanitize(new String(Files.readAllBytes(currentConfigFile.toPath()), StandardCharsets.UTF_8).trim());
        } catch (IOException failure) {
            AgentLog.error("Unable to read active config marker", failure);
            return null;
        }
    }

    private void persistCurrentConfigName() {
        try {
            persistCurrentConfigNameOrThrow();
        } catch (IOException failure) {
            AgentLog.error("Unable to persist active config marker", failure);
        }
    }

    private void persistCurrentConfigNameOrThrow() throws IOException {
        AtomicFileStore.write(currentConfigFile, currentConfigName.getBytes(StandardCharsets.UTF_8));
    }

    private void writeJsonAtomically(File file, JsonObject root) throws IOException {
        AtomicFileStore.write(file, GSON.toJson(root).getBytes(StandardCharsets.UTF_8));
    }

    private static final class ConfigPlan {
        private final List<Integer> clickPattern;
        private final List<ModulePlan> modules;

        private ConfigPlan(List<Integer> clickPattern, List<ModulePlan> modules) {
            this.clickPattern = clickPattern;
            this.modules = modules;
        }
    }

    private static final class ModulePlan {
        private final Module module;
        private final Boolean enabled;
        private final Integer keyCode;
        private final List<SettingValue> settings;

        private ModulePlan(Module module, Boolean enabled, Integer keyCode, List<SettingValue> settings) {
            this.module = module;
            this.enabled = enabled;
            this.keyCode = keyCode;
            this.settings = settings;
        }
    }

    private static final class RuntimeState {
        private final List<Integer> clickPattern;
        private final List<ModuleState> modules;

        private RuntimeState(List<Integer> clickPattern, List<ModuleState> modules) {
            this.clickPattern = clickPattern;
            this.modules = modules;
        }
    }

    private static final class ModuleState {
        private final Module module;
        private final boolean enabled;
        private final int keyCode;
        private final List<SettingValue> settings;

        private ModuleState(Module module, boolean enabled, int keyCode, List<SettingValue> settings) {
            this.module = module;
            this.enabled = enabled;
            this.keyCode = keyCode;
            this.settings = settings;
        }
    }

    private static final class SettingValue {
        private final Setting setting;
        private final Object value;

        private SettingValue(Setting setting, Object value) {
            this.setting = setting;
            this.value = value;
        }
    }

    private String nextConfigName() {
        List<String> configs = listConfigs();
        int index = 1;
        while (configs.contains("config-" + index)) {
            index++;
        }
        return "config-" + index;
    }

    private String sanitize(String input) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                builder.append(c);
            }
        }
        return builder.length() == 0 ? "default" : builder.toString();
    }
}
