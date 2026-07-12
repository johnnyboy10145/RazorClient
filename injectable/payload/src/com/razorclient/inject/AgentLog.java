package com.razorclient.inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public final class AgentLog {
    private static final long MAX_BYTES = 2L * 1024L * 1024L;
    private static final Path LOG = Path.of(System.getProperty("user.home"), ".lunarclient", "razorclient", "razorclient.log");

    public static synchronized void info(String message) { write("INFO", message, null); }
    public static synchronized void error(String message, Throwable failure) { write("ERROR", message, failure); }

    private static void write(String level, String message, Throwable failure) {
        try {
            Files.createDirectories(LOG.getParent());
            if (Files.exists(LOG) && Files.size(LOG) >= MAX_BYTES) {
                Files.move(LOG, LOG.resolveSibling("razorclient.log.1"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            StringBuilder line = new StringBuilder(Instant.now() + " [" + level + "] " + message + System.lineSeparator());
            if (failure != null) {
                line.append(failure).append(System.lineSeparator());
                for (StackTraceElement frame : failure.getStackTrace()) line.append("  at ").append(frame).append(System.lineSeparator());
            }
            Files.writeString(LOG, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            System.err.println("[RazorClient] " + level + ": " + message);
        }
    }

    private AgentLog() {}
}
