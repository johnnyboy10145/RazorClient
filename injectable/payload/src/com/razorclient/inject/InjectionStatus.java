package com.razorclient.inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public final class InjectionStatus {
    private static final String STATUS_PROPERTY = "razorclient.live.statusFile";

    public static void write(String phase) {
        write(phase, "");
    }

    public static void write(String phase, String detail) {
        String file = System.getProperty(STATUS_PROPERTY);
        if (file == null || file.isEmpty()) {
            return;
        }
        try {
            Path path = Path.of(file);
            Files.createDirectories(path.getParent());
            String line = "{\"ts\":\"" + escape(Instant.now().toString())
                + "\",\"source\":\"java\",\"phase\":\"" + escape(phase)
                + "\",\"detail\":\"" + escape(detail == null ? "" : detail) + "\"}"
                + System.lineSeparator();
            Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\':
                    result.append("\\\\");
                    break;
                case '"':
                    result.append("\\\"");
                    break;
                case '\n':
                    result.append("\\n");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                case '\t':
                    result.append("\\t");
                    break;
                default:
                    result.append(ch);
                    break;
            }
        }
        return result.toString();
    }

    private InjectionStatus() {}
}
