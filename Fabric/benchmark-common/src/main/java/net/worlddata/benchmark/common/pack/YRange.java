package net.worlddata.benchmark.common.pack;

public record YRange(int readMin, int readSpan, int arenaY) {

    public static final YRange STANDARD = of(-64, 320);

    public static YRange of(int minBuildHeight, int maxBuildHeight) {
        int height = Math.max(1, maxBuildHeight - minBuildHeight);
        int layers = PackConfig.ARENA_LAYERS;
        int arenaY = height > layers ? clamp(PackConfig.ARENA_Y, minBuildHeight, maxBuildHeight - layers) : minBuildHeight;
        int room = arenaY - minBuildHeight;
        if (room < 1) return new YRange(minBuildHeight, Math.min(PackConfig.Y_SPAN, height), arenaY);
        int span = Math.min(PackConfig.Y_SPAN, room);
        return new YRange(clamp(PackConfig.Y_MIN, minBuildHeight, arenaY - span), span, arenaY);
    }

    private static int clamp(int value, int low, int high) {
        if (high < low) return low;
        return Math.max(low, Math.min(high, value));
    }
}
