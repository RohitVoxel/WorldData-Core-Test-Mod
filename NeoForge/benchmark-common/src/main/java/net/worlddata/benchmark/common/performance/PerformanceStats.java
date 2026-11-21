package net.worlddata.benchmark.common.performance;

public record PerformanceStats(
        double averageFps,
        double minFps,
        double onePercentLowFps,
        double pointOnePercentLowFps,
        double averageTickMillis,
        double p95TickMillis,
        double p99TickMillis,
        double maxTickMillis
) {
    public static final PerformanceStats EMPTY = new PerformanceStats(0, 0, 0, 0, 0, 0, 0, 0);
}
