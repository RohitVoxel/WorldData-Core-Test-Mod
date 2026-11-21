package net.worlddata.benchmark.common.pack;

public enum Family {
    CHUNK_ENTRY("Chunk entry"),
    TICKETS("Minecraft force-load tickets"),
    POINT("Point reads"),
    BIOME("Biome"),
    LAYER("Derived layers"),
    RAW("Raw views"),
    FILTER("Filter and scan"),
    SPATIAL("Spatial"),
    LOOKUP("Block lookup by id, name and tag"),
    LIGHT("Light"),
    WORLD("World snapshot"),
    WRITE("Vanilla writes");

    private final String label;

    Family(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
