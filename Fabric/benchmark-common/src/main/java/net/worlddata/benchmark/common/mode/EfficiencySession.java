package net.worlddata.benchmark.common.mode;

import net.worlddata.benchmark.common.pack.Arm;
import net.worlddata.benchmark.common.pack.ChunkLoader;
import net.worlddata.benchmark.common.pack.Family;
import net.worlddata.benchmark.common.pack.HotspotRegistry;
import net.worlddata.benchmark.common.pack.InFlightTable;
import net.worlddata.benchmark.common.pack.Outcome;
import net.worlddata.benchmark.common.pack.ReadAction;
import net.worlddata.benchmark.common.pack.WorldReader;

import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

public final class EfficiencySession implements ModeRun {

    public enum Stop {
        COMPLETED("all measured ticks were run"),
        SAFETY("MSPT mean stayed above the safety level for the hold window"),
        STOPPED("stopped by the author"),
        SERVER_STOPPING("the server was stopping");

        private final String text;

        Stop(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    static final int C_OPS = 0;
    static final int C_PASS = 1;
    static final int C_PENDING = 2;
    static final int C_WINDOW = 3;
    static final int C_HARNESS = 4;
    static final int C_MSPT = 5;
    static final int C_GC = 6;
    static final int COLUMNS = 7;

    private enum Phase {
        NEW,
        WARMUP,
        MEASURING,
        FINISHED
    }

    private final ModeSetup setup;
    private final EfficiencyConfig config;
    private final LongSupplier clock;
    private final WorldReader primary;
    private final ChunkLoader loader;
    private final HotspotRegistry hotspots;
    private final OpFeeder feeder;
    private final InFlightTable inFlight = new InFlightTable();
    private final InFlightTable.Settler settler = this::settle;
    private final Columns ticks = new Columns(COLUMNS);
    private final long[] immediate = new long[Outcome.values().length];
    private final long[] settled = new long[Outcome.values().length];
    private final long[] actionOps = new long[ReadAction.values().length];
    private final long[] actionPass = new long[ReadAction.values().length];
    private final long[] actionNanos = new long[ReadAction.values().length];
    private final long[] safetyRing;
    private final int[] warmPending = new int[ModeConfig.STEADY_TICKS];
    private final ModeThrash thrash;

    private Phase phase = Phase.NEW;
    private Stop stop;
    private ModeReport report;
    private Map<String, String> other = Map.of();
    private boolean stopRequested;
    private long cursor;
    private long startNanos;
    private long endNanos;
    private long tickStart;
    private long lastWindowNanos;
    private long lastOps;
    private long lastPass;
    private long lastPending;
    private long lastHarness;
    private long lastMspt;
    private long pendingIssued;
    private long safetySum;
    private int safetyFilled;
    private int safetyAt;
    private int warmTicks;
    private int warmIndex;
    private int warmupUsed;
    private boolean steadyReached;
    private int hotspotsReadyAtStart = -1;
    private double clockReadNanos;
    private long gcStartMillis;
    private long gcEndMillis;

    public EfficiencySession(ModeSetup setup, EfficiencyConfig config) {
        this(setup, config, System::nanoTime);
    }

    public EfficiencySession(ModeSetup setup, EfficiencyConfig config, LongSupplier clock) {
        this.setup = setup;
        this.config = config;
        this.clock = clock;
        this.primary = setup.primary();
        this.loader = setup.loader();
        this.hotspots = new HotspotRegistry(setup.baseChunkX(), setup.baseChunkZ());
        this.feeder = new OpFeeder(setup.seed(), hotspots, setup.yRange(), primary);
        this.safetyRing = new long[config.holdTicks()];
        this.thrash = config.thrashWorkers() > 0 ? new ModeThrash(config.thrashWorkers(), primary, hotspots, setup.yRange(), setup.seed()) : null;
        java.util.Arrays.fill(warmPending, -1);
    }

    @Override
    public String mode() {
        return "efficiency";
    }

    @Override
    public String runId() {
        return setup.runId();
    }

    @Override
    public void onServerTickStart(long serverTick) {
        if (phase == Phase.FINISHED) return;
        long now = clock.getAsLong();
        tickStart = now;
        if (phase == Phase.NEW) begin(now);
        inFlight.poll(ModeConfig.POLL_ENTRIES_PER_TICK, primary, now, settler, null, 100, ModeConfig.PENDING_MAX_AGE_NANOS);
        if (thrash != null) thrash.burstIfDue(serverTick);
        runWindow(phase == Phase.MEASURING);
        lastHarness = Math.max(0L, clock.getAsLong() - tickStart);
    }

    @Override
    public void onServerTickEnd(long serverTick) {
        if (phase == Phase.NEW || phase == Phase.FINISHED) return;
        long now = clock.getAsLong();
        long mspt = Math.max(0L, now - tickStart);
        lastMspt = mspt;
        if (phase == Phase.MEASURING) {
            int row = ticks.add();
            ticks.set(C_OPS, row, lastOps);
            ticks.set(C_PASS, row, lastPass);
            ticks.set(C_PENDING, row, lastPending);
            ticks.set(C_WINDOW, row, lastWindowNanos);
            ticks.set(C_HARNESS, row, lastHarness);
            ticks.set(C_MSPT, row, mspt);
            long gc = GcMeter.totalMillis();
            ticks.set(C_GC, row, gc);
            gcEndMillis = gc;
        } else {
            warmTicks++;
            warmPending[warmIndex] = (int) Math.min(Integer.MAX_VALUE, lastPending);
            warmIndex = (warmIndex + 1) % warmPending.length;
            if (warmTicks >= config.warmupTicks() && (steady() || warmTicks >= config.warmupTicks() + ModeConfig.WARMUP_EXTENSION_TICKS)) startMeasuring();
        }
        safetySum -= safetyRing[safetyAt];
        safetyRing[safetyAt] = mspt;
        safetySum += mspt;
        safetyAt = (safetyAt + 1) % safetyRing.length;
        if (safetyFilled < safetyRing.length) safetyFilled++;
        if (safetyFilled == safetyRing.length && safetySum / (double) safetyRing.length > config.safetyMs() * 1_000_000.0) finish(Stop.SAFETY, now);
        else if (phase == Phase.MEASURING && ticks.size() >= config.ticks()) finish(Stop.COMPLETED, now);
        else if (stopRequested) finish(Stop.STOPPED, now);
    }

    @Override
    public boolean isFinished() {
        return phase == Phase.FINISHED;
    }

    @Override
    public void stop() {
        if (phase != Phase.FINISHED) stopRequested = true;
    }

    @Override
    public void abort() {
        if (phase == Phase.FINISHED) return;
        finish(Stop.SERVER_STOPPING, clock.getAsLong());
    }

    @Override
    public String statusLine() {
        return switch (phase) {
            case NEW -> "efficiency: waiting for the first server tick";
            case WARMUP -> String.format(Locale.ROOT, "efficiency warming up: %d ticks so far, pending reads in the last tick %d", warmTicks, lastPending);
            case MEASURING -> String.format(Locale.ROOT, "efficiency measuring: %d of %d ticks, last window %d ops in %.3f ms, last MSPT %.1f ms", ticks.size(), config.ticks(), lastOps, lastWindowNanos / 1_000_000.0, lastMspt / 1_000_000.0);
            case FINISHED -> "efficiency finished: " + (stop == null ? "" : stop.text());
        };
    }

    @Override
    public ModeReport report() {
        if (report == null || phase != Phase.FINISHED) report = EfficiencyReport.build(this);
        return report;
    }

    @Override
    public Map<String, String> storeValues() {
        if (stop != Stop.COMPLETED) return Map.of();
        return EfficiencyReport.storeValues(this);
    }

    @Override
    public void otherArm(Map<String, String> values) {
        this.other = values == null ? Map.of() : values;
        this.report = null;
    }

    private void begin(long now) {
        startNanos = now;
        clockReadNanos = calibrateClock();
        gcStartMillis = GcMeter.totalMillis();
        gcEndMillis = gcStartMillis;
        HotspotPins.pin(loader, hotspots);
        if (thrash != null) thrash.warmUp();
        phase = Phase.WARMUP;
        if (config.warmupTicks() == 0) startMeasuring();
    }

    private void startMeasuring() {
        warmupUsed = warmTicks;
        steadyReached = steady();
        hotspotsReadyAtStart = HotspotPins.ready(loader, hotspots);
        gcStartMillis = GcMeter.totalMillis();
        gcEndMillis = gcStartMillis;
        phase = Phase.MEASURING;
    }

    private boolean steady() {
        int span = Math.max(1, Math.min(warmPending.length, config.warmupTicks()));
        if (warmTicks < span) return false;
        for (int i = 1; i <= span; i++) {
            int index = ((warmIndex - i) % warmPending.length + warmPending.length) % warmPending.length;
            if (warmPending[index] != 0) return false;
        }
        return true;
    }

    private double calibrateClock() {
        int reads = 20_000;
        long first = clock.getAsLong();
        long last = first;
        for (int i = 0; i < reads; i++) last = clock.getAsLong();
        return Math.max(0L, last - first) / (double) reads;
    }

    private void runWindow(boolean measuring) {
        long windowStart = clock.getAsLong();
        long deadline = windowStart + config.windowNanos();
        long mark = windowStart;
        long ops = 0L;
        long pass = 0L;
        long pending = 0L;
        while (true) {
            Outcome outcome = feeder.issue(cursor++);
            long t = clock.getAsLong();
            ReadAction action = feeder.action();
            ops++;
            if (outcome == Outcome.PENDING) {
                pending++;
                inFlight.add(feeder.token(), t, action.family());
            } else if (outcome == Outcome.PASS) {
                pass++;
            }
            if (measuring) {
                int a = action.ordinal();
                actionOps[a]++;
                actionNanos[a] += t - mark;
                if (outcome == Outcome.PASS) actionPass[a]++;
                if (outcome != Outcome.PENDING) immediate[outcome.ordinal()]++;
            }
            mark = t;
            if (t >= deadline || ops >= ModeConfig.MAX_OPS_PER_WINDOW) break;
        }
        if (measuring) pendingIssued += pending;
        lastWindowNanos = mark - windowStart;
        lastOps = ops;
        lastPass = pass;
        lastPending = pending;
    }

    private void finish(Stop reason, long now) {
        if (phase == Phase.FINISHED) return;
        stop = reason;
        endNanos = now;
        loader.releaseAll();
        if (thrash != null) thrash.shutdown();
        phase = Phase.FINISHED;
    }

    private void settle(long token, Family family, Outcome outcome, long issueNanos, long nowNanos) {
        settled[outcome.ordinal()]++;
    }

    Arm arm() {
        return primary.arm();
    }

    ModeSetup setup() {
        return setup;
    }

    EfficiencyConfig config() {
        return config;
    }

    Columns ticks() {
        return ticks;
    }

    Stop stopReason() {
        return stop;
    }

    ModeThrash thrash() {
        return thrash;
    }

    Map<String, String> other() {
        return other;
    }

    long[] immediateOutcomes() {
        return immediate;
    }

    long[] settledOutcomes() {
        return settled;
    }

    long[] actionOps() {
        return actionOps;
    }

    long[] actionPass() {
        return actionPass;
    }

    long[] actionNanos() {
        return actionNanos;
    }

    long pendingIssued() {
        return pendingIssued;
    }

    int pendingLeft() {
        return inFlight.waiters();
    }

    int warmupUsed() {
        return warmupUsed;
    }

    boolean steadyReached() {
        return steadyReached;
    }

    int hotspotsReady() {
        return hotspotsReadyAtStart;
    }

    double clockReadNanos() {
        return clockReadNanos;
    }

    long wallNanos() {
        return Math.max(0L, endNanos - startNanos);
    }

    long gcMillis() {
        return Math.max(0L, gcEndMillis - gcStartMillis);
    }

    long checksum() {
        return feeder.checksum();
    }
}
