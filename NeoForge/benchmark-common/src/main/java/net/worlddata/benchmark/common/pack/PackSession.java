package net.worlddata.benchmark.common.pack;

import net.worlddata.benchmark.common.performance.PerformanceSampler;
import net.worlddata.benchmark.common.performance.PerformanceStats;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public final class PackSession {

    private final PackSetup setup;
    private final WorldReader primary;
    private final WorldReader baseline;
    private final ChunkLoader loader;
    private final CallRecorder recorder;
    private final OpsLedger ledger;
    private final LongSupplier clock;
    private final TickBudget budget;
    private final TickBudget clientBudget;
    private final InFlightTable inFlight = new InFlightTable();
    private final HotspotRegistry hotspots;
    private final WaveSchedule schedule;
    private final WaveRunner waves;
    private final BatchPlanner planner;
    private final MutationEngine mutation;
    private final YRange yRange;
    private final OpsRate rate;
    private final ThrashPipeline thrash;
    private final ThrashLanes lanes;
    private final DataSink sink = new DataSink(PackConfig.SINK_RING);
    private final PlannedIssue plan = new PlannedIssue();
    private final BaselineSampler baselineSampler = new BaselineSampler();
    private final PerformanceSampler tickSampler = new PerformanceSampler();
    private final LatencyHistogram pendingWait = new LatencyHistogram();
    private final LatencyHistogram harnessTick = new LatencyHistogram();
    private final List<PhaseMark> marks = new ArrayList<>();
    private final List<long[]> curve = new ArrayList<>();
    private final AtomicLong lightCredits = new AtomicLong();
    private final AtomicLong lightIndex = new AtomicLong();
    private final InFlightTable.Settler settler = this::settle;
    private final int[] probeX = new int[PackConfig.PROBE_CELLS];
    private final int[] probeY = new int[PackConfig.PROBE_CELLS];
    private final int[] probeZ = new int[PackConfig.PROBE_CELLS];

    private volatile PackSessionState state = PackSessionState.NEW;
    private volatile PackPhase phase = PackPhase.PRIME;
    private volatile long lastTickNanos;
    private volatile long lastHarnessNanos;
    private volatile ThrashPipeline.Burst lastBurst;
    private volatile int batchNo = -1;
    private volatile int batchSize;
    private volatile long windowOps;

    private BatchProfile profile;
    private boolean batchOpen;
    private long batchStartIndex;
    private int batchIssued;
    private long cursor;
    private int tickOps;
    private int tickBatches;
    private long tickAllowance;
    private long rateCutTicks;
    private long rateIdleTicks;
    private int maxBatchesInTick;
    private long idleIssueTicks;
    private long budgetCutTicks;
    private long overBudgetTicks;
    private long harnessSumNanos;
    private long harnessMaxNanos;
    private long batchesOpened;
    private long batchesClosed;
    private long batchesOpenedWhileWaiting;
    private long currentTick;
    private long sideLaneDeadlineNanos = PackConfig.SIDE_LANE_DEADLINE_NANOS;

    private long writeIndex;
    private long ticks;
    private long tickStartNanos;
    private long startNanos;
    private long endNanos;
    private long reachedTotalNanos;
    private long tickAllocStart = -1L;
    private long tickAllocSum;
    private long tickAllocMax;
    private long tickAllocSamples;
    private long msptSumNanos;
    private long drainTicks;
    private long drainStartNanos;
    private boolean issuingDone;
    private boolean stoppedEarly;
    private boolean forcingSettle;
    private int restoredPositions = -1;
    private long baselineSamples;
    private long thrashSkipped;
    private long probeReads;
    private boolean probePending;
    private int probeWaitTicks;
    private long probeEstimateNanos = PackConfig.PROBE_ESTIMATE_NANOS;
    private int probeReadsPerThread = PackConfig.PROBE_READS_MAX;
    private boolean allocPending;
    private int allocWaitTicks;
    private long allocEstimateNanos;
    private int curveStride = 20;
    private double allocSum;
    private double allocMax;
    private int allocSamples;
    private int allocUnsupported;
    private String stopReason = "completed total";

    public PackSession(PackSetup setup) {
        this(setup, System::nanoTime);
    }

    public PackSession(PackSetup setup, LongSupplier clock) {
        this.setup = setup;
        this.clock = clock;
        this.primary = setup.primary();
        this.baseline = setup.baseline();
        this.loader = setup.loader();
        this.recorder = setup.recorder();
        this.ledger = new OpsLedger(setup.totalOps());
        long limit = setup.tickBudgetNanos() <= 0L ? PackConfig.TICK_BUDGET_DEFAULT_NANOS : PackConfig.clampTickBudgetNanos(setup.tickBudgetNanos());
        this.budget = new TickBudget(limit, clock);
        this.clientBudget = new TickBudget(limit, clock);
        this.hotspots = new HotspotRegistry(setup.baseChunkX(), setup.baseChunkZ());
        this.schedule = new WaveSchedule(setup.seed(), setup.totalOps(), setup.baseChunkX(), setup.baseChunkZ());
        this.waves = new WaveRunner(schedule, loader, clock);
        this.planner = new BatchPlanner(setup.seed(), setup.totalOps());
        this.yRange = setup.yRange();
        this.rate = new OpsRate(setup.opsPerSecond());
        this.mutation = new MutationEngine(setup.writer(), primary, hotspots, yRange.arenaY());
        this.thrash = new ThrashPipeline(setup.thrashWorkers());
        this.lanes = new ThrashLanes(setup.thrashWorkers());
    }

    void sideLaneDeadlineNanos(long nanos) {
        this.sideLaneDeadlineNanos = nanos;
        this.waves.deadlineNanos(nanos);
    }

    public void onServerTickStart(long serverTick) {
        if (state == PackSessionState.FINISHED) return;
        long now = clock.getAsLong();
        tickStartNanos = now;
        budget.begin(now);
        tickAllocStart = AllocationMeter.threadBytes();
        currentTick = serverTick;
        if (state == PackSessionState.NEW) begin(now);
        if (state == PackSessionState.DRAINING) {
            drainTick(now);
            recordHarness();
            return;
        }
        PackPhase current = PackPhase.of(ledger.completed(), ledger.total());
        if (current != phase) enterPhase(current);
        inFlight.poll(PackConfig.POLL_ENTRIES_PER_TICK, primary, now, settler, budget, PackConfig.POLL_SHARE_PERCENT, sideLaneDeadlineNanos);
        waves.tick(cursor, ticks, budget, true);
        probeIfDue();
        allocationIfDue();
        driveBatches();
        recordHarness();
        if (issuingDone) startDrain(clock.getAsLong());
    }

    public void onServerTickEnd(long serverTick) {
        if (state == PackSessionState.NEW || state == PackSessionState.FINISHED) return;
        long now = clock.getAsLong();
        long duration = now - tickStartNanos;
        lastTickNanos = duration;
        tickSampler.sample(duration);
        long allocEnd = AllocationMeter.threadBytes();
        if (tickAllocStart >= 0L && allocEnd >= tickAllocStart) {
            long used = allocEnd - tickAllocStart;
            tickAllocSum += used;
            tickAllocMax = Math.max(tickAllocMax, used);
            tickAllocSamples++;
        }
        msptSumNanos += duration;
        ticks++;
        mutation.tickEnd(serverTick);
        if (reachedTotalNanos == 0L && ledger.completed() >= ledger.total()) reachedTotalNanos = now;
        if (ticks % curveStride == 0) sampleCurve();
    }

    public void onClientTick() {
        if (!setup.lightOnClientThread()) return;
        PackSessionState s = state;
        if (s != PackSessionState.RUNNING && s != PackSessionState.DRAINING) return;
        clientBudget.begin();
        int done = 0;
        while (done < PackConfig.LIGHT_PER_CLIENT_TICK && lightCredits.get() > 0L) {
            if (done > 0 && clientBudget.exhausted()) break;
            if (lightCredits.decrementAndGet() < 0L) {
                lightCredits.incrementAndGet();
                break;
            }
            executeLight(lightIndex.getAndIncrement());
            done++;
        }
    }

    public void abort() {
        if (state == PackSessionState.FINISHED || state == PackSessionState.NEW) {
            if (state == PackSessionState.NEW) {
                stoppedEarly = true;
                stopReason = "never started";
                state = PackSessionState.FINISHED;
                phase = PackPhase.FINISHED;
            }
            return;
        }
        stoppedEarly = true;
        stopReason = "server stopped";
        issuingDone = true;
        batchOpen = false;
        long now = clock.getAsLong();
        forcingSettle = true;
        inFlight.forceSettle(Outcome.TIMEOUT, now, settler);
        forcingSettle = false;
        forceLightCredits();
        if (state == PackSessionState.RUNNING) startDrain(now);
        finish(now);
    }

    public void stop() {
        if (state == PackSessionState.FINISHED || state == PackSessionState.DRAINING) return;
        stoppedEarly = true;
        stopReason = "stopped early";
        issuingDone = true;
        batchOpen = false;
    }

    private void begin(long now) {
        startNanos = now;
        sink.start(setup.sinkFile());
        thrash.warmUp();
        state = PackSessionState.RUNNING;
        phase = PackPhase.PRIME;
        marks.add(new PhaseMark(PackPhase.PRIME, 0L, 0L, 0L));
        waves.pinHotspots(hotspots);
    }

    private void driveBatches() {
        tickOps = 0;
        tickBatches = 0;
        tickAllowance = rate.beginTick(tickStartNanos);
        boolean rateLimited = false;
        while (!issuingDone) {
            if (tickOps >= tickAllowance) {
                rateLimited = true;
                break;
            }
            if (!batchOpen) {
                if (tickOps > 0 && budget.exhausted()) {
                    budgetCutTicks++;
                    break;
                }
                openBatch();
                continue;
            }
            stepBatch();
            if (batchOpen) {
                if (tickOps >= tickAllowance) rateLimited = true;
                else budgetCutTicks++;
                break;
            }
        }
        rate.endTick(tickStartNanos, tickOps);
        windowOps = rate.windowOps();
        if (rateLimited) rateCutTicks++;
        if (tickOps == 0 && !issuingDone) {
            if (rateLimited) rateIdleTicks++;
            else idleIssueTicks++;
        }
        maxBatchesInTick = Math.max(maxBatchesInTick, tickBatches);
    }

    private void openBatch() {
        int next = batchNo + 1;
        if (next >= planner.batchCount()) {
            issuingDone = true;
            return;
        }
        BatchPlanner.Batch b = planner.batch(next);
        batchNo = next;
        batchSize = b.size();
        profile = BatchProfile.of(setup.seed(), next);
        lanes.resetBatch();
        batchStartIndex = b.startIndex();
        batchIssued = 0;
        if (next > 0 && next % PackConfig.PROBE_EVERY_BATCHES == PackConfig.PROBE_EVERY_BATCHES / 2) probePending = true;
        if (next > 0 && (inFlight.waiters() > 0 || waves.activeNow() > 0)) batchesOpenedWhileWaiting++;
        batchOpen = true;
        batchesOpened++;
        tickBatches++;
    }

    private void stepBatch() {
        while (batchIssued < batchSize) {
            if (tickOps >= tickAllowance) return;
            if (tickOps > 0 && budget.exhausted()) return;
            issueOne(batchStartIndex + batchIssued, batchIssued);
            batchIssued++;
            cursor++;
            tickOps++;
        }
        batchesClosed++;
        batchOpen = false;
        if (batchNo + 1 >= planner.batchCount()) issuingDone = true;
    }

    private void enterPhase(PackPhase next) {
        phase = next;
        marks.add(new PhaseMark(next, ledger.completed(), (clock.getAsLong() - startNanos) / 1_000_000L, ticks));
    }

    private void issueOne(long index, int indexInBatch) {
        PackPhase p = phase;
        if (p == PackPhase.MUTATION && setup.writer() != null && IssuePlan.isWrite(setup.seed(), index)) {
            ledger.issued(true);
            Outcome outcome = mutation.step(writeIndex++, ticks);
            ledger.complete(Family.WRITE, outcome, p);
            return;
        }
        IssuePlan.fill(plan, setup.seed(), index, profile, indexInBatch, yRange, setup.thrashWorkers());
        if (plan.hotspot && plan.kind == OpKind.LAYER && !plan.shared) plan.action = hotspots.focusedLayer(plan.action, plan.slot, ledger.completed(), ledger.total());
        if (plan.kind == OpKind.LIGHT && setup.lightOnClientThread()) {
            lightCredits.incrementAndGet();
            return;
        }
        long now = clock.getAsLong();
        int cx;
        int cz;
        WavePlan wave = waves.currentPlan();
        if (plan.coldProbe) {
            cx = WavePlan.coldProbeChunkX(setup.baseChunkX(), plan.hash);
            cz = WavePlan.coldProbeChunkZ(setup.baseChunkZ(), plan.hash);
        } else if (plan.hotspot || p == PackPhase.PRIME || wave == null) {
            cx = hotspots.chunkX(plan.slot % PackConfig.HOTSPOT_CHUNKS);
            cz = hotspots.chunkZ(plan.slot % PackConfig.HOTSPOT_CHUNKS);
        } else {
            cx = wave.chunkX(plan.slot);
            cz = wave.chunkZ(plan.slot);
        }
        int x = (cx << 4) + plan.localX;
        int z = (cz << 4) + plan.localZ;
        ledger.issued(false);
        Outcome outcome = primary.read(plan.action, x, plan.y, z, plan.extra);
        lanes.record(plan.worker, plan.shared, outcome);
        if (outcome == Outcome.PENDING) {
            ledger.pending();
            inFlight.add(primary.chunkToken(cx, cz), now, plan.action.family());
        } else {
            ledger.complete(plan.action.family(), outcome, p);
            if (outcome == Outcome.PASS) {
                long digest = primary.lastDigest();
                sink.accept(digest);
                if (plan.shared) lanes.overlap(plan.cell, plan.action, digest, currentTick);
            }
        }
        if (baseline != null && plan.action.baselineSampled() && baselineSampler.due(plan.action, index)) {
            Outcome sampled = baseline.read(plan.action, x, plan.y, z, plan.extra);
            baselineSampler.taken(plan.action, sampled);
            baselineSamples++;
        }
    }

    private void executeLight(long lightSeq) {
        long h = IssuePlan.lightHash(setup.seed(), lightSeq);
        int slot = SeedMix.bounded(h, 8, PackConfig.HOTSPOT_CHUNKS);
        int x = (hotspots.chunkX(slot) << 4) + SeedMix.bounded(h, 20, 16);
        int z = (hotspots.chunkZ(slot) << 4) + SeedMix.bounded(h, 26, 16);
        int y = yRange.readMin() + SeedMix.bounded(h, 32, yRange.readSpan());
        ledger.issued(false);
        Outcome outcome = primary.read(ReadAction.LIGHT_AT, x, y, z, 0);
        if (outcome == Outcome.PENDING) outcome = Outcome.UNAVAILABLE;
        ledger.complete(Family.LIGHT, outcome, phase);
        if (outcome == Outcome.PASS) sink.accept(primary.lastDigest());
    }

    private void probeIfDue() {
        if (!probePending || state != PackSessionState.RUNNING) return;
        if (budget.remainingNanos() < probeEstimateNanos && probeWaitTicks++ < 3) return;
        probePending = false;
        probeWaitTicks = 0;
        int burstNumber = thrash.bursts();
        int slot = burstNumber % PackConfig.HOTSPOT_CHUNKS;
        int cx = hotspots.chunkX(slot);
        int cz = hotspots.chunkZ(slot);
        ThrashProbe probe = primary.openThrash(cx, cz);
        if (probe == null) {
            thrashSkipped++;
            return;
        }
        for (int i = 0; i < PackConfig.PROBE_CELLS; i++) {
            long h = SeedMix.at(setup.seed() ^ 0x5448L, (long) burstNumber * PackConfig.PROBE_CELLS + i);
            probeX[i] = (cx << 4) + SeedMix.bounded(h, 8, 16);
            probeZ[i] = (cz << 4) + SeedMix.bounded(h, 16, 16);
            probeY[i] = yRange.readMin() + SeedMix.bounded(h, 24, yRange.readSpan());
        }
        long before = clock.getAsLong();
        ThrashPipeline.Burst burst = thrash.burst(probe, probeX, probeY, probeZ, probeReadsPerThread);
        long cost = Math.max(0L, clock.getAsLong() - before);
        probeEstimateNanos = (probeEstimateNanos * 3L + cost) / 4L;
        if (cost > budget.limitNanos() / 2L) probeReadsPerThread = Math.max(PackConfig.PROBE_READS_MIN, probeReadsPerThread / 2);
        else if (cost < budget.limitNanos() / 8L) probeReadsPerThread = Math.min(PackConfig.PROBE_READS_MAX, probeReadsPerThread * 2);
        probeReads += burst.totalReads();
        lastBurst = burst;
    }

    private void allocationIfDue() {
        if (phase == PackPhase.THRASH || phase == PackPhase.MUTATION) {
            if (ticks > 0 && ticks % PackConfig.ALLOC_EVERY_TICKS == 0) allocPending = true;
        }
        if (!allocPending) return;
        if (budget.remainingNanos() < allocEstimateNanos && allocWaitTicks++ < 3) return;
        allocPending = false;
        allocWaitTicks = 0;
        int slot = (int) ((ticks / PackConfig.ALLOC_EVERY_TICKS) % PackConfig.HOTSPOT_CHUNKS);
        long before = clock.getAsLong();
        double bytes = primary.visitAllocationBytesPerCall(hotspots.chunkX(slot), hotspots.chunkZ(slot), PackConfig.ALLOC_CALLS);
        long cost = Math.max(0L, clock.getAsLong() - before);
        allocEstimateNanos = (allocEstimateNanos * 3L + cost) / 4L;
        if (bytes < 0.0) {
            allocUnsupported++;
            return;
        }
        allocSamples++;
        allocSum += bytes;
        allocMax = Math.max(allocMax, bytes);
    }

    private void recordHarness() {
        long used = Math.max(0L, clock.getAsLong() - tickStartNanos);
        lastHarnessNanos = used;
        harnessTick.record(used);
        harnessSumNanos += used;
        harnessMaxNanos = Math.max(harnessMaxNanos, used);
        if (used > budget.limitNanos() * 2L) overBudgetTicks++;
    }

    private void sampleCurve() {
        curve.add(new long[] {ticks, ledger.completed(), ledger.issued(), ledger.inFlight(), lastTickNanos / 1000L, batchSize, batchNo, lastHarnessNanos / 1000L});
        if (curve.size() >= 2048) {
            List<long[]> kept = new ArrayList<>();
            for (int i = 0; i < curve.size(); i += 2) kept.add(curve.get(i));
            curve.clear();
            curve.addAll(kept);
            curveStride *= 2;
        }
    }

    public List<long[]> curve() {
        return List.copyOf(curve);
    }

    private void startDrain(long now) {
        state = PackSessionState.DRAINING;
        phase = PackPhase.DRAIN;
        drainStartNanos = now;
        marks.add(new PhaseMark(PackPhase.DRAIN, ledger.completed(), (now - startNanos) / 1_000_000L, ticks));
    }

    private void drainTick(long now) {
        drainTicks++;
        inFlight.poll(PackConfig.DRAIN_POLL_ENTRIES, primary, now, settler, budget, 100, sideLaneDeadlineNanos);
        waves.tick(cursor, ticks, budget, !stoppedEarly);
        if (lightCredits.get() > 0L && drainTicks > PackConfig.LIGHT_FORCE_TICKS) forceLightCredits();
        boolean clear = inFlight.waiters() == 0 && lightCredits.get() == 0L && waves.allStartedFinished() && (stoppedEarly || waves.started() == waves.planned());
        if (clear) {
            finish(now);
        } else if (drainTicks > PackConfig.DRAIN_DEADLINE_TICKS) {
            forcingSettle = true;
            inFlight.forceSettle(Outcome.TIMEOUT, now, settler);
            forcingSettle = false;
            forceLightCredits();
            finish(now);
        }
    }

    private void forceLightCredits() {
        long left = lightCredits.getAndSet(0L);
        for (long i = 0; i < left; i++) {
            ledger.lightCreditForced();
            executeLight(lightIndex.getAndIncrement());
        }
    }

    private void settle(long token, Family family, Outcome outcome, long issueNanos, long nowNanos) {
        ledger.settledPending(1);
        ledger.complete(family, outcome, phase);
        if (forcingSettle) ledger.drainTimeout();
        pendingWait.record(Math.max(0L, nowNanos - issueNanos));
        if (outcome == Outcome.PASS) sink.accept(token);
    }

    private void finish(long now) {
        endNanos = now;
        mutation.audit();
        restoredPositions = mutation.restore();
        waves.finish();
        sink.close();
        thrash.shutdown();
        phase = PackPhase.FINISHED;
        marks.add(new PhaseMark(PackPhase.FINISHED, ledger.completed(), (now - startNanos) / 1_000_000L, ticks));
        state = PackSessionState.FINISHED;
    }

    public PackSetup setup() {
        return setup;
    }

    public PackSessionState state() {
        return state;
    }

    public PackPhase phase() {
        return phase;
    }

    public boolean isFinished() {
        return state == PackSessionState.FINISHED;
    }

    public OpsLedger ledger() {
        return ledger;
    }

    public CallRecorder recorder() {
        return recorder;
    }

    public MutationEngine mutation() {
        return mutation;
    }

    public ThrashPipeline thrash() {
        return thrash;
    }

    public ThrashLanes lanes() {
        return lanes;
    }

    public ThrashPipeline.Burst lastBurst() {
        return lastBurst;
    }

    public DataSink sink() {
        return sink;
    }

    public BatchPlanner planner() {
        return planner;
    }

    public WaveSchedule schedule() {
        return schedule;
    }

    public WaveRunner waves() {
        return waves;
    }

    public ChunkLoader loader() {
        return loader;
    }

    public TickBudget budget() {
        return budget;
    }

    public InFlightTable inFlightTable() {
        return inFlight;
    }

    public List<PhaseMark> marks() {
        return List.copyOf(marks);
    }

    public PerformanceStats tickStats() {
        return tickSampler.snapshotAsDurations();
    }

    public LatencyHistogram pendingWait() {
        return pendingWait;
    }

    public LatencyHistogram restoreWait() {
        return waves.restoreWait();
    }

    public LatencyHistogram coldWait() {
        return waves.coldWait();
    }

    public LatencyHistogram harnessTick() {
        return harnessTick;
    }

    public long ticks() {
        return ticks;
    }

    public int batchNumber() {
        return batchNo;
    }

    public int currentBatchSize() {
        return batchSize;
    }

    public long cursor() {
        return cursor;
    }

    public long batchesOpened() {
        return batchesOpened;
    }

    public long batchesClosed() {
        return batchesClosed;
    }

    public long batchesOpenedWhileWaiting() {
        return batchesOpenedWhileWaiting;
    }

    public int maxBatchesInOneTick() {
        return maxBatchesInTick;
    }

    public long idleIssueTicks() {
        return idleIssueTicks;
    }

    public long rateCutTicks() {
        return rateCutTicks;
    }

    public long rateIdleTicks() {
        return rateIdleTicks;
    }

    public long opsPerSecondCap() {
        return rate.cap();
    }

    public long opsPerSecondNow() {
        return windowOps;
    }

    public long opsPerSecondPeak() {
        return rate.peak();
    }

    public double opsPerSecondAverage() {
        long millis = timeToFinishMillis();
        return millis <= 0L ? 0.0 : ledger.completed() * 1000.0 / millis;
    }

    public YRange yRange() {
        return yRange;
    }

    public long budgetCutTicks() {
        return budgetCutTicks;
    }

    public long overBudgetTicks() {
        return overBudgetTicks;
    }

    public long watchdogTimeouts() {
        return inFlight.watchdogTimeouts();
    }

    public double harnessMeanMillis() {
        return ticks == 0L ? 0.0 : harnessSumNanos / (double) Math.max(1L, ticks) / 1_000_000.0;
    }

    public double harnessMaxMillis() {
        return harnessMaxNanos / 1_000_000.0;
    }

    public double lastHarnessMillis() {
        return lastHarnessNanos / 1_000_000.0;
    }

    public int interBatchPauseTicks() {
        return PackConfig.INTER_BATCH_PAUSE_TICKS;
    }

    public double meanTickMillis() {
        return ticks == 0L ? 0.0 : msptSumNanos / (double) ticks / 1_000_000.0;
    }

    public double lastTickMillis() {
        return lastTickNanos / 1_000_000.0;
    }

    public long totalTickNanos() {
        return msptSumNanos;
    }

    public double tickAllocMeanKilobytes() {
        return tickAllocSamples == 0L ? -1.0 : tickAllocSum / (double) tickAllocSamples / 1024.0;
    }

    public double tickAllocMaxKilobytes() {
        return tickAllocSamples == 0L ? -1.0 : tickAllocMax / 1024.0;
    }

    public long wallMillis() {
        if (startNanos == 0L) return 0L;
        long end = endNanos != 0L ? endNanos : clock.getAsLong();
        return (end - startNanos) / 1_000_000L;
    }

    public long timeToFinishMillis() {
        if (startNanos == 0L) return 0L;
        if (reachedTotalNanos != 0L) return (reachedTotalNanos - startNanos) / 1_000_000L;
        if (state == PackSessionState.FINISHED && ledger.completed() >= ledger.total()) return wallMillis();
        return -1L;
    }

    public long drainMillis() {
        if (drainStartNanos == 0L) return 0L;
        long end = endNanos != 0L ? endNanos : clock.getAsLong();
        return (end - drainStartNanos) / 1_000_000L;
    }

    public boolean stoppedEarly() {
        return stoppedEarly;
    }

    public String stopReason() {
        return stopReason;
    }

    public int restoredPositions() {
        return restoredPositions;
    }

    public long baselineSamples() {
        return baselineSamples;
    }

    public BaselineSampler baselineSampler() {
        return baselineSampler;
    }

    public long thrashSkipped() {
        return thrashSkipped;
    }

    public long probeReads() {
        return probeReads;
    }

    public int probeReadsPerThread() {
        return probeReadsPerThread;
    }

    public double allocMeanBytes() {
        return allocSamples == 0 ? -1.0 : allocSum / allocSamples;
    }

    public double allocMaxBytes() {
        return allocSamples == 0 ? -1.0 : allocMax;
    }

    public int allocSamples() {
        return allocSamples;
    }

    public int allocUnsupported() {
        return allocUnsupported;
    }

    public long lightCreditsPending() {
        return lightCredits.get();
    }

    public long forceLoadCalls() {
        return loader.forceLoadCalls();
    }

    public long forceLoadBlocked() {
        return loader.forceLoadBlocked();
    }

    public int maxPendingLag() {
        return mutation.lagMaxTicks();
    }

    public MutationEngine.Audit audit() {
        return mutation.audit();
    }

    public double opsPerMsptMilli() {
        double totalMs = msptSumNanos / 1_000_000.0;
        return totalMs <= 0.0 ? 0.0 : ledger.completed() / totalMs;
    }
}
