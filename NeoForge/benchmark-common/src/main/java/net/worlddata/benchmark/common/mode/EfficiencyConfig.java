package net.worlddata.benchmark.common.mode;

import java.util.List;

public record EfficiencyConfig(
        ArmChoice arm,
        double windowMs,
        int ticks,
        long seed,
        double coreBudgetMs,
        boolean coreBudgetGiven,
        double safetyMs,
        int holdTicks,
        int warmupTicks,
        int thrashWorkers,
        String note
) {

    public static final List<String> POSITIONAL = List.of("windowms", "ticks", "seed", "corebudgetms", "safetyms", "holdticks", "warmup");

    public static EfficiencyConfig parse(String armText, String options) {
        ArmChoice arm = ArmChoice.parse(armText);
        ModeArgs a = ModeArgs.parse(options, POSITIONAL);
        if (a.has("arm")) arm = ArmChoice.parse(a.text("arm", "auto"));
        return new EfficiencyConfig(
                arm,
                a.doubleValue("windowms", 1.0, 0.05, 50.0),
                (int) a.longValue("ticks", 300L, 10L, 1_000_000L),
                a.longValue("seed", ModeConfig.DEFAULT_SEED, Long.MIN_VALUE, Long.MAX_VALUE),
                a.doubleValue("corebudgetms", ModeConfig.DEFAULT_CORE_BUDGET_MS, ModeConfig.CORE_BUDGET_MIN_MS, ModeConfig.CORE_BUDGET_MAX_MS),
                a.has("corebudgetms"),
                a.doubleValue("safetyms", 100.0, 1.0, 100_000.0),
                (int) a.longValue("holdticks", 100L, 5L, 100_000L),
                (int) a.longValue("warmup", 60L, 0L, 100_000L),
                a.thrashWorkers("thrash"),
                a.text("note", ""));
    }

    public long windowNanos() {
        return Math.max(1L, Math.round(windowMs * 1_000_000.0));
    }
}
