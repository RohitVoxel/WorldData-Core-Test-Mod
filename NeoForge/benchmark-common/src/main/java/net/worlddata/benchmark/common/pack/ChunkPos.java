package net.worlddata.benchmark.common.pack;

public final class ChunkPos {

    private static final long REGION_FLIP = 1L << 62;

    private ChunkPos() {}

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int x(long packed) {
        return (int) (packed >> 32);
    }

    public static int z(long packed) {
        return (int) packed;
    }

    public static long toRegionToken(long chunkToken) {
        return chunkToken ^ REGION_FLIP;
    }

    public static boolean isRegionToken(long token) {
        long top = token >> 61;
        return top != 0L && top != -1L;
    }

    public static long fromRegionToken(long regionToken) {
        return regionToken ^ REGION_FLIP;
    }
}
