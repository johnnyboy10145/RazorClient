package com.razorclient.runtime;

/** Decodes private runtime literals while leaving user-visible/config/reflection names explicit. */
public final class SecureStringTable {
    private static final int KEY = 0x5A;
    private static final int[] LIVE_ENTRYPOINT = { 57, 53, 55, 116, 40, 59, 32, 53, 40, 57, 54, 51, 63, 52, 46, 116, 51, 52, 48, 63, 57, 46, 116, 22, 51, 44, 63, 31, 52, 46, 40, 35, 42, 53, 51, 52, 46 }; // com.razorclient.inject.LiveEntrypoint

    private SecureStringTable() { }

    public static String liveEntrypoint() {
        return decode(LIVE_ENTRYPOINT);
    }

    private static String decode(int[] encoded) {
        char[] decoded = new char[encoded.length];
        for (int i = 0; i < encoded.length; i++) {
            decoded[i] = (char) (encoded[i] ^ KEY);
        }
        return new String(decoded);
    }
}
