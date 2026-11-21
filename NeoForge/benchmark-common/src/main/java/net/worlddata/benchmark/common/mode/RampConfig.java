package net.worlddata.benchmark.common.mode;

import java.util.List;

public record RampConfig(
        double targetMs,
        double overMs,
        int holdTicks,
        long startOps,
        long stepOps,
        double stepSeconds,
        double tickIssueMs,
        long seed,
        long maxOps,
        double maxMinutes,
        int thrashWorkers,
        double packBaselineMs,
        double coreBudgetMs,
        ArmChoice arm,
        String note
) {

    public static final List<String> POSITIONAL = List.of("seed", "overms", "holdticks", "startops", "stepops", "tickissuems", "stepseconds", "maxops", "maxminutes", "packbaselinems", "corebudgetms");

    public static RampConfig parse(double targetMs, String options) {
        if (Double.isNaN(targetMs) || targetMs < 1.0 || targetMs > 1000.0) throw new IllegalArgumentException("targetMs must be between 1 and 1000");
        ModeArgs a = ModeArgs.parse(options, POSITIONAL);
        long start = a.longValue("startops", 100L, 1L, 1_000_000_000L);
        long maxOps = a.longValue("maxops", 5_000_000L, 1L, 1_000_000_000L);
        if (maxOps < start) throw new IllegalArgumentException("maxOps must be at least startOps");
        return new RampConfig(
                targetMs,
                a.doubleValue("overms", 10.0, 0.0, 1000.0),
                (int) a.longValue("holdticks", 100L, 5L, 100_000L),
                start,
                a.longValue("stepops", 100L, 1L, 1_000_000_000L),
                a.doubleValue("stepseconds", 1.0, 0.05, 600.0),
                a.doubleValue("tickissuems", 0.0, 0.0, 1000.0),
                a.longValue("seed", ModeConfig.DEFAULT_SEED, Long.MIN_VALUE, Long.MAX_VALUE),
                maxOps,
                a.doubleValue("maxminutes", 60.0, 0.1, 1440.0),
                a.thrashWorkers("thrash"),
                a.doubleValue("packbaselinems", -1.0, 0.0, 1000.0),
                a.doubleValue("corebudgetms", -1.0, ModeConfig.CORE_BUDGET_MIN_MS, ModeConfig.CORE_BUDGET_MAX_MS),
                ArmChoice.parse(a.text("arm", "auto")),
                a.text("note", ""));
    }

    public double limitMs() {
        return targetMs + overMs;
    }

    public long limitNanos() {
        return Math.round(limitMs() * 1_000_000.0);
    }

    public long stepNanos() {
        return Math.max(1L, Math.round(stepSeconds * 1_000_000_000.0));
    }

    public long tickIssueNanos() {
        return Math.round(tickIssueMs * 1_000_000.0);
    }

    public long rateAtStep(int step) {
        return Math.min(maxOps, startOps + (long) step * stepOps);
    }

    public boolean rateCapped(int step) {
        return startOps + (long) step * stepOps >= maxOps;
    }
}
