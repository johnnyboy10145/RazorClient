package com.razorclient.util;

import java.util.Random;

public class RandomizationHelper {
    private static final Random RANDOM = new Random();

    public static double nextGaussian(double min, double max, double stdDev) {
        double mean = (min + max) / 2.0;
        double value = mean + RANDOM.nextGaussian() * stdDev;
        return Math.max(min, Math.min(max, value));
    }

    public static float nextFloat(float min, float max) {
        return min + (max - min) * RANDOM.nextFloat();
    }

    public static int nextInt(int min, int max) {
        return min + RANDOM.nextInt(max - min + 1);
    }

    public static boolean nextBoolean() { return RANDOM.nextBoolean(); }
}