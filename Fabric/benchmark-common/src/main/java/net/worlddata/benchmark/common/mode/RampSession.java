package net.worlddata.benchmark.common.mode;

import net.worlddata.benchmark.common.pack.Arm;
import net.worlddata.benchmark.common.pack.ChunkLoader;
import net.worlddata.benchmark.common.pack.Family;
import net.worlddata.benchmark.common.pack.HotspotRegistry;
import net.worlddata.benchmark.common.pack.InFlightTable;
import net.worlddata.benchmark.common.pack.Outcome;
import net.worlddata.benchmark.common.pack.PackConfig;
import net.worlddata.benchmark.common.pack.WorldReader;

import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

public final class RampSession implements ModeRun {

    public enum Stop {
        SUSTAINED_OVER("MSPT stayed above the stop level for the whole hold window"),
        OPS_CEILING("the ops/s ceiling was reached and held, MSPT never stayed above the stop level for the hold window"),
        TIME_LIMIT("the time limit was reached, MSPT never stayed above the stop level for the hold window"),
        TICK_LIMIT("the stored tick limit was reached, MSPT never stayed above the stop level for the hold window"),
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

    static final int C_STEP = 0;
    static final int C_RATE = 1;
    static final int C_INTENDED = 2;
    static final int C_ISSUED = 3;
    static final int C_DROPPED = 4;
    static final int C_PASS = 5;
    static final int C_HARNESS = 6;
    static final int C_MSPT = 7;
    static final int C_START = 8;
    static final int C_GC = 9;
    static final int COLUMNS = 10;

    private enum State {
        NEW,
        RUNNING,
        FINISHED
    }

    private final ModeSetup setup;
    private final RampConfig config;
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
    private final ModeThrash thrash;

    private State state = State.NEW;
    private Stop stop;
    private ModeReport report;
    private Map<String, String> other = Map.of();
    private boolean stopRequested;
    private long cursor;
    private long startNanos;
    private long endNanos;
    private long tickStart;
    private long lastRefill;
    private double tokens;
    private int currentStep;
    private long currentRate;
    private long lastIntended;
    private long lastIssued;
    private long lastDropped;
    private long lastPass;
    private long lastHarness;
    private long lastMspt;
    private long pendingIssued;
    private long gcStartMillis;
    private long gcEndMillis;
    private int streak;
    private int streakStartRow = -1;
    private int longestRun;
    private int spikeRuns;
    private long overTicks;
    private int ceilingTicks;
    private int finalStreakStartRow = -1;
    private long droppedTotal;
    private int hotspotsReadyAtEnd = -1;

    public RampSession(ModeSetup setup, RampConfig config) {
        this(setup, config, System::nanoTime);
    }

    public RampSession(ModeSetup setup, RampConfig config, LongSupplier clock) {
        this.setup = setup;
        this.config = config;
        this.clock = clock;
        this.primary = setup.primary();
        this.loader = setup.loader();
        this.hotspots = new HotspotRegistry(setup.baseChunkX(), setup.baseChunkZ());
        this.feeder = new OpFeeder(setup.seed(), hotspots, setup.yRange(), primary);
        this.thrash = config.thrashWorkers() > 0 ? new ModeThrash(config.thrashWorkers(), primary, hotspots, setup.yRange(), setup.seed()) : null;
    }

    @Override
    public String mode() {
        return "ramp";
    }

    @Override
    public String runId() {
        return setup.runId();
    }

    @Override
    public void onServerTickStart(long serverTick) {
        if (state == State.FINISHED) return;
        long now = clock.getAsLong();
        tickStart = now;
        if (state == State.NEW) begin(now);
        inFlight.poll(ModeConfig.POLL_ENTRIES_PER_TICK, primary, now, settler, null, 100, ModeConfig.PENDING_MAX_AGE_NANOS);
        long step = Math.min(Integer.MAX_VALUE, Math.max(0L, (now - startNanos) / config.stepNanos()));
        currentStep = (int) step;
        currentRate = config.rateAtStep(currentStep);
        double elapsedSeconds = Math.max(0L, now - lastRefill) / 1_000_000_000.0;
        lastRefill = now;
        tokens = Math.min(PackConfig.rateCapacity(currentRate), tokens + currentRate * elapsedSeconds);
        long intended = (long) tokens;
        tokens -= intended;
        long ceiling = config.tickIssueNanos();
        long deadline = ceiling > 0L ? tickStart + ceiling : Long.MAX_VALUE;
        long issued = 0L;
        long dropped = 0L;
        long pass = 0L;
        for (long i = 0L; i < intended; i++) {
            if (ceiling > 0L && clock.getAsLong() >= deadline) {
                dropped = intended - i;
                break;
            }
            Outcome outcome = feeder.issue(cursor++);
            issued++;
            if (outcome == Outcome.PENDING) {
                pendingIssued++;
                inFlight.add(feeder.token(), tickStart, feeder.action().family());
            } else {
                immediate[outcome.ordinal()]++;
                if (outcome == Outcome.PASS) pass++;
            }
        }
        if (thrash != null) thrash.burstIfDue(serverTick);
        lastIntended = intended;
        lastIssued = issued;
        lastDropped = dropped;
        lastPass = pass;
        droppedTotal += dropped;
        lastHarness = Math.max(0L, clock.getAsLong() - tickStart);
    }

