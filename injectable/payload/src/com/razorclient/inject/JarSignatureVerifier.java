package com.razorclient.inject;

import java.io.InputStream;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Forces Java's standard JAR signer to validate every signed entry before loading. */
public final class JarSignatureVerifier {
    private JarSignatureVerifier() { }

    public static boolean verify(String path, String expectedSignerSha256) {
        if (path == null || path.isEmpty() || expectedSignerSha256 == null) return false;
        String expected = expectedSignerSha256.replace(":", "").trim().toUpperCase(Locale.ROOT);
        if (!expected.matches("[0-9A-F]{64}")) return false;
        try (JarFile jar = new JarFile(Path.of(path).toFile(), true)) {
            byte[] buffer = new byte[8192];
            java.util.Enumeration<JarEntry> entries = jar.entries();
            int verifiedEntries = 0;
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName().startsWith("META-INF/")) continue;
                try (InputStream input = jar.getInputStream(entry)) {
                    while (input.read(buffer) != -1) { }
                }
                CodeSigner[] signers = entry.getCodeSigners();
                if (signers == null || signers.length == 0 || !containsExpectedSigner(signers, expected)) {
                    AgentLog.error("Unsigned or unexpected payload entry: " + entry.getName(), null);
                    return false;
                }
                verifiedEntries++;
            }
            return verifiedEntries > 0;
        } catch (Throwable failure) {
            AgentLog.error("Payload JAR signature verification failed", failure);
            return false;
        }
    }

    private static boolean containsExpectedSigner(CodeSigner[] signers, String expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (CodeSigner signer : signers) {
            if (signer.getSignerCertPath().getCertificates().isEmpty()) continue;
            Certificate leaf = signer.getSignerCertPath().getCertificates().get(0);
            byte[] hash = digest.digest(leaf.getEncoded());
            if (toHex(hash).equals(expected)) return true;
        }
        return false;
    }

    private static String toHex(byte[] bytes) {
        char[] hex = new char[bytes.length * 2];
        final char[] alphabet = "0123456789ABCDEF".toCharArray();
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xFF;
            hex[index * 2] = alphabet[value >>> 4];
            hex[index * 2 + 1] = alphabet[value & 0x0F];
        }
        return new String(hex);
    }
}
