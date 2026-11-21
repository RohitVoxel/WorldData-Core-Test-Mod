package net.worlddata.benchmark.common.pack;

public final class BlockIds {

    public static final String AIR = "minecraft:air";
    public static final String NO_DATA = "";

    private static final String CAVE_AIR = "minecraft:cave_air";
    private static final String VOID_AIR = "minecraft:void_air";

    private BlockIds() {}

    public static boolean isAir(String id) {
        return AIR.equals(id) || CAVE_AIR.equals(id) || VOID_AIR.equals(id);
    }

    public static String canonical(String id) {
        if (id == null) return NO_DATA;
        return isAir(id) ? AIR : id;
    }

    public static boolean readable(String id) {
        return id != null && !id.isEmpty();
    }

    public static boolean same(String minecraft, String core) {
        return canonical(minecraft).equals(canonical(core));
    }
}
