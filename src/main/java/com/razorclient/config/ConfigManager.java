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
import java.awt.Desktop;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ConfigManager instance;
    private static boolean suppressSave;

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

    public static void saveActiveConfig() {
        if (instance != null && !suppressSave) {
            instance.saveCurrent();
            instance.moduleManager.refreshConfigModule();
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
        persistCurrentConfigName();
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
        } else {
            currentConfigName = remaining.get(0);
            applyConfig(currentConfigName);
        }
        persistCurrentConfigName();
    }

    public void load(String name) {
        saveCurrent();
        applyConfig(name);
    }

    private void applyConfig(String name) {
        File file = getConfigFile(name);
        if (!file.isFile()) {
            return;
        }

        try {
            suppressSave = true;
            String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonObject modules = root.has("modules") ? root.getAsJsonObject("modules") : new JsonObject();
            applyRecordedPattern(root);

            for (Module module : moduleManager.getModules()) {
                JsonObject moduleJson = modules.has(module.getName()) ? modules.getAsJsonObject(module.getName()) : null;
                if (moduleJson == null) {
                    continue;
                }

                if (moduleJson.has("enabled")) {
                    module.setEnabled(moduleJson.get("enabled").getAsBoolean());
                }

                if (moduleJson.has("keyCode")) {
                    module.setKeyCode(moduleJson.get("keyCode").getAsInt());
                }

                JsonObject settingsJson = moduleJson.has("settings") ? moduleJson.getAsJsonObject("settings") : null;
                if (settingsJson == null) {
                    continue;
                }

                for (Setting setting : module.getSettings()) {
                    if (!settingsJson.has(setting.getName())) {
                        continue;
                    }

                    JsonElement value = settingsJson.get(setting.getName());
                    if (setting instanceof BooleanSetting) {
                        ((BooleanSetting) setting).setEnabled(value.getAsBoolean());
                    } else if (setting instanceof DecimalSetting) {
                        ((DecimalSetting) setting).setManualValue(value.getAsDouble());
                    } else if (setting instanceof IntRangeSetting && value.isJsonArray()) {
                        JsonArray array = value.getAsJsonArray();
                        if (array.size() >= 2) {
                            ((IntRangeSetting) setting).setRange(array.get(0).getAsInt(), array.get(1).getAsInt(), false);
                        }
                    } else if (setting instanceof NumberSetting) {
                        ((NumberSetting) setting).setManualValue(value.getAsInt());
                    } else if (setting instanceof EnumSetting) {
                        ((EnumSetting<?>) setting).setValueByName(value.getAsString());
                    }
                }
            }

            currentConfigName = name;
            persistCurrentConfigName();
            moduleManager.refreshConfigModule();
        } catch (Exception ignored) {
        } finally {
            suppressSave = false;
        }
    }

    public void openFolder() {
        ensureDirectory();
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(configDirectory);
            }
        } catch (IOException ignored) {
        }
    }

    private void saveAs(String name) {
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
                if (setting instanceof ActionSetting) {
                    continue;
                }
                if (setting instanceof BooleanSetting) {
                    settingsJson.addProperty(setting.getName(), ((BooleanSetting) setting).isEnabled());
                } else if (setting instanceof DecimalSetting) {
                    settingsJson.addProperty(setting.getName(), ((DecimalSetting) setting).getValue());
                } else if (setting instanceof IntRangeSetting) {
                    IntRangeSetting range = (IntRangeSetting) setting;
                    JsonArray array = new JsonArray();
                    array.add(new JsonPrimitive(range.getLow()));
                    array.add(new JsonPrimitive(range.getHigh()));
                    settingsJson.add(setting.getName(), array);
                } else if (setting instanceof NumberSetting) {
                    settingsJson.addProperty(setting.getName(), ((NumberSetting) setting).getValue());
                } else if (setting instanceof EnumSetting) {
                    settingsJson.addProperty(setting.getName(), ((EnumSetting<?>) setting).getValue().name());
                }
            }

            moduleJson.add("settings", settingsJson);
            modulesJson.add(module.getName(), moduleJson);
        }

        root.add("modules", modulesJson);
        try {
            Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
            try {
                GSON.toJson(root, writer);
            } finally {
                writer.close();
            }
        } catch (IOException ignored) {
        }
    }

    private void ensureBuiltInConfigs() {
        File survival = getConfigFile("survival");
        File bedwarsSurvival = getConfigFile("bedwars-survival");
        boolean createSurvival = !survival.isFile();
        if (!createSurvival && bedwarsSurvival.isFile()) {
            return;
        }

        JsonObject root = new JsonObject();
        root.add("clickPattern", new JsonObject());
        JsonObject modules = new JsonObject();

        addModule(modules, "Sprint", true, org.lwjgl.input.Keyboard.KEY_NONE);
        addModule(modules, "HUD", true, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Mode", "CLASSIC"),
            setting("Red", 170),
            setting("Green", 95),
            setting("Blue", 255),
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

        addModule(modules, "BedPlates", true, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Range", 96),
            setting("Layers", 2),
            setting("Show Distance", true)
        );
        addModule(modules, "PlayerESP", true, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Mode", "CLASSIC"),
            setting("Render Mode", "BOTH"),
            setting("Red", 170),
            setting("Green", 95),
            setting("Blue", 255),
            setting("See Invis", false),
            setting("Show Names", true),
            setting("Show Health", true),
            setting("Show Distance", true),
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
            setting("Trigger", "ON_VOID"),
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
            setting("Moving Backwards", false)
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

        addModule(modules, "ClickRecorder", false, org.lwjgl.input.Keyboard.KEY_NONE,
            setting("Show Message", false)
        );

        root.add("modules", modules);
        if (createSurvival) {
            writeConfigJson(survival, root);
        }
        writeConfigJson(bedwarsSurvival, root);
    }

    private void writeConfigJson(File file, JsonObject root) {
        try {
            Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
            try {
                GSON.toJson(root, writer);
            } finally {
                writer.close();
            }
        } catch (IOException ignored) {
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

    private void applyRecordedPattern(JsonObject root) {
        ClickPatternStore.clear();
        if (!root.has("clickPattern")) {
            return;
        }

        JsonObject patternJson = root.getAsJsonObject("clickPattern");
        int index = 0;
        while (patternJson.has(Integer.toString(index))) {
            ClickPatternStore.addDelay(patternJson.get(Integer.toString(index)).getAsInt());
            index++;
        }
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
        } catch (IOException ignored) {
            return null;
        }
    }

    private void persistCurrentConfigName() {
        try {
            Files.write(currentConfigFile.toPath(), currentConfigName.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
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
