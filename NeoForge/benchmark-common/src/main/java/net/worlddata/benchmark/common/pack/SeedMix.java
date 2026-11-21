package net.worlddata.benchmark.common.pack;

public final class SeedMix {

    private static final long GOLDEN = 0x9E3779B97F4A7C15L;

    private SeedMix() {}

    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static long at(long seed, long index) {
        return mix(seed + (index + 1L) * GOLDEN);
    }

    public static int percent(long hash) {
        return (int) ((hash >>> 33) % 100L);
    }

    public static int basisPoint(long hash) {
        return (int) ((hash >>> 33) % 10_000L);
    }

    public static int bounded(long hash, int shift, int bound) {
        return (int) (((hash >>> shift) & 0x7FFFFFFFL) % bound);
    }
}
