package net.worlddata.benchmark.common.mode;

import net.worlddata.benchmark.common.pack.HotspotRegistry;
import net.worlddata.benchmark.common.pack.PackConfig;
import net.worlddata.benchmark.common.pack.SeedMix;
import net.worlddata.benchmark.common.pack.ThrashPipeline;
import net.worlddata.benchmark.common.pack.ThrashProbe;
import net.worlddata.benchmark.common.pack.WorldReader;
import net.worlddata.benchmark.common.pack.YRange;

final class ModeThrash {

    private final ThrashPipeline pipeline;
    private final WorldReader primary;
    private final HotspotRegistry hotspots;
    private final YRange yRange;
    private final long seed;
    private final int workers;
    private final int[] x = new int[PackConfig.PROBE_CELLS];
    private final int[] y = new int[PackConfig.PROBE_CELLS];
    private final int[] z = new int[PackConfig.PROBE_CELLS];
    private int bursts;
    private int failed;
    private int skipped;
    private double ratioSum;
    private long reads;

    ModeThrash(int workers, WorldReader primary, HotspotRegistry hotspots, YRange yRange, long seed) {
        this.workers = workers;
        this.pipeline = new ThrashPipeline(workers);
        this.primary = primary;
        this.hotspots = hotspots;
        this.yRange = yRange;
        this.seed = seed;
    }

    void warmUp() {
        pipeline.warmUp();
    }

    void burstIfDue(long serverTick) {
        if (serverTick % ModeConfig.THRASH_EVERY_TICKS == 0L) burst();
    }

    void shutdown() {
        pipeline.shutdown();
    }

    private void burst() {
        int number = bursts + skipped + failed;
        int slot = number % PackConfig.HOTSPOT_CHUNKS;
        int cx = hotspots.chunkX(slot);
        int cz = hotspots.chunkZ(slot);
        ThrashProbe probe = primary.openThrash(cx, cz);
        if (probe == null) {
            skipped++;
            return;
        }
        for (int i = 0; i < PackConfig.PROBE_CELLS; i++) {
            long h = SeedMix.at(seed ^ 0x5448L, (long) number * PackConfig.PROBE_CELLS + i);
            x[i] = (cx << 4) + SeedMix.bounded(h, 8, 16);
            z[i] = (cz << 4) + SeedMix.bounded(h, 16, 16);
            y[i] = yRange.readMin() + SeedMix.bounded(h, 24, yRange.readSpan());
        }
        ThrashPipeline.Burst burst = pipeline.burst(probe, x, y, z, PackConfig.PROBE_READS_MIN * 2);
        if (!burst.completed()) {
            failed++;
            return;
        }
        bursts++;
        ratioSum += burst.ratio();
        reads += burst.totalReads();
    }

    int workers() {
        return workers;
    }

    int bursts() {
        return bursts;
    }

    int failed() {
        return failed;
    }

    int skipped() {
        return skipped;
    }

    double meanRatio() {
        return bursts == 0 ? 0.0 : ratioSum / bursts;
    }

    long reads() {
        return reads;
    }
}
