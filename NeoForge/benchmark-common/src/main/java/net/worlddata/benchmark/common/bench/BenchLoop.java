package net.worlddata.benchmark.common.bench;

import java.util.Arrays;

public final class BenchLoop {

    public static final int MAX_SAMPLES = 1_000_000;
    public static final long PASS_BUDGET_NANOS = 4_000_000_000L;
    private static final int FLOOR_SAMPLES = 20_000;

    private static volatile long sink;

    private BenchLoop() {}

    public static long timerFloor(BenchClock clock) {
        int[] gaps = new int[FLOOR_SAMPLES];
        for (int i = 0; i < gaps.length; i++) {
            long first = clock.nanos();
            long second = clock.nanos();
            gaps[i] = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, second - first));
        }
        Arrays.sort(gaps);
        return gaps[gaps.length / 2];
    }

    public static BenchSideResult run(BenchTarget target, BenchConfig config, BenchClock clock, long passBudgetNanos) {
        BenchApi api = config.api();
        int block = api.blockSize();
        int count = config.count();

        sink = target.block(api, 0, api.warmup());

        long throughputStart = clock.nanos();
        long throughputLimit = throughputStart + passBudgetNanos;
        int done = 0;
        long checksum = 0L;
        boolean throughputTruncated = false;
        while (done < count) {
            int step = Math.min(block, count - done);
            checksum += target.block(api, done, step);
            done += step;
            if (done < count && clock.nanos() >= throughputLimit) {
                throughputTruncated = true;
                break;
            }
        }
        long wall = clock.nanos() - throughputStart;
        sink = checksum;

        int wanted = Math.min(done, MAX_SAMPLES);
        int[] samples = new int[wanted];
        long min = Long.MAX_VALUE;
        long max = 0L;
        long sum = 0L;
        long probeDigest = 0L;
        int taken = 0;
        boolean latencyTruncated = false;
        int mask = block - 1;
        long latencyLimit = clock.nanos() + passBudgetNanos;
        for (int i = 0; i < wanted; i++) {
            long before = clock.nanos();
            probeDigest += target.call(api, i);
            long after = clock.nanos();
            long elapsed = Math.max(0L, after - before);
            if (elapsed < min) min = elapsed;
            if (elapsed > max) max = elapsed;
            sum += elapsed;
            samples[i] = (int) Math.min(elapsed, Integer.MAX_VALUE);
            taken = i + 1;
            if ((i & mask) == mask && after >= latencyLimit && taken < wanted) {
                latencyTruncated = true;
                break;
            }
        }
        sink = probeDigest;
        Arrays.sort(samples, 0, taken);

        BenchStats stats = new BenchStats(done, wall, taken,
                taken == 0 ? 0L : min,
                BenchStats.percentile(samples, taken, 0.50),
                BenchStats.percentile(samples, taken, 0.99),
                max,
                taken == 0 ? 0.0 : (double) sum / taken,
                throughputTruncated, latencyTruncated);
        return new BenchSideResult(target.label(), target.timed(api, config.mode()), checksum, stats);
    }
}
