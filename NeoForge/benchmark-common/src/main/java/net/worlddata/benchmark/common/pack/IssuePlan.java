package net.worlddata.benchmark.common.pack;

public final class IssuePlan {

    private static final long LIGHT_SALT = 0x4C49474854L;
    private static final long WRITE_SALT = 0x5752495445L;
    private static final long SHARED_SALT = 0x53484152L;

    private static final ReadAction[] POINT = {ReadAction.STATE_ID, ReadAction.BLOCK_AT, ReadAction.MATERIAL_AT, ReadAction.WATER_AT, ReadAction.IS_PRESENT, ReadAction.IS_SUBMERGED};
    private static final int[] POINT_CUM = {35, 55, 75, 85, 95, 100};
    private static final ReadAction[] LAYER = {ReadAction.HEIGHTMAP, ReadAction.COLUMN, ReadAction.OCCUPANCY, ReadAction.BLOCK_INDEX};
    private static final int[] LAYER_CUM = {40, 65, 85, 100};
    private static final ReadAction[] FILTER = {ReadAction.MATCHES_AT, ReadAction.HAS_BLOCK, ReadAction.COUNT_BLOCKS, ReadAction.FIND_BLOCKS, ReadAction.VISIT_BLOCKS};
    private static final int[] FILTER_CUM = {30, 50, 70, 80, 100};
    private static final ReadAction[] SPATIAL = {ReadAction.SUMMARIZE_BOX, ReadAction.SUMMARIZE_SPHERE, ReadAction.VISIT_BOX, ReadAction.VISIT_SPHERE, ReadAction.VISIT_COLUMN, ReadAction.SAMPLE_GRID};
    private static final int[] SPATIAL_CUM = {25, 40, 60, 70, 85, 100};
    private static final ReadAction[] META = {ReadAction.BIOME, ReadAction.AVAILABILITY, ReadAction.RAW_VIEW, ReadAction.PEEK, ReadAction.WORLD_SNAPSHOT};
    private static final int[] META_CUM = {25, 50, 70, 90, 100};
    private static final ReadAction[] LOOKUP = {ReadAction.FIND_BY_TAG, ReadAction.FIND_BY_NAME, ReadAction.FIND_BY_STATE_ID, ReadAction.COUNT_BY_TAG, ReadAction.COUNT_BY_NAME, ReadAction.COUNT_BY_STATE_ID,
            ReadAction.HAS_BY_TAG, ReadAction.HAS_BY_NAME, ReadAction.HAS_BY_STATE_ID, ReadAction.HAS_TAG_AT, ReadAction.STATE_ID_OF, ReadAction.STATE_IDS_OF_BLOCK, ReadAction.STATE_IDS_OF_TAG,
            ReadAction.BLOCK_ID_OF, ReadAction.STATE_INFO, ReadAction.STATE_HAS_TAG};
    private static final int[] LOOKUP_CUM = {5, 9, 13, 18, 22, 26, 30, 34, 38, 60, 68, 74, 78, 86, 94, 100};

    private IssuePlan() {}

    public static void fill(PlannedIssue out, long seed, long index, BatchProfile profile, int indexInBatch) {
        fill(out, seed, index, profile, indexInBatch, YRange.STANDARD);
    }

    public static void fill(PlannedIssue out, long seed, long index, BatchProfile profile, int indexInBatch, YRange yRange) {
        fill(out, seed, index, profile, indexInBatch, yRange, PackConfig.THRASH_WORKERS);
    }

    public static void fill(PlannedIssue out, long seed, long index, BatchProfile profile, int indexInBatch, YRange yRange, int workers) {
        long h = SeedMix.at(seed, index);
        out.hash = h;
        out.kind = profile.kindOf(SeedMix.basisPoint(h));
        out.worker = indexInBatch % workers;
        long h2 = SeedMix.mix(h);
        out.write = false;
        out.coldProbe = false;
        out.shared = false;
        out.cell = -1;
        out.hotspot = SeedMix.percent(h2) < PackConfig.HOTSPOT_PERCENT;
        out.slot = SeedMix.bounded(h2, 8, out.hotspot ? PackConfig.HOTSPOT_CHUNKS : PackConfig.WAVE_CHUNKS_MAX);
        out.localX = SeedMix.bounded(h2, 20, 16);
        out.localZ = SeedMix.bounded(h2, 26, 16);
        out.y = yRange.readMin() + SeedMix.bounded(h2, 32, yRange.readSpan());
        out.extra = SeedMix.bounded(h2, 40, 1 << 20);
        int pick = SeedMix.bounded(h2, 3, 100);
        if (out.kind.stable() && SeedMix.percent(SeedMix.mix(h2)) < PackConfig.SHARED_PERCENT) {
            int cell = SeedMix.bounded(h2, 44, PackConfig.SHARED_CELLS);
            long c = SeedMix.at(seed ^ SHARED_SALT, (long) profile.batchNo() * PackConfig.SHARED_CELLS + cell);
            out.shared = true;
            out.cell = cell;
            out.hotspot = true;
            out.slot = SeedMix.bounded(c, 8, PackConfig.HOTSPOT_CHUNKS);
            out.localX = SeedMix.bounded(c, 20, 16);
            out.localZ = SeedMix.bounded(c, 26, 16);
            out.y = yRange.readMin() + SeedMix.bounded(c, 32, yRange.readSpan());
            out.extra = SeedMix.bounded(c, 40, 1 << 20);
            pick = SeedMix.bounded(SeedMix.mix(c + out.kind.ordinal()), 3, 100);
        }
        out.action = switch (out.kind) {
            case POINT -> choose(POINT, POINT_CUM, pick);
            case LAYER -> choose(LAYER, LAYER_CUM, pick);
            case FILTER -> choose(FILTER, FILTER_CUM, pick);
            case SPATIAL -> choose(SPATIAL, SPATIAL_CUM, pick);
            case LIGHT -> ReadAction.LIGHT_AT;
            case META -> choose(META, META_CUM, pick);
            case LOOKUP -> choose(LOOKUP, LOOKUP_CUM, pick);
        };
        if (out.action == ReadAction.AVAILABILITY && SeedMix.percent(SeedMix.mix(h2)) < 25) {
            out.coldProbe = true;
        }
    }

    public static boolean isWrite(long seed, long index) {
        return SeedMix.percent(SeedMix.at(seed ^ WRITE_SALT, index)) < PackConfig.WRITE_PERCENT_IN_MUTATION;
    }

    public static long lightHash(long seed, long lightIndex) {
        return SeedMix.at(seed ^ LIGHT_SALT, lightIndex);
    }

    private static ReadAction choose(ReadAction[] actions, int[] cumulative, int pick) {
        for (int i = 0; i < actions.length; i++) {
            if (pick < cumulative[i]) return actions[i];
        }
        return actions[actions.length - 1];
    }
}