    @Override
    public void onServerTickEnd(long serverTick) {
        if (state != State.RUNNING) return;
        long now = clock.getAsLong();
        long mspt = Math.max(0L, now - tickStart);
        lastMspt = mspt;
        int row = ticks.add();
        ticks.set(C_STEP, row, currentStep);
        ticks.set(C_RATE, row, currentRate);
        ticks.set(C_INTENDED, row, lastIntended);
        ticks.set(C_ISSUED, row, lastIssued);
        ticks.set(C_DROPPED, row, lastDropped);
        ticks.set(C_PASS, row, lastPass);
        ticks.set(C_HARNESS, row, lastHarness);
        ticks.set(C_MSPT, row, mspt);
        ticks.set(C_START, row, tickStart - startNanos);
        long gc = GcMeter.totalMillis();
        ticks.set(C_GC, row, gc);
        gcEndMillis = gc;
        if (mspt > config.limitNanos()) {
            if (streak == 0) streakStartRow = row;
            streak++;
            overTicks++;
            longestRun = Math.max(longestRun, streak);
        } else if (streak > 0) {
            spikeRuns++;
            streak = 0;
            streakStartRow = -1;
        }
        if (config.rateCapped(currentStep)) ceilingTicks++;
        if (streak >= config.holdTicks()) {
            finalStreakStartRow = streakStartRow;
            finish(Stop.SUSTAINED_OVER, now);
        } else if (stopRequested) {
            finish(Stop.STOPPED, now);
        } else if (ceilingTicks >= config.holdTicks() * 2) {
            finish(Stop.OPS_CEILING, now);
        } else if (now - startNanos >= Math.round(config.maxMinutes() * 60_000_000_000.0)) {
            finish(Stop.TIME_LIMIT, now);
        } else if (ticks.size() >= ModeConfig.MAX_STORED_TICKS) {
            finish(Stop.TICK_LIMIT, now);
        }
    }

    @Override
    public boolean isFinished() {
        return state == State.FINISHED;
    }

    @Override
    public void stop() {
        if (state != State.FINISHED) stopRequested = true;
    }

    @Override
    public void abort() {
        if (state == State.FINISHED) return;
        finish(Stop.SERVER_STOPPING, clock.getAsLong());
    }

    @Override
    public String statusLine() {
        if (state == State.NEW) return "ramp: waiting for the first server tick";
        return String.format(Locale.ROOT, "ramp %s: step %d at %d ops/s intended, %d ticks, last MSPT %.1f ms (stop level %.1f ms), over-limit streak %d of %d",
                state == State.FINISHED ? "finished" : "running", currentStep, currentRate, ticks.size(), lastMspt / 1_000_000.0, config.limitMs(), streak, config.holdTicks());
    }

    @Override
    public ModeReport report() {
        if (report == null || state != State.FINISHED) report = RampReport.build(this);
        return report;
    }

    @Override
    public Map<String, String> storeValues() {
        if (stop == null || stop == Stop.STOPPED || stop == Stop.SERVER_STOPPING || ticks.size() == 0) return Map.of();
        return RampReport.storeValues(this);
    }

    @Override
    public void otherArm(Map<String, String> values) {
        this.other = values == null ? Map.of() : values;
        this.report = null;
    }

    private void begin(long now) {
        startNanos = now;
        lastRefill = now - PackConfig.RATE_NOMINAL_TICK_NANOS;
        gcStartMillis = GcMeter.totalMillis();
        gcEndMillis = gcStartMillis;
        HotspotPins.pin(loader, hotspots);
        if (thrash != null) thrash.warmUp();
        state = State.RUNNING;
    }

    private void finish(Stop reason, long now) {
        if (state == State.FINISHED) return;
        if (state == State.NEW) startNanos = now;
        stop = reason;
        endNanos = now;
        if (state == State.RUNNING) hotspotsReadyAtEnd = HotspotPins.ready(loader, hotspots);
        loader.releaseAll();
        if (thrash != null) thrash.shutdown();
        state = State.FINISHED;
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

    RampConfig config() {
        return config;
    }

    Columns ticks() {
        return ticks;
    }

    Stop stopReason() {
        return stop;
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

    long pendingIssued() {
        return pendingIssued;
    }

    int pendingLeft() {
        return inFlight.waiters();
    }

    int longestRun() {
        return longestRun;
    }

    int spikeRuns() {
        return spikeRuns;
    }

    long overTicks() {
        return overTicks;
    }

    int finalStreakStartRow() {
        return finalStreakStartRow;
    }

    long wallNanos() {
        return Math.max(0L, endNanos - startNanos);
    }

    long gcMillis() {
        return Math.max(0L, gcEndMillis - gcStartMillis);
    }

    long droppedTotal() {
        return droppedTotal;
    }

    long checksum() {
        return feeder.checksum();
    }

    int hotspotsReady() {
        return hotspotsReadyAtEnd;
    }

    ModeThrash thrash() {
        return thrash;
    }
}
