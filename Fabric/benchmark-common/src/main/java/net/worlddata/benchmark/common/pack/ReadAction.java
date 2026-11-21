package net.worlddata.benchmark.common.pack;

public enum ReadAction {
    STATE_ID(Family.POINT, CallKind.C_STATE_ID, CallKind.V_BLOCK_STATE),
    IS_PRESENT(Family.POINT, CallKind.C_IS_PRESENT, CallKind.V_BLOCK_STATE),
    BLOCK_AT(Family.POINT, CallKind.C_BLOCK_AT, CallKind.V_BLOCK_STATE),
    MATERIAL_AT(Family.POINT, CallKind.C_MATERIAL_AT, CallKind.V_BLOCK_STATE_TAGS),
    WATER_AT(Family.POINT, CallKind.C_WATER_AT, CallKind.V_FLUID_STATE),
    IS_SUBMERGED(Family.POINT, CallKind.C_IS_SUBMERGED, CallKind.V_FLUID_STATE),
    BIOME(Family.BIOME, CallKind.C_BIOME, CallKind.V_BIOME),
    HEIGHTMAP(Family.LAYER, CallKind.C_HEIGHTMAP, CallKind.V_HEIGHT),
    COLUMN(Family.LAYER, CallKind.C_COLUMN, CallKind.V_HEIGHT_COLUMN),
    OCCUPANCY(Family.LAYER, CallKind.C_OCCUPANCY, CallKind.V_SECTION_EMPTY),
    BLOCK_INDEX(Family.LAYER, CallKind.C_BLOCK_INDEX, CallKind.V_COLUMN_SCAN),
    RAW_VIEW(Family.RAW, CallKind.C_RAW_VIEW, CallKind.V_CHUNK_SECTIONS),
    MATCHES_AT(Family.FILTER, CallKind.C_MATCHES_AT, CallKind.V_BLOCK_STATE),
    HAS_BLOCK(Family.FILTER, CallKind.C_HAS_BLOCK, CallKind.V_CHUNK_SCAN),
    COUNT_BLOCKS(Family.FILTER, CallKind.C_COUNT_BLOCKS, CallKind.V_CHUNK_SCAN),
    FIND_BLOCKS(Family.FILTER, CallKind.C_FIND_BLOCKS, CallKind.V_CHUNK_SCAN),
    VISIT_BLOCKS(Family.FILTER, CallKind.C_VISIT_BLOCKS, CallKind.V_WINDOW_SCAN),
    SUMMARIZE_BOX(Family.SPATIAL, CallKind.C_SUMMARIZE_BOX, CallKind.V_BOX_SCAN),
    SUMMARIZE_SPHERE(Family.SPATIAL, CallKind.C_SUMMARIZE_SPHERE, CallKind.V_BOX_SCAN),
    VISIT_BOX(Family.SPATIAL, CallKind.C_VISIT_BOX, CallKind.V_BOX_SCAN),
    VISIT_SPHERE(Family.SPATIAL, CallKind.C_VISIT_SPHERE, CallKind.V_BOX_SCAN),
    VISIT_COLUMN(Family.SPATIAL, CallKind.C_VISIT_COLUMN, CallKind.V_COLUMN_SCAN),
    SAMPLE_GRID(Family.SPATIAL, CallKind.C_SAMPLE_GRID, CallKind.V_GRID_SCAN),
    LIGHT_AT(Family.LIGHT, CallKind.C_LIGHT_AT, CallKind.V_BRIGHTNESS),
    AVAILABILITY(Family.CHUNK_ENTRY, CallKind.C_AVAILABILITY, CallKind.V_HAS_CHUNK),
    PEEK(Family.CHUNK_ENTRY, CallKind.C_PEEK, CallKind.V_GET_CHUNK),
    WORLD_SNAPSHOT(Family.WORLD, CallKind.C_WORLD_SNAPSHOT, CallKind.V_WORLD_TIME),
    FIND_BY_TAG(Family.LOOKUP, CallKind.C_FIND_BY_TAG, CallKind.V_CHUNK_SCAN),
    FIND_BY_NAME(Family.LOOKUP, CallKind.C_FIND_BY_NAME, CallKind.V_CHUNK_SCAN),
    FIND_BY_STATE_ID(Family.LOOKUP, CallKind.C_FIND_BY_STATE_ID, CallKind.V_CHUNK_SCAN),
    COUNT_BY_TAG(Family.LOOKUP, CallKind.C_COUNT_BY_TAG, CallKind.V_CHUNK_SCAN),
    COUNT_BY_NAME(Family.LOOKUP, CallKind.C_COUNT_BY_NAME, CallKind.V_CHUNK_SCAN),
    COUNT_BY_STATE_ID(Family.LOOKUP, CallKind.C_COUNT_BY_STATE_ID, CallKind.V_CHUNK_SCAN),
    HAS_BY_TAG(Family.LOOKUP, CallKind.C_HAS_BY_TAG, CallKind.V_CHUNK_SCAN),
    HAS_BY_NAME(Family.LOOKUP, CallKind.C_HAS_BY_NAME, CallKind.V_CHUNK_SCAN),
    HAS_BY_STATE_ID(Family.LOOKUP, CallKind.C_HAS_BY_STATE_ID, CallKind.V_CHUNK_SCAN),
    HAS_TAG_AT(Family.LOOKUP, CallKind.C_HAS_TAG_AT, CallKind.V_BLOCK_STATE_TAGS),
    STATE_ID_OF(Family.LOOKUP, CallKind.C_STATE_ID_OF, CallKind.V_BLOCK_LOOKUP),
    STATE_IDS_OF_BLOCK(Family.LOOKUP, CallKind.C_STATE_IDS_OF_BLOCK, CallKind.V_BLOCK_STATES),
    STATE_IDS_OF_TAG(Family.LOOKUP, CallKind.C_STATE_IDS_OF_TAG, CallKind.V_TAG_SCAN),
    BLOCK_ID_OF(Family.LOOKUP, CallKind.C_BLOCK_ID_OF, CallKind.V_BLOCK_LOOKUP),
    STATE_INFO(Family.LOOKUP, CallKind.C_STATE_INFO, CallKind.V_BLOCK_LOOKUP),
    STATE_HAS_TAG(Family.LOOKUP, CallKind.C_STATE_HAS_TAG, CallKind.V_BLOCK_STATE_TAGS);

