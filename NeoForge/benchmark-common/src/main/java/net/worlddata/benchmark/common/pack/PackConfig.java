package net.worlddata.benchmark.common.pack;

public final class PackConfig {

    public static final long DEFAULT_TOTAL_OPS = 200_000L;
    public static final long DEFAULT_SEED = 12345L;

    public static final int BATCH_MAX_OPS = 8_000;
    public static final int BATCH_FLOOR_OPS = 400;
    public static final int BATCH_AVERAGE_FLOOR_OPS = 800;
    public static final int BATCH_TARGET_COUNT = 200;
    public static final int INTER_BATCH_PAUSE_TICKS = 0;

    public static final int THRASH_WORKERS = 16;
    public static final int THRASH_WORKERS_MIN = 16;
    public static final int THRASH_WORKERS_MAX = 64;
    public static final int SHARED_CELLS = 16;
    public static final int SHARED_PERCENT = 40;
    public static final int PROBE_EVERY_BATCHES = 8;
    public static final int PROBE_READS_MAX = 256;
    public static final int PROBE_READS_MIN = 32;
    public static final int PROBE_CELLS = 8;
    public static final long PROBE_ESTIMATE_NANOS = 600_000L;

    public static final long OPS_PER_SECOND_UNLIMITED = 0L;
    public static final long OPS_PER_SECOND_MAX = 1_000_000_000L;
    public static final long RATE_WINDOW_NANOS = 1_000_000_000L;
    public static final long RATE_NOMINAL_TICK_NANOS = 50_000_000L;

    public static final int TICK_BUDGET_MIN_MS = 1;
    public static final int TICK_BUDGET_MAX_MS = 5;
    public static final int TICK_BUDGET_DEFAULT_MS = 3;
    public static final long TICK_BUDGET_DEFAULT_NANOS = TICK_BUDGET_DEFAULT_MS * 1_000_000L;
    public static final long TICK_BUDGET_MIN_NANOS = TICK_BUDGET_MIN_MS * 1_000_000L;
    public static final long TICK_BUDGET_MAX_NANOS = TICK_BUDGET_MAX_MS * 1_000_000L;
    public static final int POLL_SHARE_PERCENT = 40;
    public static final int POLL_ENTRIES_PER_TICK = 512;
    public static final int DRAIN_POLL_ENTRIES = 4096;
    public static final int DRAIN_DEADLINE_TICKS = 600;
    public static final long SIDE_LANE_DEADLINE_NANOS = 90_000_000_000L;
    public static final int LIGHT_FORCE_TICKS = 100;
    public static final int LIGHT_PER_CLIENT_TICK = 512;

    public static final int WAVES_FULL_RUN = 60;
    public static final long WAVES_FULL_RUN_MIN_OPS = 120_000L;
    public static final long WAVE_MIN_OPS_PER_WAVE = 2_000L;
    public static final int WAVE_CHUNKS_MIN = 1;
    public static final int WAVE_CHUNKS_MAX = 64;
    public static final int WAVE_POOL_SIDE = 32;
    public static final int WAVE_POOL_OFFSET = 24;
    public static final int WAVE_ANCHORS = 12;
    public static final int WAVE_MAX_WIDTH = 8;
    public static final int WAVE_CALL_MAX_CHUNKS = 16;
    public static final int WAVE_REVISIT_GAP = 2;
    public static final int WAVE_HOLD_TICKS = 20;
    public static final int WAVE_POLL_SHARE_PERCENT = 30;
    public static final int UNIQUE_CHUNK_CAP = 1_500;
    public static final long FORCE_LOAD_CALL_CAP = 4_096L;

    public static final int HOTSPOT_CLUSTERS = 6;
    public static final int HOTSPOT_CLUSTER_SIDE = 2;
    public static final int HOTSPOT_CHUNKS = HOTSPOT_CLUSTERS * HOTSPOT_CLUSTER_SIDE * HOTSPOT_CLUSTER_SIDE;

    public static final int HOTSPOT_PERCENT = 75;
    public static final int WRITE_PERCENT_IN_MUTATION = 12;
    public static final int BASELINE_EVERY = 32;
    public static final int HEAVY_BASELINE_EVERY = 256;
    public static final int BASELINE_FLOOR = 64;
    public static final int HEAVY_BASELINE_FLOOR = 16;

    public static final int ALLOC_EVERY_TICKS = 100;
    public static final int ALLOC_CALLS = 64;

    public static final int LAG_PROBE_SLOTS = 64;
    public static final int LAG_TIMEOUT_TICKS = 40;
    public static final int AUDIT_SAMPLES = 4096;

    public static final int Y_MIN = 40;
    public static final int Y_SPAN = 56;
    public static final int ARENA_Y = 232;
    public static final int ARENA_LAYERS = 8;

    public static final long SINK_BUDGET_BYTES_PER_SLICE = 64 * 1024;
    public static final int SINK_RING = 8192;

    private PackConfig() {}

    public static int waveCount(long totalOps) {
        if (totalOps >= WAVES_FULL_RUN_MIN_OPS) return WAVES_FULL_RUN;
        return (int) Math.max(1L, Math.min(WAVES_FULL_RUN, totalOps / WAVE_MIN_OPS_PER_WAVE));
    }

    public static boolean fullWaveRun(long totalOps) {
        return totalOps >= WAVES_FULL_RUN_MIN_OPS;
    }

    public static long rateCapacity(long opsPerSecond) {
        return Math.max(2L, opsPerSecond / 10L);
    }

    public static long clampTickBudgetNanos(long nanos) {
        return Math.max(TICK_BUDGET_MIN_NANOS, Math.min(TICK_BUDGET_MAX_NANOS, nanos));
    }

    public static long tickBudgetNanosFromMillis(int millis) {
        return clampTickBudgetNanos(millis * 1_000_000L);
    }
}
