package net.worlddata.benchmark.common.pack;

public final class WavePlan {

    private static final long WAVE_SALT = 0x57415645L;
    private static final long ANCHOR_SALT = 0x414E4348L;

    private final int index;
    private final int anchor;
    private final int chunks;
    private final int width;
    private final int regionRows;
    private final int[] callMinX;
    private final int[] callMinZ;
    private final int[] callMaxX;
    private final int[] callMaxZ;
    private final int[] singleX;
    private final int[] singleZ;
    private final int regionMinX;
    private final int regionMinZ;

    private WavePlan(int index, int anchor, int chunks, int width, int regionRows, int regionMinX, int regionMinZ,
                     int[] callMinX, int[] callMinZ, int[] callMaxX, int[] callMaxZ, int[] singleX, int[] singleZ) {
        this.index = index;
        this.anchor = anchor;
        this.chunks = chunks;
        this.width = width;
        this.regionRows = regionRows;
        this.regionMinX = regionMinX;
        this.regionMinZ = regionMinZ;
        this.callMinX = callMinX;
        this.callMinZ = callMinZ;
        this.callMaxX = callMaxX;
        this.callMaxZ = callMaxZ;
        this.singleX = singleX;
        this.singleZ = singleZ;
    }

    public static int anchorOf(long seed, int index) {
        return SeedMix.bounded(SeedMix.at(seed ^ WAVE_SALT, index), 5, PackConfig.WAVE_ANCHORS);
    }

    public static WavePlan of(long seed, int index, int baseChunkX, int baseChunkZ) {
        long h = SeedMix.at(seed ^ WAVE_SALT, index);
        int chunks = PackConfig.WAVE_CHUNKS_MIN + SeedMix.bounded(h, 11, PackConfig.WAVE_CHUNKS_MAX - PackConfig.WAVE_CHUNKS_MIN + 1);
        int anchor = anchorOf(seed, index);
        int side = PackConfig.WAVE_POOL_SIDE;
        int width = 1 + SeedMix.bounded(h, 21, Math.min(PackConfig.WAVE_MAX_WIDTH, chunks));
        width = Math.max(width, (chunks + side - 1) / side);
        width = Math.min(width, chunks);
        int fullRows = chunks / width;
        int rows = (chunks + width - 1) / width;
        long a = SeedMix.at(seed ^ ANCHOR_SALT, anchor);
        int ax = SeedMix.bounded(a, 8, side) + SeedMix.bounded(h, 33, 4);
        int az = SeedMix.bounded(a, 24, side) + SeedMix.bounded(h, 38, 4);
        ax = Math.max(0, Math.min(side - width, ax));
        az = Math.max(0, Math.min(side - rows, az));
        int originX = baseChunkX + PackConfig.WAVE_POOL_OFFSET;
        int originZ = baseChunkZ + PackConfig.WAVE_POOL_OFFSET;
        int rowsPerCall = Math.max(1, PackConfig.WAVE_CALL_MAX_CHUNKS / width);
        int bands = (fullRows + rowsPerCall - 1) / rowsPerCall;
        int[] minX = new int[bands];
        int[] minZ = new int[bands];
        int[] maxX = new int[bands];
        int[] maxZ = new int[bands];
        for (int b = 0; b < bands; b++) {
            int firstRow = b * rowsPerCall;
            int lastRow = Math.min(fullRows, firstRow + rowsPerCall) - 1;
            minX[b] = originX + ax;
            maxX[b] = originX + ax + width - 1;
            minZ[b] = originZ + az + firstRow;
            maxZ[b] = originZ + az + lastRow;
        }
        int remainder = chunks - fullRows * width;
        int[] sx = new int[remainder];
        int[] sz = new int[remainder];
        for (int i = 0; i < remainder; i++) {
            sx[i] = originX + ax + i;
            sz[i] = originZ + az + fullRows;
        }
        return new WavePlan(index, anchor, chunks, width, fullRows, originX + ax, originZ + az, minX, minZ, maxX, maxZ, sx, sz);
    }

    public int index() {
        return index;
    }

    public int anchor() {
        return anchor;
    }

    public int chunks() {
        return chunks;
    }

    public int width() {
        return width;
    }

    public int regionCalls() {
        return callMinX.length;
    }

    public int regionMinX(int call) {
        return callMinX[call];
    }

    public int regionMinZ(int call) {
        return callMinZ[call];
    }

    public int regionMaxX(int call) {
        return callMaxX[call];
    }

    public int regionMaxZ(int call) {
        return callMaxZ[call];
    }

    public int singles() {
        return singleX.length;
    }

    public int singleX(int i) {
        return singleX[i];
    }

    public int singleZ(int i) {
        return singleZ[i];
    }

    public int calls() {
        return callMinX.length + singleX.length;
    }

    public int chunkX(int slot) {
        int s = Math.floorMod(slot, chunks);
        int inRegion = regionRows * width;
        return s < inRegion ? regionMinX + s % width : singleX[s - inRegion];
    }

    public int chunkZ(int slot) {
        int s = Math.floorMod(slot, chunks);
        int inRegion = regionRows * width;
        return s < inRegion ? regionMinZ + s / width : singleZ[s - inRegion];
    }

    public static int coldProbeChunkX(int baseChunkX, long hash) {
        return baseChunkX + 160 + SeedMix.bounded(hash, 12, 64) * (SeedMix.bounded(hash, 4, 2) == 0 ? 1 : -1);
    }

    public static int coldProbeChunkZ(int baseChunkZ, long hash) {
        return baseChunkZ + 160 + SeedMix.bounded(hash, 24, 64) * (SeedMix.bounded(hash, 6, 2) == 0 ? 1 : -1);
    }
}
