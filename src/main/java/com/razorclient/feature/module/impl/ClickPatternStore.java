package com.razorclient.feature.module.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Fixed-size circular store so long recording sessions cannot grow without bound. */
public final class ClickPatternStore {
    private static final int MAX_DELAYS = 4096;
    private static final int[] DELAYS = new int[MAX_DELAYS];
    private static int head;
    private static int size;

    private ClickPatternStore() {
    }

    public static void clear() {
        head = 0;
        size = 0;
    }

    public static void addDelay(int delay) {
        int value = Math.max(0, delay);
        if (size < MAX_DELAYS) {
            DELAYS[(head + size) % MAX_DELAYS] = value;
            size++;
            return;
        }
        DELAYS[head] = value;
        head = (head + 1) % MAX_DELAYS;
    }

    public static boolean isEmpty() {
        return size == 0;
    }

    public static int size() {
        return size;
    }

    public static int getDelay(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index=" + index + ", size=" + size);
        }
        return DELAYS[(head + index) % MAX_DELAYS];
    }

    public static List<Integer> getDelays() {
        List<Integer> copy = new ArrayList<Integer>(size);
        for (int index = 0; index < size; index++) {
            copy.add(Integer.valueOf(DELAYS[(head + index) % MAX_DELAYS]));
        }
        return Collections.unmodifiableList(copy);
    }
}
