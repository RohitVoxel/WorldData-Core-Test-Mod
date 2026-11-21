package net.worlddata.benchmark.common.pack;

import java.util.HashSet;
import java.util.Set;

public final class WaveSchedule {

    private static final long TRIGGER_SALT = 0x54524947L;

    private final int count;
    private final long total;
    private final boolean fullRun;
    private final long[] triggers;
    private final WavePlan[] plans;
    private final boolean[] revisit;

    public WaveSchedule(long seed, long total, int baseChunkX, int baseChunkZ) {
        this.total = total;
        this.count = PackConfig.waveCount(total);
        this.fullRun = PackConfig.fullWaveRun(total);
        this.triggers = new long[count];
        this.plans = new WavePlan[count];
        this.revisit = new boolean[count];
        long span = Math.max(1L, total * 9L / 10L);
        long slice = Math.max(1L, span / count);
        for (int w = 0; w < count; w++) {
            long jitter = SeedMix.bounded(SeedMix.at(seed ^ TRIGGER_SALT, w), 7, (int) Math.max(1L, Math.min(Integer.MAX_VALUE, slice / 2)));
            triggers[w] = Math.min(total - 1L, (long) w * span / count + jitter);
            plans[w] = WavePlan.of(seed, w, baseChunkX, baseChunkZ);
            for (int u = 0; u <= w - PackConfig.WAVE_REVISIT_GAP; u++) {
                if (plans[u].anchor() == plans[w].anchor()) {
                    revisit[w] = true;
                    break;
                }
            }
        }
    }

    public int count() {
        return count;
    }

    public boolean fullRun() {
        return fullRun;
    }

    public long total() {
        return total;
    }

    public long trigger(int wave) {
        return triggers[wave];
    }

    public WavePlan plan(int wave) {
        return plans[wave];
    }

    public boolean revisit(int wave) {
        return revisit[wave];
    }

    public int uniqueChunksPlanned() {
        Set<Long> seen = new HashSet<>();
        for (WavePlan plan : plans) {
            for (int s = 0; s < plan.chunks(); s++) seen.add(ChunkPos.pack(plan.chunkX(s), plan.chunkZ(s)));
        }
        return seen.size();
    }

    public int minChunks() {
        int min = Integer.MAX_VALUE;
        for (WavePlan plan : plans) min = Math.min(min, plan.chunks());
        return count == 0 ? 0 : min;
    }

    public int maxChunks() {
        int max = 0;
        for (WavePlan plan : plans) max = Math.max(max, plan.chunks());
        return max;
    }
}
