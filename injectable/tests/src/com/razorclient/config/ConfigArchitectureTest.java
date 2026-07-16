package com.razorclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.DecimalSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.IntRangeSetting;
import com.razorclient.feature.setting.NumberSetting;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Package-local codec/storage tests without exposing production internals. */
public final class ConfigArchitectureTest {
    private enum Mode { FIRST, SECOND }

    private ConfigArchitectureTest() {}

    public static void run() throws Exception {
        testTypedCodecs();
        testAtomicStorage();
    }

    private static void testTypedCodecs() {
        BooleanSetting bool = new BooleanSetting("Enabled", true);
        NumberSetting number = new NumberSetting("Count", 0, 20, 1, 7);
        DecimalSetting decimal = new DecimalSetting("Scale", 0.0D, 4.0D, 0.1D, 1.5D);
        IntRangeSetting range = new IntRangeSetting("Range", 3, 8, 0, 10);
        EnumSetting<Mode> mode = new EnumSetting<Mode>("Mode", Mode.values(), Mode.FIRST);

        check(Boolean.TRUE.equals(SettingCodec.capture(bool)), "boolean capture failed");
        check(Integer.valueOf(7).equals(SettingCodec.capture(number)), "number capture failed");
        check(Double.valueOf(1.5D).equals(SettingCodec.capture(decimal)), "decimal capture failed");
        check(Arrays.equals(new int[] {3, 8}, (int[]) SettingCodec.capture(range)), "range capture failed");
        check("FIRST".equals(SettingCodec.capture(mode)), "enum capture failed");

        check(Boolean.FALSE.equals(SettingCodec.decode(bool, new JsonPrimitive(false), "Enabled")),
            "boolean decode failed");
        check(Integer.valueOf(9).equals(SettingCodec.decode(number, new JsonPrimitive(9), "Count")),
            "integer decode failed");
        check(Double.valueOf(2.5D).equals(SettingCodec.decode(decimal, new JsonPrimitive(2.5D), "Scale")),
            "decimal decode failed");
        JsonArray values = new JsonArray();
        values.add(new JsonPrimitive(2));
        values.add(new JsonPrimitive(6));
        check(Arrays.equals(new int[] {2, 6}, (int[]) SettingCodec.decode(range, values, "Range")),
            "range decode failed");
        check("second".equals(SettingCodec.decode(mode, new JsonPrimitive("second"), "Mode")),
            "case-insensitive enum decode failed");

        SettingCodec.apply(number, Integer.valueOf(12));
        SettingCodec.apply(decimal, Double.valueOf(2.2D));
        SettingCodec.apply(range, new int[] {1, 9});
        check(number.getValue() == 12 && decimal.getValue() == 2.2D
            && range.getLow() == 1 && range.getHigh() == 9, "typed application failed");

        JsonElement encodedRange = SettingCodec.encode(range);
        check(encodedRange.isJsonArray() && encodedRange.getAsJsonArray().size() == 2,
            "range encoding failed");
        expectFailure(() -> SettingCodec.decode(number, new JsonPrimitive(1.5D), "Count"),
            "fractional integer was accepted");
        expectFailure(() -> SettingCodec.decode(decimal, new JsonPrimitive(Double.NaN), "Scale"),
            "non-finite decimal was accepted");
        expectFailure(() -> SettingCodec.decode(mode, new JsonPrimitive("UNKNOWN"), "Mode"),
            "unknown enum was accepted");
    }

    private static void testAtomicStorage() throws Exception {
        Path directory = Files.createTempDirectory("razor-config-test-");
        try {
            Path destination = directory.resolve("nested").resolve("profile.json");
            byte[] first = "{\"version\":1}".getBytes(StandardCharsets.UTF_8);
            byte[] second = "{\"version\":2}".getBytes(StandardCharsets.UTF_8);
            AtomicFileStore.write(destination.toFile(), first);
            check(Arrays.equals(first, Files.readAllBytes(destination)), "initial atomic write failed");
            AtomicFileStore.write(destination.toFile(), second);
            check(Arrays.equals(second, Files.readAllBytes(destination)), "atomic replacement failed");
            check(!Files.exists(destination.resolveSibling("profile.json.tmp")),
                "temporary config file remained after promotion");
        } finally {
            deleteRecursively(directory);
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (path == null || !Files.exists(path)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(value -> {
                try {
                    Files.deleteIfExists(value);
                } catch (java.io.IOException failure) {
                    throw new RuntimeException(failure);
                }
            });
        }
    }

    private static void expectFailure(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
