package net.worlddata.benchmark.common.pack;

public enum CallKind {
    V_BLOCK_STATE("Level.getBlockState", Arm.VANILLA, Family.POINT),
    V_BLOCK_STATE_TAGS("Level.getBlockState + tags", Arm.VANILLA, Family.POINT),
    V_FLUID_STATE("Level.getBlockState().getFluidState", Arm.VANILLA, Family.POINT),
    V_BIOME("Level.getBiome", Arm.VANILLA, Family.BIOME),
    V_HEIGHT("Level.getHeight", Arm.VANILLA, Family.LAYER),
    V_HEIGHT_COLUMN("Level.getHeight x2 + surface read", Arm.VANILLA, Family.LAYER),
    V_SECTION_EMPTY("LevelChunkSection.hasOnlyAir", Arm.VANILLA, Family.LAYER),
    V_COLUMN_SCAN("column getBlockState scan", Arm.VANILLA, Family.LAYER),
    V_CHUNK_SECTIONS("LevelChunk section walk", Arm.VANILLA, Family.RAW),
    V_CHUNK_SCAN("chunk getBlockState scan", Arm.VANILLA, Family.FILTER),
    V_WINDOW_SCAN("window getBlockState scan", Arm.VANILLA, Family.FILTER),
    V_BOX_SCAN("box getBlockState scan", Arm.VANILLA, Family.SPATIAL),
    V_GRID_SCAN("grid getHeight scan", Arm.VANILLA, Family.SPATIAL),
    V_BRIGHTNESS("Level.getBrightness", Arm.VANILLA, Family.LIGHT),
    V_HAS_CHUNK("Level.hasChunk", Arm.VANILLA, Family.CHUNK_ENTRY),
    V_GET_CHUNK("Level.getChunk (loaded)", Arm.VANILLA, Family.CHUNK_ENTRY),
    V_CHUNK_FUTURE("ServerChunkCache.getChunkFuture", Arm.VANILLA, Family.CHUNK_ENTRY),
    V_FUTURE_STATE("chunk future state", Arm.VANILLA, Family.CHUNK_ENTRY),
    V_ADD_TICKET("ServerChunkCache.addRegionTicket (force-load)", Arm.VANILLA, Family.TICKETS),
    V_REMOVE_TICKET("ServerChunkCache.removeRegionTicket (release)", Arm.VANILLA, Family.TICKETS),
    V_WORLD_TIME("Level.getDayTime + isRaining", Arm.VANILLA, Family.WORLD),
    V_SET_BLOCK("Level.setBlock", Arm.VANILLA, Family.WRITE),
    V_BLOCK_LOOKUP("BuiltInRegistries.BLOCK get + Block.getId", Arm.VANILLA, Family.LOOKUP),
    V_BLOCK_STATES("Block.getStateDefinition states walk", Arm.VANILLA, Family.LOOKUP),
    V_TAG_SCAN("BuiltInRegistries.BLOCK tag scan", Arm.VANILLA, Family.LOOKUP),

