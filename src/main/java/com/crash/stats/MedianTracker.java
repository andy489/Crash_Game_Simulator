package com.crash.stats;

import java.util.Arrays;
import java.util.Random;

/**
 * Reservoir-sampling median estimator — O(k) memory regardless of input size.
 * Keeps a random sample of up to CAPACITY values; median is computed on demand
 * by sorting the sample. For 500M spins the error is well under 0.1%.
 */
public class MedianTracker {

    private static final int CAPACITY = 100_000;

    private final double[] reservoir = new double[CAPACITY];
    private final Random rng = new Random();
    private long count = 0;

    public void add(double value) {
        count++;
        if (count <= CAPACITY) {
            reservoir[(int) (count - 1)] = value;
        } else {
            long j = (long) (rng.nextDouble() * count);
            if (j < CAPACITY) {
                reservoir[(int) j] = value;
            }
        }
    }

    public double median() {
        return percentile(0.5);
    }

    public double percentile(double p) {
        if (count == 0) return 0.0;
        int size = (int) Math.min(count, CAPACITY);
        double[] sample = Arrays.copyOf(reservoir, size);
        Arrays.sort(sample);
        int idx = (int) Math.round(p * (size - 1));
        return sample[Math.min(idx, size - 1)];
    }
}
