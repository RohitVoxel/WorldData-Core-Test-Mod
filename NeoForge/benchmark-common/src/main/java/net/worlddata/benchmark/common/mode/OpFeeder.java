package net.worlddata.benchmark.common.mode;

import net.worlddata.benchmark.common.pack.BatchProfile;
import net.worlddata.benchmark.common.pack.HotspotRegistry;
import net.worlddata.benchmark.common.pack.IssuePlan;
import net.worlddata.benchmark.common.pack.OpKind;
import net.worlddata.benchmark.common.pack.Outcome;
import net.worlddata.benchmark.common.pack.PackConfig;
import net.worlddata.benchmark.common.pack.PlannedIssue;
import net.worlddata.benchmark.common.pack.ReadAction;
import net.worlddata.benchmark.common.pack.WorldReader;
import net.worlddata.benchmark.common.pack.YRange;

public final class OpFeeder {

    private static final long REROLL_STRIDE = 1_000_003L;
    private static final int REROLL_LIMIT = 16;

    private final long seed;
    private final HotspotRegistry hotspots;
    private final YRange yRange;
    private final WorldReader reader;
    private final PlannedIssue plan = new PlannedIssue();
    private BatchProfile profile;
    private int profileNo = -1;
    private long checksum;
    private ReadAction lastAction = ReadAction.STATE_ID;
    private int lastChunkX;
    private int lastChunkZ;

    public OpFeeder(long seed, HotspotRegistry hotspots, YRange yRange, WorldReader reader) {
        this.seed = seed;
        this.hotspots = hotspots;
        this.yRange = yRange;
        this.reader = reader;
    }

    public Outcome issue(long index) {
        int batch = (int) (index / ModeConfig.OPS_PER_PROFILE);
        int within = (int) (index % ModeConfig.OPS_PER_PROFILE);
        if (batch != profileNo) {
            profile = BatchProfile.of(seed, batch);
            profileNo = batch;
        }
        IssuePlan.fill(plan, seed, index, profile, within, yRange);
        int tries = 0;
        while (plan.kind == OpKind.LIGHT && tries < REROLL_LIMIT) {
            tries++;
            IssuePlan.fill(plan, seed, index + REROLL_STRIDE * tries, profile, within, yRange);
        }
        if (plan.kind == OpKind.LIGHT) plan.action = ReadAction.STATE_ID;
        int slot = plan.slot % PackConfig.HOTSPOT_CHUNKS;
        int cx = hotspots.chunkX(slot);
        int cz = hotspots.chunkZ(slot);
        lastAction = plan.action;
        lastChunkX = cx;
        lastChunkZ = cz;
        Outcome outcome = reader.read(plan.action, (cx << 4) + plan.localX, plan.y, (cz << 4) + plan.localZ, plan.extra);
        if (outcome == Outcome.PASS) checksum += reader.lastDigest();
        return outcome;
    }

    public ReadAction action() {
        return lastAction;
    }

    public long token() {
        return reader.chunkToken(lastChunkX, lastChunkZ);
    }

    public long checksum() {
        return checksum;
    }
}
