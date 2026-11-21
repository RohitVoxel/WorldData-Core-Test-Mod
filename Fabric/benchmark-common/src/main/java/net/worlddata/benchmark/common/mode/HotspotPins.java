package net.worlddata.benchmark.common.mode;

import net.worlddata.benchmark.common.pack.ChunkLoader;
import net.worlddata.benchmark.common.pack.HotspotRegistry;
import net.worlddata.benchmark.common.pack.Outcome;
import net.worlddata.benchmark.common.pack.PackConfig;

public final class HotspotPins {

    private HotspotPins() {}

    public static void pin(ChunkLoader loader, HotspotRegistry hotspots) {
        int side = PackConfig.HOTSPOT_CLUSTER_SIDE;
        for (int c = 0; c < hotspots.clusterCount(); c++) {
            int minX = hotspots.clusterMinChunkX(c);
            int minZ = hotspots.clusterMinChunkZ(c);
            loader.pin(minX, minZ, minX + side - 1, minZ + side - 1);
        }
    }

    public static int ready(ChunkLoader loader, HotspotRegistry hotspots) {
        int side = PackConfig.HOTSPOT_CLUSTER_SIDE;
        int ready = 0;
        for (int c = 0; c < hotspots.clusterCount(); c++) {
            int minX = hotspots.clusterMinChunkX(c);
            int minZ = hotspots.clusterMinChunkZ(c);
            if (loader.status(minX, minZ, minX + side - 1, minZ + side - 1) == Outcome.PASS) ready++;
        }
        return ready;
    }
}