    C_CHUNK_QUERY("requests().chunk(load=false)", Arm.CORE, Family.CHUNK_ENTRY),
    C_PEEK("requests().peek", Arm.CORE, Family.CHUNK_ENTRY),
    C_AVAILABILITY("requests().availability", Arm.CORE, Family.CHUNK_ENTRY),
    C_STATE_ID("stateIdAt", Arm.CORE, Family.POINT),
    C_IS_PRESENT("isPresent", Arm.CORE, Family.POINT),
    C_BLOCK_AT("blockAt", Arm.CORE, Family.POINT),
    C_MATERIAL_AT("materialAt", Arm.CORE, Family.POINT),
    C_WATER_AT("waterAt", Arm.CORE, Family.POINT),
    C_IS_SUBMERGED("isSubmerged", Arm.CORE, Family.POINT),
    C_BIOME("biome", Arm.CORE, Family.BIOME),
    C_HEIGHTMAP("heightmap().topY", Arm.CORE, Family.LAYER),
    C_COLUMN("column()", Arm.CORE, Family.LAYER),
    C_OCCUPANCY("occupancy()", Arm.CORE, Family.LAYER),
    C_BLOCK_INDEX("blockIndex()", Arm.CORE, Family.LAYER),
    C_RAW_VIEW("raw() + section view", Arm.CORE, Family.RAW),
    C_MATCHES_AT("matchesAt", Arm.CORE, Family.FILTER),
    C_HAS_BLOCK("hasBlock", Arm.CORE, Family.FILTER),
    C_COUNT_BLOCKS("countBlocks", Arm.CORE, Family.FILTER),
    C_FIND_BLOCKS("findBlocks", Arm.CORE, Family.FILTER),
    C_VISIT_BLOCKS("visitBlocks", Arm.CORE, Family.FILTER),
    C_SUMMARIZE_BOX("spatial().summarizeBox", Arm.CORE, Family.SPATIAL),
    C_SUMMARIZE_SPHERE("spatial().summarizeSphere", Arm.CORE, Family.SPATIAL),
    C_VISIT_BOX("spatial().visitBox", Arm.CORE, Family.SPATIAL),
    C_VISIT_SPHERE("spatial().visitSphere", Arm.CORE, Family.SPATIAL),
    C_VISIT_COLUMN("spatial().visitColumn", Arm.CORE, Family.SPATIAL),
    C_SAMPLE_GRID("spatial().sampleGrid", Arm.CORE, Family.SPATIAL),
    C_LIGHT_AT("light().lightAt", Arm.CORE, Family.LIGHT),
    C_WORLD_SNAPSHOT("world().snapshot", Arm.CORE, Family.WORLD),
    C_FIND_BY_TAG("findBlocksByTag", Arm.CORE, Family.LOOKUP),
    C_FIND_BY_NAME("findBlocksByName", Arm.CORE, Family.LOOKUP),
    C_FIND_BY_STATE_ID("findBlocksByStateId", Arm.CORE, Family.LOOKUP),
    C_COUNT_BY_TAG("countBlocksByTag", Arm.CORE, Family.LOOKUP),
    C_COUNT_BY_NAME("countBlocksByName", Arm.CORE, Family.LOOKUP),
    C_COUNT_BY_STATE_ID("countBlocksByStateId", Arm.CORE, Family.LOOKUP),
    C_HAS_BY_TAG("hasBlockByTag", Arm.CORE, Family.LOOKUP),
    C_HAS_BY_NAME("hasBlockByName", Arm.CORE, Family.LOOKUP),
    C_HAS_BY_STATE_ID("hasBlockByStateId", Arm.CORE, Family.LOOKUP),
    C_HAS_TAG_AT("hasTagAt", Arm.CORE, Family.LOOKUP),
    C_STATE_ID_OF("blocks().stateIdOf", Arm.CORE, Family.LOOKUP),
    C_STATE_IDS_OF_BLOCK("blocks().stateIdsOfBlock", Arm.CORE, Family.LOOKUP),
    C_STATE_IDS_OF_TAG("blocks().stateIdsOfTag", Arm.CORE, Family.LOOKUP),
    C_BLOCK_ID_OF("blocks().blockIdOf", Arm.CORE, Family.LOOKUP),
    C_STATE_INFO("blocks().info", Arm.CORE, Family.LOOKUP),
    C_STATE_HAS_TAG("blocks().hasTag", Arm.CORE, Family.LOOKUP);

    private final String label;
    private final Arm arm;
    private final Family family;

    CallKind(String label, Arm arm, Family family) {
        this.label = label;
        this.arm = arm;
        this.family = family;
    }

    public String label() {
        return label;
    }

    public Arm arm() {
        return arm;
    }

    public Family family() {
        return family;
    }

    public String csvId() {
        String rest = name().substring(2).toLowerCase(java.util.Locale.ROOT);
        return (arm == Arm.CORE ? "core_" : "minecraft_") + rest;
    }
}
