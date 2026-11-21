package net.worlddata.benchmark.common.bench;

public record BenchStats(long ops, long wallNanos, int samples, long minNanos, long p50Nanos, long p99Nanos,
                         long maxNanos, double meanNanos, boolean throughputTruncated, boolean latencyTruncated) {

    public double opsPerSecond() {
        return wallNanos <= 0L ? 0.0 : ops * 1_000_000_000.0 / wallNanos;
    }

    public boolean truncated() {
        return throughputTruncated || latencyTruncated;
    }

    public static long percentile(int[] sorted, int length, double fraction) {
        if (length <= 0) return 0L;
        int rank = (int) Math.ceil(fraction * length) - 1;
        return sorted[Math.max(0, Math.min(length - 1, rank))];
    }
}
