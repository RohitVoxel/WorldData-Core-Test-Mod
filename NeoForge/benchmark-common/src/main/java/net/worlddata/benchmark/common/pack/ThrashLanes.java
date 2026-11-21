package net.worlddata.benchmark.common.pack;

import java.util.Arrays;

public final class ThrashLanes {

    private static final int ACTIONS = ReadAction.values().length;

    private final long[] ops;
    private final long[] sharedOps;
    private final long[] notPassed;
    private final long[] seenDigest = new long[PackConfig.SHARED_CELLS * ACTIONS];
    private final long[] seenTick = new long[PackConfig.SHARED_CELLS * ACTIONS];
    private long overlapReads;
    private long comparisons;
    private long disagreements;

    public ThrashLanes() {
        this(PackConfig.THRASH_WORKERS);
    }

    public ThrashLanes(int workers) {
        this.ops = new long[workers];
        this.sharedOps = new long[workers];
        this.notPassed = new long[workers];
        Arrays.fill(seenTick, -1L);
    }

    public void resetBatch() {
        Arrays.fill(seenTick, -1L);
    }

    public void record(int worker, boolean shared, Outcome outcome) {
        ops[worker]++;
        if (shared) sharedOps[worker]++;
        if (outcome != Outcome.PASS && outcome != Outcome.PENDING) notPassed[worker]++;
    }

    public void overlap(int cell, ReadAction action, long digest, long tick) {
        overlapReads++;
        int index = cell * ACTIONS + action.ordinal();
        if (seenTick[index] == tick) {
            comparisons++;
            if (seenDigest[index] != digest) disagreements++;
        } else {
            seenTick[index] = tick;
            seenDigest[index] = digest;
        }
    }

    public long ops(int worker) {
        return ops[worker];
    }

    public long sharedOps(int worker) {
        return sharedOps[worker];
    }

    public long notPassed(int worker) {
        return notPassed[worker];
    }

    public long totalOps() {
        long sum = 0L;
        for (long v : ops) sum += v;
        return sum;
    }

    public long overlapReads() {
        return overlapReads;
    }

    public long comparisons() {
        return comparisons;
    }

    public long disagreements() {
        return disagreements;
    }

    public int workers() {
        return ops.length;
    }
}
