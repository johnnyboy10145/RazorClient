package com.razorclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.razorclient.feature.setting.ActionSetting;
import com.razorclient.feature.setting.BooleanSetting;
import com.razorclient.feature.setting.DecimalSetting;
import com.razorclient.feature.setting.EnumSetting;
import com.razorclient.feature.setting.IntRangeSetting;
import com.razorclient.feature.setting.NumberSetting;
import com.razorclient.feature.setting.Setting;

/** Typed, side-effect-free config encoding plus explicit setting application. */
final class SettingCodec {
    private SettingCodec() {}

    static Object decode(Setting setting, JsonElement element, String path) {
        if (setting instanceof BooleanSetting) return Boolean.valueOf(requireBoolean(element, path));
        if (setting instanceof DecimalSetting) return Double.valueOf(requireDouble(element, path));
        if (setting instanceof IntRangeSetting) {
            if (!element.isJsonArray() || element.getAsJsonArray().size() != 2) {
                throw new IllegalArgumentException(path + " must be a two-value array");
            }
            JsonArray values = element.getAsJsonArray();
            return new int[] {requireInt(values.get(0), path + "[0]"), requireInt(values.get(1), path + "[1]")};
        }
        if (setting instanceof NumberSetting) return Integer.valueOf(requireInt(element, path));
        if (setting instanceof EnumSetting) {
            String value = requireString(element, path);
            if (!containsEnumValue((EnumSetting<?>) setting, value)) {
                throw new IllegalArgumentException(path + " has unknown value " + value);
            }
            return value;
        }
        throw new IllegalArgumentException(path + " has unsupported setting type " + setting.getClass().getName());
    }

    static Object capture(Setting setting) {
        if (setting instanceof BooleanSetting) return Boolean.valueOf(((BooleanSetting) setting).isEnabled());
        if (setting instanceof DecimalSetting) return Double.valueOf(((DecimalSetting) setting).getValue());
        if (setting instanceof IntRangeSetting) {
            IntRangeSetting range = (IntRangeSetting) setting;
            return new int[] {range.getLow(), range.getHigh()};
        }
        if (setting instanceof NumberSetting) return Integer.valueOf(((NumberSetting) setting).getValue());
        if (setting instanceof EnumSetting) return ((EnumSetting<?>) setting).getValue().name();
        throw new IllegalArgumentException("Unsupported setting type " + setting.getClass().getName());
    }

    static void apply(Setting setting, Object value) {
        if (setting instanceof BooleanSetting) {
            ((BooleanSetting) setting).setEnabled(((Boolean) value).booleanValue());
        } else if (setting instanceof DecimalSetting) {
            ((DecimalSetting) setting).setManualValue(((Double) value).doubleValue(), false);
        } else if (setting instanceof IntRangeSetting) {
            int[] range = (int[]) value;
            ((IntRangeSetting) setting).setRange(range[0], range[1], false);
        } else if (setting instanceof NumberSetting) {
            ((NumberSetting) setting).setManualValue(((Integer) value).intValue(), false);
        } else if (setting instanceof EnumSetting) {
            if (!((EnumSetting<?>) setting).setValueByName((String) value)) {
                throw new IllegalStateException("Unable to apply enum setting " + setting.getName());
            }
        } else {
            throw new IllegalArgumentException("Unsupported setting type " + setting.getClass().getName());
        }
    }

    static JsonElement encode(Setting setting) {
        if (setting instanceof ActionSetting) return null;
        if (setting instanceof BooleanSetting) return new JsonPrimitive(((BooleanSetting) setting).isEnabled());
        if (setting instanceof DecimalSetting) return new JsonPrimitive(((DecimalSetting) setting).getValue());
        if (setting instanceof IntRangeSetting) {
            IntRangeSetting range = (IntRangeSetting) setting;
            JsonArray values = new JsonArray();
            values.add(new JsonPrimitive(range.getLow()));
            values.add(new JsonPrimitive(range.getHigh()));
            return values;
        }
        if (setting instanceof NumberSetting) return new JsonPrimitive(((NumberSetting) setting).getValue());
        if (setting instanceof EnumSetting) return new JsonPrimitive(((EnumSetting<?>) setting).getValue().name());
        throw new IllegalArgumentException("Unsupported setting type " + setting.getClass().getName());
    }

    private static boolean containsEnumValue(EnumSetting<?> setting, String name) {
        for (Enum<?> value : setting.getValues()) if (value.name().equalsIgnoreCase(name)) return true;
        return false;
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

    private static double requireDouble(JsonElement element, String path) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(path + " must be a number");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) throw new IllegalArgumentException(path + " must be finite");
        return value;
    }

    private static String requireString(JsonElement element, String path) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(path + " must be a string");
        }
        return element.getAsString();
    }
}
