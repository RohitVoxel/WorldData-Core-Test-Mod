package net.worlddata.benchmark.common.pack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

public final class WaveRunner {

    private static final class Group {
        final int minX;
        final int minZ;
        final int maxX;
        final int maxZ;
        boolean submitted;
        boolean terminal;
        boolean released;
        long startNanos;
        long submitTick = -1L;

        Group(int minX, int minZ, int maxX, int maxZ) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
        }

        int chunks() {
            return (maxX - minX + 1) * (maxZ - minZ + 1);
        }
    }

    private static final class Active {
        final int index;
        final WavePlan plan;
        final boolean revisit;
        final Group[] groups;
        int submitted;
        int terminal;
        int released;
        long doneTick = -1L;

        Active(int index, WavePlan plan, boolean revisit) {
            this.index = index;
            this.plan = plan;
            this.revisit = revisit;
            this.groups = new Group[plan.calls()];
            int g = 0;
            for (int i = 0; i < plan.regionCalls(); i++) {
                groups[g++] = new Group(plan.regionMinX(i), plan.regionMinZ(i), plan.regionMaxX(i), plan.regionMaxZ(i));
            }
            for (int i = 0; i < plan.singles(); i++) {
                groups[g++] = new Group(plan.singleX(i), plan.singleZ(i), plan.singleX(i), plan.singleZ(i));
            }
        }
    }

    private final WaveSchedule schedule;
    private final ChunkLoader loader;
    private final LongSupplier clock;
    private final List<Active> active = new ArrayList<>();
    private final LatencyHistogram coldWait = new LatencyHistogram();
    private final LatencyHistogram restoreWait = new LatencyHistogram();
    private long deadlineNanos = PackConfig.SIDE_LANE_DEADLINE_NANOS;
    private int next;
    private WavePlan latest;
    private int started;
    private int finished;
    private int released;
    private int revisits;
    private int chunksMin = Integer.MAX_VALUE;
    private int chunksMax;
    private long chunksTotal;
    private long requestCalls;
    private long releaseCalls;
    private long pinCalls;
    private long groupsLoaded;
    private long groupsFailed;
    private long groupsTimedOut;
    private long groupsBlocked;
    private int activePeak;
    private int abandoned;

    public WaveRunner(WaveSchedule schedule, ChunkLoader loader, LongSupplier clock) {
        this.schedule = schedule;
        this.loader = loader;
        this.clock = clock;
    }

    void deadlineNanos(long nanos) {
        this.deadlineNanos = nanos;
    }

    public void pinHotspots(HotspotRegistry hotspots) {
        int clusters = hotspots.clusterCount();
        int side = PackConfig.HOTSPOT_CLUSTER_SIDE;
        for (int c = 0; c < clusters - 1; c++) {
            int minX = hotspots.clusterMinChunkX(c);
            int minZ = hotspots.clusterMinChunkZ(c);
            loader.pin(minX, minZ, minX + side - 1, minZ + side - 1);
            pinCalls++;
        }
        int minX = hotspots.clusterMinChunkX(clusters - 1);
        int minZ = hotspots.clusterMinChunkZ(clusters - 1);
        for (int dx = 0; dx < side; dx++) {
            for (int dz = 0; dz < side; dz++) {
                loader.pin(minX + dx, minZ + dz, minX + dx, minZ + dz);
                pinCalls++;
            }
        }
    }

    public void tick(long cursor, long tick, TickBudget budget, boolean allowStart) {
        if (allowStart) {
            while (next < schedule.count() && cursor >= schedule.trigger(next)) start(next++);
        }
        boolean worked = false;
        for (Active a : active) {
            while (a.submitted < a.groups.length) {
                if (worked && budget.exhausted()) break;
                worked = true;
                submit(a, a.groups[a.submitted++], tick);
            }
        }
        boolean polled = false;
        for (Active a : active) {
            for (Group g : a.groups) {
                if (!g.submitted || g.terminal || g.submitTick == tick) continue;
                if (polled && budget.usedAtLeast(PackConfig.WAVE_POLL_SHARE_PERCENT)) break;
                polled = true;
                poll(a, g, tick);
            }
        }
        for (int i = 0; i < active.size(); i++) {
            Active a = active.get(i);
            if (a.doneTick >= 0L && tick - a.doneTick >= PackConfig.WAVE_HOLD_TICKS) {
                for (Group g : a.groups) {
                    if (g.released) continue;
                    if (worked && budget.exhausted()) break;
                    worked = true;
                    release(a, g);
                }
                if (a.released == a.groups.length) {
                    released++;
                    active.remove(i--);
                }
            }
        }
    }

    public void finish() {
        for (Active a : active) {
            for (Group g : a.groups) {
                if (g.submitted && !g.terminal) abandoned++;
                if (g.submitted && !g.released) release(a, g);
            }
            if (a.submitted == a.groups.length) released++;
        }
        active.clear();
        loader.releaseAll();
    }

    private void start(int wave) {
        WavePlan plan = schedule.plan(wave);
        boolean revisit = schedule.revisit(wave);
        Active a = new Active(wave, plan, revisit);
        active.add(a);
        activePeak = Math.max(activePeak, active.size());
        latest = plan;
        started++;
        if (revisit) revisits++;
        chunksMin = Math.min(chunksMin, plan.chunks());
        chunksMax = Math.max(chunksMax, plan.chunks());
        chunksTotal += plan.chunks();
    }

    private void submit(Active a, Group g, long tick) {
        g.submitted = true;
        g.submitTick = tick;
        long before = clock.getAsLong();
        g.startNanos = before;
        Outcome outcome = loader.request(g.minX, g.minZ, g.maxX, g.maxZ);
        requestCalls++;
        if (outcome != Outcome.PENDING) {
            finishGroup(a, g, outcome, clock.getAsLong() - before, tick);
        }
    }

    private void poll(Active a, Group g, long tick) {
        long now = clock.getAsLong();
        Outcome outcome = loader.status(g.minX, g.minZ, g.maxX, g.maxZ);
        if (outcome == Outcome.PENDING && now - g.startNanos > deadlineNanos) outcome = Outcome.TIMEOUT;
        if (outcome != Outcome.PENDING) finishGroup(a, g, outcome, now - g.startNanos, tick);
    }

    private void finishGroup(Active a, Group g, Outcome outcome, long waitNanos, long tick) {
        g.terminal = true;
        a.terminal++;
        switch (outcome) {
            case PASS -> groupsLoaded++;
            case TIMEOUT -> groupsTimedOut++;
            case UNAVAILABLE -> groupsBlocked++;
            default -> groupsFailed++;
        }
        (a.revisit ? restoreWait : coldWait).record(Math.max(0L, waitNanos));
        if (a.terminal == a.groups.length) {
            a.doneTick = tick;
            finished++;
        }
    }

    private void release(Active a, Group g) {
        g.released = true;
        a.released++;
        loader.release(g.minX, g.minZ, g.maxX, g.maxZ);
        releaseCalls++;
    }

    public WavePlan currentPlan() {
        return latest;
    }

    public int planned() {
        return schedule.count();
    }

    public int started() {
        return started;
    }

    public int finished() {
        return finished;
    }

    public int released() {
        return released;
    }

    public int revisits() {
        return revisits;
    }

    public int activeNow() {
        return active.size();
    }

    public int activePeak() {
        return activePeak;
    }

    public int chunksMin() {
        return started == 0 ? 0 : chunksMin;
    }

    public int chunksMax() {
        return chunksMax;
    }

    public double chunksMean() {
        return started == 0 ? 0.0 : chunksTotal / (double) started;
    }

    public long chunksRequested() {
        return chunksTotal;
    }

    public long requestCalls() {
        return requestCalls;
    }

    public long releaseCalls() {
        return releaseCalls;
    }

    public long pinCalls() {
        return pinCalls;
    }

    public long groupsLoaded() {
        return groupsLoaded;
    }

    public long groupsFailed() {
        return groupsFailed;
    }

    public long groupsTimedOut() {
        return groupsTimedOut;
    }

    public long groupsBlocked() {
        return groupsBlocked;
    }

    public int abandoned() {
        return abandoned;
    }

    public boolean allStartedFinished() {
        return finished == started;
    }

    public int heldGroups() {
        int held = 0;
        for (Active a : active) {
            for (Group g : a.groups) if (g.submitted && !g.released) held++;
        }
        return held;
    }

    public LatencyHistogram coldWait() {
        return coldWait;
    }

    public LatencyHistogram restoreWait() {
        return restoreWait;
    }

    public ChunkLoader loader() {
        return loader;
    }
}
