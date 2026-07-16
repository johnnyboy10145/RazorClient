package com.razorclient.config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Durable replace-in-place storage shared by config data and active-profile metadata. */
final class AtomicFileStore {
    private AtomicFileStore() {}

    static void write(File destination, byte[] bytes) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create directory " + parent.getAbsolutePath());
        }
        File temporary = new File(parent, destination.getName() + ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
                output.flush();
                output.getFD().sync();
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (temporary.isFile() && !temporary.delete()) temporary.deleteOnExit();
        }
    }
}
