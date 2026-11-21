package net.worlddata.benchmark.common.pack;

public final class HotspotRegistry {

    private static final int RING_RADIUS_CHUNKS = 6;

    private final int[] clusterMinX = new int[PackConfig.HOTSPOT_CLUSTERS];
    private final int[] clusterMinZ = new int[PackConfig.HOTSPOT_CLUSTERS];

    public HotspotRegistry(int baseChunkX, int baseChunkZ) {
        for (int i = 0; i < PackConfig.HOTSPOT_CLUSTERS; i++) {
            double angle = i * (2.0 * Math.PI / PackConfig.HOTSPOT_CLUSTERS);
            clusterMinX[i] = baseChunkX + (int) Math.round(Math.cos(angle) * RING_RADIUS_CHUNKS);
            clusterMinZ[i] = baseChunkZ + (int) Math.round(Math.sin(angle) * RING_RADIUS_CHUNKS);
        }
    }

    public int clusterCount() {
        return PackConfig.HOTSPOT_CLUSTERS;
    }

    public int clusterMinChunkX(int cluster) {
        return clusterMinX[cluster];
    }

    public int clusterMinChunkZ(int cluster) {
        return clusterMinZ[cluster];
    }

    public int chunkX(int slot) {
        int cluster = slot / (PackConfig.HOTSPOT_CLUSTER_SIDE * PackConfig.HOTSPOT_CLUSTER_SIDE);
        int within = slot % (PackConfig.HOTSPOT_CLUSTER_SIDE * PackConfig.HOTSPOT_CLUSTER_SIDE);
        return clusterMinX[cluster] + within % PackConfig.HOTSPOT_CLUSTER_SIDE;
    }

    public int chunkZ(int slot) {
        int cluster = slot / (PackConfig.HOTSPOT_CLUSTER_SIDE * PackConfig.HOTSPOT_CLUSTER_SIDE);
        int within = slot % (PackConfig.HOTSPOT_CLUSTER_SIDE * PackConfig.HOTSPOT_CLUSTER_SIDE);
        return clusterMinZ[cluster] + within / PackConfig.HOTSPOT_CLUSTER_SIDE;
    }

    private static final ReadAction[] LAYERS = {ReadAction.HEIGHTMAP, ReadAction.COLUMN, ReadAction.OCCUPANCY, ReadAction.BLOCK_INDEX};

    public ReadAction focusedLayer(ReadAction planned, int slot, long completedOps, long totalOps) {
        int index = 0;
        for (int i = 0; i < LAYERS.length; i++) if (LAYERS[i] == planned) index = i;
        long step = Math.max(1_000L, totalOps / 50L);
        int shift = (int) ((completedOps / step + slot) % LAYERS.length);
        return LAYERS[(index + shift) % LAYERS.length];
    }
}
