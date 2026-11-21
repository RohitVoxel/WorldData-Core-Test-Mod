package net.worlddata.benchmark.common.performance;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;

public final class PerformanceSampler {

    private static final int DEFAULT_RESERVOIR_SIZE = 4096;

    private final long[] reservoir;
    private final ReentrantLock lock = new ReentrantLock();
    private long count = 0;

    public PerformanceSampler() {
        this(DEFAULT_RESERVOIR_SIZE);
    }

    public PerformanceSampler(int reservoirSize) {
        this.reservoir = new long[Math.max(1, reservoirSize)];
    }

    public void sample(long valueNanos) {
        lock.lock();
        try {
            if (count < reservoir.length) {
                reservoir[(int) count] = valueNanos;
            } else {
                long j = (long) (Math.random() * (count + 1));
                if (j < reservoir.length) {
                    reservoir[(int) j] = valueNanos;
                }
            }
            count++;
        } finally {
            lock.unlock();
        }
    }

    public void reset() {
        lock.lock();
        try {
            count = 0;
        } finally {
            lock.unlock();
        }
    }

    public PerformanceStats snapshotAsFrameTimes() {
        return snapshot(true);
    }

    public PerformanceStats snapshotAsDurations() {
        return snapshot(false);
    }

    private PerformanceStats snapshot(boolean convertToFps) {
        lock.lock();
        try {
            int sampleSize = (int) Math.min(count, reservoir.length);
            if (sampleSize == 0) return PerformanceStats.EMPTY;
            long[] sorted = Arrays.copyOf(reservoir, sampleSize);
            Arrays.sort(sorted);

            double sum = 0;
            for (long v : sorted) sum += v;
            double average = sum / sorted.length;

            if (convertToFps) {

                double avgFps = 1_000_000_000.0 / average;
                double p1Low = fpsFromDurationPercentile(sorted, 0.99);
                double p01Low = fpsFromDurationPercentile(sorted, 0.999);
                double minFps = 1_000_000_000.0 / sorted[sorted.length - 1];
                return new PerformanceStats(avgFps, minFps, p1Low, p01Low, 0, 0, 0, 0);
            } else {
                double p95 = sorted[percentileIndex(sorted.length, 0.95)] / 1_000_000.0;
                double p99 = sorted[percentileIndex(sorted.length, 0.99)] / 1_000_000.0;
                double max = sorted[sorted.length - 1] / 1_000_000.0;
                double averageMillis = average / 1_000_000.0;
                return new PerformanceStats(0, 0, 0, 0, averageMillis, p95, p99, max);
            }
        } finally {
            lock.unlock();
        }
    }

    private static double fpsFromDurationPercentile(long[] sorted, double fraction) {
        long durationAtPercentile = sorted[percentileIndex(sorted.length, fraction)];
        return durationAtPercentile <= 0 ? 0 : 1_000_000_000.0 / durationAtPercentile;
    }

    private static int percentileIndex(int length, double fraction) {
        int index = (int) Math.ceil(fraction * length) - 1;
        return Math.max(0, Math.min(length - 1, index));
    }
}
