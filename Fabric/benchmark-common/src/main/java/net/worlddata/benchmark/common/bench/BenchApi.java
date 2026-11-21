package net.worlddata.benchmark.common.bench;

import java.util.ArrayList;
import java.util.List;

public enum BenchApi {
    STATE_ID_AT("stateIdAt", 100_000, 1_000_000, false, 50_000, false),
    BLOCK_AT("blockAt", 100_000, 1_000_000, false, 50_000, false),
    MATERIAL_AT("materialAt", 100_000, 1_000_000, false, 50_000, false),
    WATER_AT("waterAt", 100_000, 1_000_000, false, 50_000, true),
    IS_PRESENT("isPresent", 100_000, 1_000_000, false, 50_000, true),
    MATCHES_AT("matchesAt", 100_000, 1_000_000, false, 50_000, true),
    HAS_BLOCK("hasBlock", 10_000, 200_000, true, 16, true),
    COUNT_BLOCKS("countBlocks", 1_000, 50_000, true, 8, true);

    private final String commandName;
    private final int defaultCount;
    private final int maxCount;
    private final boolean scan;
    private final int warmup;
    private final boolean comparable;

    BenchApi(String commandName, int defaultCount, int maxCount, boolean scan, int warmup, boolean comparable) {
        this.commandName = commandName;
        this.defaultCount = defaultCount;
        this.maxCount = maxCount;
        this.scan = scan;
        this.warmup = warmup;
        this.comparable = comparable;
    }

    public String commandName() {
        return commandName;
    }

    public int defaultCount() {
        return defaultCount;
    }

    public int maxCount() {
        return maxCount;
    }

    public boolean scan() {
        return scan;
    }

    public int warmup() {
        return warmup;
    }

    public boolean comparable() {
        return comparable;
    }

    public int blockSize() {
        return scan ? 16 : 1024;
    }

    public static List<String> names() {
        List<String> names = new ArrayList<>();
        for (BenchApi api : values()) names.add(api.commandName);
        return names;
    }

    public static BenchApi parse(String text) {
        String wanted = text == null ? "" : text.trim();
        for (BenchApi api : values()) {
            if (api.commandName.equalsIgnoreCase(wanted) || api.name().equalsIgnoreCase(wanted)) return api;
        }
        throw new IllegalArgumentException("Unknown API '" + wanted + "'. Choose one of: " + String.join(", ", names()));
    }
}
