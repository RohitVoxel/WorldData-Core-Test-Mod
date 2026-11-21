package net.worlddata.benchmark.common.pack;

import java.util.Arrays;

public final class BatchPlanner {

    public record Batch(int number, int size, long startIndex) {
    }

    private static final long SIZE_SALT = 0x53495A45L;

    private final long seed;
    private final long total;
    private final int[] sizes;
    private final long[] starts;

    public BatchPlanner(long seed, long total) {
        this.seed = seed;
        this.total = total;
        long average = Math.max(PackConfig.BATCH_AVERAGE_FLOOR_OPS, Math.min(PackConfig.BATCH_MAX_OPS, total / PackConfig.BATCH_TARGET_COUNT));
        int[] drawn = new int[(int) Math.min(Integer.MAX_VALUE - 8, total / PackConfig.BATCH_FLOOR_OPS + 4)];
        long remaining = total;
        int n = 0;
        while (remaining > 0) {
            long h = SeedMix.at(seed ^ SIZE_SALT, n);
            double factor = 0.5 + SeedMix.bounded(h, 9, 10_000) / 10_000.0;
            long size = Math.round(average * factor);
            size = Math.max(PackConfig.BATCH_FLOOR_OPS, Math.min(PackConfig.BATCH_MAX_OPS, size));
            if (size > remaining) size = remaining;
            drawn[n++] = (int) size;
            remaining -= size;
        }
        if (n >= 2 && drawn[n - 1] < PackConfig.BATCH_FLOOR_OPS) {
            int tail = drawn[n - 1];
            if (drawn[n - 2] + tail <= PackConfig.BATCH_MAX_OPS) {
                drawn[n - 2] += tail;
                n--;
            } else {
                int deficit = PackConfig.BATCH_FLOOR_OPS - tail;
                drawn[n - 2] -= deficit;
                drawn[n - 1] = PackConfig.BATCH_FLOOR_OPS;
            }
        }
        this.sizes = Arrays.copyOf(drawn, n);
        this.starts = new long[n];
        long running = 0L;
        for (int i = 0; i < n; i++) {
            starts[i] = running;
            running += sizes[i];
        }
    }

    public long seed() {
        return seed;
    }

    public long total() {
        return total;
    }

    public int batchCount() {
        return sizes.length;
    }

    public int size(int batch) {
        return sizes[batch];
    }

    public long start(int batch) {
        return starts[batch];
    }

    public int minBatchSize() {
        int min = Integer.MAX_VALUE;
        for (int s : sizes) min = Math.min(min, s);
        return sizes.length == 0 ? 0 : min;
    }

    public int maxBatchSize() {
        int max = 0;
        for (int s : sizes) max = Math.max(max, s);
        return max;
    }

    public Batch batch(int batch) {
        return new Batch(batch, sizes[batch], starts[batch]);
    }
}
