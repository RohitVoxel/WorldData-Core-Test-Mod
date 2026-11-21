package net.worlddata.benchmark.common.pack;

public record CallSnapshot(
        CallKind kind,
        long count,
        long passed,
        long waiting,
        long unloaded,
        long unavailable,
        long timeout,
        long failed,
        long minNanos,
        long p50Nanos,
        long p95Nanos,
        long p99Nanos,
        double meanNanos,
        long maxNanos,
        double failRate,
        double unavailableRate
) {
    public static CallSnapshot empty(CallKind kind) {
        return new CallSnapshot(kind, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0.0, 0L, 0.0, 0.0);
    }
}
