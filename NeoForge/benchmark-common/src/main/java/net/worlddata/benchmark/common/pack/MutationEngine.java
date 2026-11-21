package net.worlddata.benchmark.common.pack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public final class MutationEngine {

    public enum Trace {
        QUARRY_PLANE,
        REDSTONE_FLIP,
        CROSS_SEAM_TRAVERSAL,
        FLUID_CASCADE
    }

    public record Audit(int sampled, int compared, int mismatches, int unreadable, List<String> examples) {
        public double driftPercent() {
            return compared == 0 ? 0.0 : mismatches * 100.0 / compared;
        }
    }

    private static final int TRACE_RUN = 512;
    private static final int ARENA_CYCLE = 2048;
    private static final int LAG_BUCKETS = 6;
    private static final int AUDIT_EXAMPLES = 8;

    private final WorldWriter writer;
    private final WorldReader observer;
    private final HotspotRegistry hotspots;
    private final int arenaY;
    private final long[] traceWrites = new long[Trace.values().length];
    private final long[] lagCounts = new long[LAG_BUCKETS];
    private final int[] probeX = new int[PackConfig.LAG_PROBE_SLOTS];
    private final int[] probeY = new int[PackConfig.LAG_PROBE_SLOTS];
    private final int[] probeZ = new int[PackConfig.LAG_PROBE_SLOTS];
    private final long[] probeTick = new long[PackConfig.LAG_PROBE_SLOTS];
    private final boolean[] probeActive = new boolean[PackConfig.LAG_PROBE_SLOTS];
    private final long[] written = new long[PackConfig.AUDIT_SAMPLES];
    private int writtenCount;
    private int writtenCursor;
    private long writes;
    private long writeFailures;
    private long lagResolved;
    private long lagTimeouts;
    private int lagMax;
    private Audit lastAudit = new Audit(0, 0, 0, 0, List.of());

    public MutationEngine(WorldWriter writer, WorldReader observer, HotspotRegistry hotspots) {
        this(writer, observer, hotspots, PackConfig.ARENA_Y);
    }

    public MutationEngine(WorldWriter writer, WorldReader observer, HotspotRegistry hotspots, int arenaY) {
        this.writer = writer;
        this.observer = observer;
        this.hotspots = hotspots;
        this.arenaY = arenaY;
    }

    public Trace traceOf(long writeIndex) {
        return Trace.values()[(int) ((writeIndex / TRACE_RUN) % Trace.values().length)];
    }

    public Outcome step(long writeIndex, long tick) {
        Trace trace = traceOf(writeIndex);
        int s = (int) (writeIndex % TRACE_RUN);
        int cycle = (int) ((writeIndex / ARENA_CYCLE) % 4);
        int baseX = hotspots.chunkX(cycle) << 4;
        int baseZ = hotspots.chunkZ(cycle) << 4;
        int x;
        int y;
        int z;
        BlockCode code;
        switch (trace) {
            case QUARRY_PLANE -> {
                int idx = s % 256;
                x = baseX + idx % 16;
                z = baseZ + idx / 16;
                y = arenaY + (int) ((writeIndex / ARENA_CYCLE) % PackConfig.ARENA_LAYERS);
                code = s < 256 ? BlockCode.STONE : BlockCode.AIR;
            }
            case REDSTONE_FLIP -> {
                int cell = s % 4;
                x = baseX + 1 + cell * 3;
                z = baseZ + 5;
                y = arenaY + 3;
                code = (s / 4) % 2 == 0 ? BlockCode.LAMP_ON : BlockCode.LAMP_OFF;
            }
            case CROSS_SEAM_TRAVERSAL -> {
                boolean east = cycle % 2 == 0;
                int across = s % 2;
                x = baseX + (east ? 15 + across : -1 + across);
                z = baseZ + (s / 2) % 16;
                y = arenaY + 5;
                code = (s / 32) % 2 == 0 ? BlockCode.STONE : BlockCode.GLASS;
            }
            default -> {
                if (s < 9) {
                    x = baseX + 7 + s % 3;
                    z = baseZ + 7 + s / 3;
                    y = arenaY + 6;
                    code = BlockCode.GLASS;
                } else {
                    x = baseX + 8;
                    z = baseZ + 8;
                    y = arenaY + 7;
                    int phase = (s - 9) % 3;
                    code = phase == 0 ? BlockCode.WATER_L6 : phase == 1 ? BlockCode.WATER_L7 : BlockCode.AIR;
                }
            }
        }
        Outcome outcome = writer.write(x, y, z, code);
        writes++;
        traceWrites[trace.ordinal()]++;
        if (outcome != Outcome.PASS) {
            writeFailures++;
            return outcome;
        }
        remember(x, y, z);
        if (observer != null && observer.arm() == Arm.CORE && writeIndex % 8 == 0) startProbe(x, y, z, tick);
        return outcome;
    }

    private void remember(int x, int y, int z) {
        written[writtenCursor] = pack(x, y, z);
        writtenCursor = (writtenCursor + 1) % written.length;
        if (writtenCount < written.length) writtenCount++;
    }

    private void startProbe(int x, int y, int z, long tick) {
        for (int i = 0; i < probeActive.length; i++) {
            if (!probeActive[i]) {
                probeActive[i] = true;
                probeX[i] = x;
                probeY[i] = y;
                probeZ[i] = z;
                probeTick[i] = tick;
                return;
            }
        }
    }

    public void tickEnd(long tick) {
        if (observer == null || writer == null) return;
        for (int i = 0; i < probeActive.length; i++) {
            if (!probeActive[i]) continue;
            int lag = (int) (tick - probeTick[i]);
            String vanilla = writer.blockId(probeX[i], probeY[i], probeZ[i]);
            String seen = observer.blockIdAt(probeX[i], probeY[i], probeZ[i]);
            if (BlockIds.readable(seen) && BlockIds.same(vanilla, seen)) {
                lagCounts[Math.min(lag, LAG_BUCKETS - 1)]++;
                lagResolved++;
                lagMax = Math.max(lagMax, lag);
                probeActive[i] = false;
            } else if (lag >= PackConfig.LAG_TIMEOUT_TICKS) {
                lagTimeouts++;
                probeActive[i] = false;
            }
        }
    }

    public Audit audit() {
        if (observer == null || writer == null || observer.arm() != Arm.CORE) {
            lastAudit = new Audit(0, 0, 0, 0, List.of());
            return lastAudit;
        }
        HashSet<Long> seen = new HashSet<>();
        List<String> examples = new ArrayList<>();
        int sampled = 0;
        int compared = 0;
        int mismatches = 0;
        int unreadable = 0;
        for (int i = 0; i < writtenCount; i++) {
            long packed = written[i];
            if (!seen.add(packed)) continue;
            int x = unpackX(packed);
            int y = unpackY(packed);
            int z = unpackZ(packed);
            sampled++;
            String minecraft = writer.blockId(x, y, z);
            String core = observer.blockIdAt(x, y, z);
            if (!BlockIds.readable(minecraft) || !BlockIds.readable(core)) {
                unreadable++;
                continue;
            }
            compared++;
            if (!BlockIds.same(minecraft, core)) {
                mismatches++;
                if (examples.size() < AUDIT_EXAMPLES) examples.add(x + " " + y + " " + z + " minecraft=" + BlockIds.canonical(minecraft) + " core=" + BlockIds.canonical(core));
            }
        }
        lastAudit = new Audit(sampled, compared, mismatches, unreadable, List.copyOf(examples));
        return lastAudit;
    }

    public Audit lastAudit() {
        return lastAudit;
    }

    public long writes() {
        return writes;
    }

    public long writeFailures() {
        return writeFailures;
    }

    public long traceWrites(Trace trace) {
        return traceWrites[trace.ordinal()];
    }

    public long[] lagCounts() {
        return Arrays.copyOf(lagCounts, lagCounts.length);
    }

    public long lagResolved() {
        return lagResolved;
    }

    public long lagTimeouts() {
        return lagTimeouts;
    }

    public int lagMaxTicks() {
        return lagMax;
    }

    public int activeProbes() {
        int n = 0;
        for (boolean active : probeActive) if (active) n++;
        return n;
    }

    public int restore() {
        return writer == null ? 0 : writer.restoreAll();
    }

    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    static int unpackX(long packed) {
        return (int) (packed >> 38) << 6 >> 6;
    }

    static int unpackZ(long packed) {
        return (int) ((packed >> 12) & 0x3FFFFFF) << 6 >> 6;
    }

    static int unpackY(long packed) {
        return (int) (packed & 0xFFF) << 20 >> 20;
    }
}
