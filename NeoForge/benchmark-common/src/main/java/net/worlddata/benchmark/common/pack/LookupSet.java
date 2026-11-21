package net.worlddata.benchmark.common.pack;

public final class LookupSet {

    public static final String[] BLOCKS = {
            "minecraft:stone",
            "minecraft:dirt",
            "minecraft:deepslate",
            "minecraft:oak_log",
            "minecraft:grass_block",
            "minecraft:netherrack",
            "minecraft:end_stone",
            "minecraft:sand"
    };

    public static final String[] TAGS = {
            "minecraft:leaves",
            "minecraft:logs",
            "minecraft:base_stone_overworld",
            "minecraft:sand",
            "minecraft:dirt",
            "minecraft:planks"
    };

    public static final String[] STATE_TAGS = {"LEAVES", "LOG", "PLANKS", "SAND", "DIRT", "ICE"};

    public static final String[] STATE_TAG_IDS = {
            "minecraft:leaves",
            "minecraft:logs",
            "minecraft:planks",
            "minecraft:sand",
            "minecraft:dirt",
            "minecraft:ice"
    };

    private LookupSet() {}

    public static int block(int extra) {
        return Math.floorMod(extra, BLOCKS.length);
    }

    public static int tag(int extra) {
        return Math.floorMod(extra >>> 3, TAGS.length);
    }

    public static int stateTag(int extra) {
        return Math.floorMod(extra >>> 5, STATE_TAGS.length);
    }
}
