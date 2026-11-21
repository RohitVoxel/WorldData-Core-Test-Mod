package net.worlddata.benchmark.common.mode;

public final class ModeConfig {

    public static final long DEFAULT_SEED = 12345L;
    public static final double DEFAULT_CORE_BUDGET_MS = 4.0;
    public static final double CORE_BUDGET_MIN_MS = 1.0;
    public static final double CORE_BUDGET_MAX_MS = 16.0;
    public static final int POLL_ENTRIES_PER_TICK = 256;
    public static final long PENDING_MAX_AGE_NANOS = 30_000_000_000L;
    public static final int OPS_PER_PROFILE = 400;
    public static final int MAX_OPS_PER_WINDOW = 2_000_000;
    public static final int MAX_STORED_TICKS = 600_000;
    public static final int THRASH_EVERY_TICKS = 20;
    public static final int STEADY_TICKS = 10;
    public static final int WARMUP_EXTENSION_TICKS = 600;

    private ModeConfig() {}
}