    private final Family family;
    private final CallKind core;
    private final CallKind vanilla;

    ReadAction(Family family, CallKind core, CallKind vanilla) {
        this.family = family;
        this.core = core;
        this.vanilla = vanilla;
    }

    public Family family() {
        return family;
    }

    public CallKind core() {
        return core;
    }

    public CallKind vanilla() {
        return vanilla;
    }

    public boolean registryLookup() {
        return switch (this) {
            case STATE_ID_OF, STATE_IDS_OF_BLOCK, STATE_IDS_OF_TAG, BLOCK_ID_OF, STATE_INFO, STATE_HAS_TAG -> true;
            default -> false;
        };
    }

    public boolean usesChunkResult() {
        return switch (family) {
            case POINT, BIOME, LAYER, RAW, FILTER -> true;
            case LOOKUP -> !registryLookup();
            default -> false;
        };
    }

    public boolean baselineSampled() {
        return switch (family) {
            case POINT, BIOME, LAYER, RAW, FILTER, SPATIAL, LIGHT, LOOKUP -> true;
            default -> false;
        };
    }

    public boolean heavyBaseline() {
        return vanilla == CallKind.V_CHUNK_SCAN || vanilla == CallKind.V_BOX_SCAN || vanilla == CallKind.V_GRID_SCAN || vanilla == CallKind.V_TAG_SCAN;
    }
}
