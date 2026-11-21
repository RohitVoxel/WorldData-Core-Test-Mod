package net.worlddata.benchmark.common.pack;

import net.worlddata.benchmark.common.mode.ArmResolver;
import net.worlddata.benchmark.common.performance.PerformanceStats;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PackReportBuilder {

    private PackReportBuilder() {}

    public static PackReport build(PackSession s) {
        return build(s, null);
    }

    public static PackReport build(PackSession s, ArmRun otherArm) {
        Arm arm = s.setup().primary().arm();
        TimeComparison time = compare(s, arm, otherArm);
        String restore = restoreComparison(s, arm, otherArm);
        Map<String, Object> json = json(s, arm, time, restore);
        String markdown = markdown(s, arm, time, restore);
        OpsLedger l = s.ledger();
        String summary = String.format(Locale.ROOT, "ATM_PACK %s: %d/%d ops completed in %s, %.2f ops per MSPT-ms",
                arm.displayName(), l.completed(), l.total(), minutes(timeToFinishMillis(s)), s.opsPerMsptMilli());
        return new PackReport(s.setup().runId(), arm, json, markdown, wideCsv(s, arm, time), latencyCsv(s, arm), curveCsv(s), summary);
    }

    static String restoreComparison(PackSession s, Arm arm, ArmRun other) {
        long mine = s.restoreWait().percentile(0.5);
        long mineCount = s.restoreWait().count();
        long minecraft = arm == Arm.VANILLA ? mine : other != null ? other.restoreP50Nanos() : 0L;
        long core = arm == Arm.CORE ? mine : other != null ? other.restoreP50Nanos() : 0L;
        boolean sameWork = other != null && other.usable() && other.totalOps() == s.ledger().total() && other.seed() == s.setup().seed();
        if (mineCount > 0L && minecraft > 0L && core > 0L && other != null && other.restoreCount() > 0L && sameWork) {
            return String.format(Locale.ROOT, "Minecraft %s, WorldData Core %s, Minecraft / WorldData Core = %.2fx (this run: %d re-admits)", nanos(minecraft), nanos(core), minecraft / (double) core, mineCount);
        }
        return "needs a finished run of the other arm with the same op count and seed";
    }

    static String rateText(long opsPerSecond) {
        return opsPerSecond > 0L ? "an ops per second cap of " + opsPerSecond : "no ops per second cap";
    }

    static long baselinePassed(PackSession s) {
        long passed = 0L;
        for (ReadAction action : ReadAction.values()) passed += s.baselineSampler().passed(action);
        return passed;
    }

    static long coreForceLoadCalls(PackSession s) {
        long calls = 0L;
        for (CallKind kind : CallKind.values()) {
            if (kind.arm() == Arm.CORE && kind.family() == Family.TICKETS) calls += s.recorder().count(kind);
        }
        return calls;
    }

    private static Map<String, Object> json(PackSession s, Arm arm, TimeComparison time, String restore) {
        OpsLedger l = s.ledger();
        PerformanceStats tick = s.tickStats();
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("runId", s.setup().runId());
        run.put("note", ReportNotes.HARNESS);
        run.put("generatedAt", Instant.now().toString());
        run.put("arm", arm.displayName());
        run.put("sourceRequested", s.setup().armRequested().text());
        run.put("coreInstalled", s.setup().coreInstalled());
        run.put("side", s.setup().side());
        run.put("dimension", s.setup().dimension());
        run.put("minBuildHeight", s.setup().minBuildHeight());
        run.put("maxBuildHeight", s.setup().maxBuildHeight());
        run.put("arenaY", s.yRange().arenaY());
        run.put("opsPerSecondCap", s.opsPerSecondCap());
        run.put("opsPerSecondAverage", s.opsPerSecondAverage());
        run.put("opsPerSecondPeak", s.opsPerSecondPeak());
        run.put("seed", s.setup().seed());
        run.put("totalOps", l.total());
        run.put("completedOps", l.completed());
        run.put("issuedOps", l.issued());
        run.put("stoppedEarly", s.stoppedEarly());
        run.put("wallMillis", s.wallMillis());
        run.put("drainMillis", s.drainMillis());
        run.put("ticks", s.ticks());
        run.put("opsPerMsptMs", s.opsPerMsptMilli());
        run.put("environment", s.setup().environment());
        run.put("provider", s.setup().primary().info());
        root.put("run", run);
        root.put("timeToFinishAllOps", time.json());

        Map<String, Object> mspt = new LinkedHashMap<>();
        mspt.put("meanMs", s.meanTickMillis());
        mspt.put("p95Ms", tick.p95TickMillis());
        mspt.put("p99Ms", tick.p99TickMillis());
        mspt.put("maxMs", tick.maxTickMillis());
        mspt.put("tickAllocationMeanKb", s.tickAllocMeanKilobytes());
        mspt.put("tickAllocationMaxKb", s.tickAllocMaxKilobytes());
        root.put("mspt", mspt);

        Map<String, Object> harness = new LinkedHashMap<>();
        harness.put("tickBudgetMs", s.budget().limitNanos() / 1_000_000.0);
        harness.put("meanMs", s.harnessMeanMillis());
        harness.put("p50Ms", s.harnessTick().percentile(0.5) / 1_000_000.0);
        harness.put("p99Ms", s.harnessTick().percentile(0.99) / 1_000_000.0);
        harness.put("maxMs", s.harnessMaxMillis());
        harness.put("ticksCutByBudget", s.budgetCutTicks());
        harness.put("ticksOverTwiceBudget", s.overBudgetTicks());
        root.put("harness", harness);

        BatchPlanner planner = s.planner();
        Map<String, Object> batches = new LinkedHashMap<>();
        batches.put("planned", planner.batchCount());
        batches.put("opened", s.batchesOpened());
        batches.put("closed", s.batchesClosed());
        batches.put("minSize", planner.minBatchSize());
        batches.put("maxSize", planner.maxBatchSize());
        batches.put("maxAllowedSize", PackConfig.BATCH_MAX_OPS);
        batches.put("maxOpenedInOneTick", s.maxBatchesInOneTick());
        batches.put("designedInterBatchPauseTicks", s.interBatchPauseTicks());
        batches.put("idleTicksWithWorkLeft", s.idleIssueTicks());
        batches.put("ticksHeldBackByOpsPerSecondCap", s.rateCutTicks());
        batches.put("idleTicksFromOpsPerSecondCap", s.rateIdleTicks());
        batches.put("openedWhileLoadsOrReadsWaiting", s.batchesOpenedWhileWaiting());
        batches.put("sideLaneWatchdogTimeouts", s.watchdogTimeouts());
        root.put("batches", batches);

        List<Object> phases = new ArrayList<>();
        for (PhaseMark mark : s.marks()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("phase", mark.phase());
            p.put("atCompletedOps", mark.atCompletedOps());
            p.put("atWallMillis", mark.atWallMillis());
            p.put("atTick", mark.atTick());
            p.put("completedInPhase", l.phaseCompleted(mark.phase()));
            phases.add(p);
        }
        root.put("phases", phases);

        Map<String, Object> accounting = new LinkedHashMap<>();
        for (Outcome outcome : Outcome.values()) if (outcome.terminal()) accounting.put(outcome.name().toLowerCase(Locale.ROOT), l.outcome(outcome));
        accounting.put("reads", l.reads());
        accounting.put("writes", l.writes());
        accounting.put("inFlightPeak", l.inFlightPeak());
        accounting.put("inFlightAtEnd", l.inFlight());
        accounting.put("drainTimeouts", l.drainTimeouts());
        accounting.put("balanced", l.balanced());
        root.put("accounting", accounting);

        Map<String, Object> families = new LinkedHashMap<>();
        for (Family family : Family.values()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("completed", l.familyCompleted(family));
            for (Outcome outcome : Outcome.values()) if (outcome.terminal()) f.put(outcome.name().toLowerCase(Locale.ROOT), l.familyOutcome(family, outcome));
            f.put("coreCalls", s.recorder().familyCount(Arm.CORE, family));
            f.put("minecraftCalls", s.recorder().familyCount(Arm.VANILLA, family));
            families.put(family.name(), f);
        }
        root.put("families", families);

        WaveRunner waves = s.waves();
        Map<String, Object> load = new LinkedHashMap<>();
        load.put("coreForceLoadApiCalls", coreForceLoadCalls(s));
        load.put("minecraftWavesPlanned", s.schedule().count());
        load.put("minecraftWavesStarted", waves.started());
        load.put("minecraftWavesFinished", waves.finished());
        load.put("minecraftWavesReleased", waves.released());
        load.put("wavesAbandonedAtEnd", waves.abandoned());
        load.put("wavesRevisiting", waves.revisits());
        load.put("wavesActivePeak", waves.activePeak());
        load.put("chunksPerWaveMin", waves.chunksMin());
        load.put("chunksPerWaveMean", waves.chunksMean());
        load.put("chunksPerWaveMax", waves.chunksMax());
        load.put("chunksRequested", waves.chunksRequested());
        load.put("uniqueChunks", s.loader().uniqueChunks());
        load.put("uniqueChunkCap", PackConfig.UNIQUE_CHUNK_CAP);
        load.put("ticketCalls", s.forceLoadCalls());
        load.put("ticketCallCap", PackConfig.FORCE_LOAD_CALL_CAP);
        load.put("ticketCallsBlocked", s.forceLoadBlocked());
        load.put("requestCalls", waves.requestCalls());
        load.put("releaseCalls", waves.releaseCalls());
        load.put("hotspotPinCalls", waves.pinCalls());
        load.put("groupsLoaded", waves.groupsLoaded());
        load.put("groupsFailed", waves.groupsFailed());
        load.put("groupsTimedOut", waves.groupsTimedOut());
        load.put("groupsBlocked", waves.groupsBlocked());
        load.put("heldGroupsAtEnd", waves.heldGroups());
        load.put("heldChunksAtEnd", s.loader().heldChunks());
        load.put("pendingReadWaitP50Ns", s.pendingWait().percentile(0.5));
        load.put("pendingReadWaitP99Ns", s.pendingWait().percentile(0.99));
        load.put("restoreWaitP50Ns", s.restoreWait().percentile(0.5));
        load.put("restoreWaitCount", s.restoreWait().count());
        load.put("firstLoadWaitP50Ns", s.coldWait().percentile(0.5));
        load.put("firstLoadWaitCount", s.coldWait().count());
        root.put("forceLoad", load);

        List<Object> latency = new ArrayList<>();
        for (CallKind kind : CallKind.values()) {
            if (!armPresent(arm, kind)) continue;
            latency.add(latencyRow(s.recorder().snapshot(kind)));
        }
        root.put("latency", latency);

        List<Object> cross = new ArrayList<>();
        for (ReadAction action : ReadAction.values()) {
            if (!action.baselineSampled()) continue;
            CallSnapshot v = s.recorder().snapshot(action.vanilla());
            CallSnapshot a = s.recorder().snapshot(action.core());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("action", action.name());
            row.put("minecraftCall", action.vanilla().label());
            row.put("minecraftP50Ns", v.p50Nanos());
            row.put("minecraftP99Ns", v.p99Nanos());
            row.put("minecraftCount", v.count());
            row.put("coreCall", action.core().label());
            row.put("coreP50Ns", a.p50Nanos());
            row.put("coreP99Ns", a.p99Nanos());
            row.put("coreCount", a.count());
            row.put("ratioP50", v.p50Nanos() > 0L && a.count() > 0L ? a.p50Nanos() / (double) v.p50Nanos() : null);
            cross.add(row);
        }
        root.put("crossArm", cross);

        Map<String, Object> thrash = new LinkedHashMap<>();
        thrash.put("threads", s.thrash().threads());
        thrash.put("bursts", s.thrash().bursts());
        thrash.put("failedBursts", s.thrash().failedBursts());
        thrash.put("threadDisagreements", s.thrash().disagreements());
        thrash.put("meanRatio", s.thrash().meanRatio());
        thrash.put("worstRatio", s.thrash().worstRatio());
        thrash.put("skippedBursts", s.thrashSkipped());
        thrash.put("probeReads", s.probeReads());
        thrash.put("logicalWorkers", s.lanes().workers());
        thrash.put("logicalOps", s.lanes().totalOps());
        thrash.put("overlapReads", s.lanes().overlapReads());
        thrash.put("overlapComparisons", s.lanes().comparisons());
        thrash.put("overlapDisagreements", s.lanes().disagreements());
        List<Object> workers = new ArrayList<>();
        for (int w = 0; w < s.lanes().workers(); w++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("worker", w);
            row.put("ops", s.lanes().ops(w));
            row.put("sharedCoordinateOps", s.lanes().sharedOps(w));
            row.put("notPassed", s.lanes().notPassed(w));
            workers.add(row);
        }
        thrash.put("workers", workers);
        root.put("thrash", thrash);

        MutationEngine m = s.mutation();
        Map<String, Object> mutation = new LinkedHashMap<>();
        mutation.put("writes", m.writes());
        mutation.put("writeFailures", m.writeFailures());
        for (MutationEngine.Trace trace : MutationEngine.Trace.values()) mutation.put("writes_" + trace.name().toLowerCase(Locale.ROOT), m.traceWrites(trace));
        long[] lag = m.lagCounts();
        List<Object> lagList = new ArrayList<>();
        for (long c : lag) lagList.add(c);
        mutation.put("lagTicksHistogram0to5plus", lagList);
        mutation.put("lagResolved", m.lagResolved());
        mutation.put("lagNeverReflected", m.lagTimeouts());
        mutation.put("lagMaxTicks", m.lagMaxTicks());
        MutationEngine.Audit audit = m.lastAudit();
        Map<String, Object> auditJson = new LinkedHashMap<>();
        auditJson.put("diagnostic", true);
        auditJson.put("positionsChecked", audit.sampled());
        auditJson.put("compared", audit.compared());
        auditJson.put("mismatches", audit.mismatches());
        auditJson.put("unreadable", audit.unreadable());
        auditJson.put("examples", audit.examples());
        auditJson.put("note", "Checked once after the run, before the arena is restored. Compares block ids only, not state properties such as lit or water level. All air variants count as one block. Positions Minecraft or Core could not read are counted as unreadable, not as mismatches. Left out of report.md and report.csv.");
        mutation.put("audit", auditJson);
        mutation.put("positionsRestored", s.restoredPositions());
        root.put("mutation", mutation);

        Map<String, Object> alloc = new LinkedHashMap<>();
        alloc.put("samples", s.allocSamples());
        alloc.put("meanBytesPerCall", s.allocMeanBytes());
        alloc.put("maxBytesPerCall", s.allocMaxBytes());
        root.put("allocation", alloc);

        Map<String, Object> sink = new LinkedHashMap<>();
        sink.put("accepted", s.sink().accepted());
        sink.put("flushed", s.sink().flushed());
        sink.put("dropped", s.sink().dropped());
        sink.put("bytesWritten", s.sink().bytes());
        sink.put("ioFailed", s.sink().ioFailed());
        root.put("sink", sink);

        Map<String, Object> pairs = new LinkedHashMap<>();
        pairs.put("baselineSamples", s.baselineSamples());
        pairs.put("baselineSamplesPassed", baselinePassed(s));
        pairs.put("baselinePolicy", BaselineSampler.policyText());
        root.put("baseline", pairs);
        root.put("reloadComparison", restore);

        return root;
    }

    static long timeToFinishMillis(PackSession s) {
        long t = s.timeToFinishMillis();
        return t < 0L ? s.wallMillis() : t;
    }

    record TimeComparison(long totalOps, long minecraftMillis, String minecraftSource, long coreMillis, String coreSource, boolean comparable, String verdict, String note) {

        Map<String, Object> json() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("totalOps", totalOps);
            m.put("minecraftFinishMillis", minecraftMillis);
            m.put("minecraftFinishDisplay", minecraftMillis > 0L ? minutes(minecraftMillis) : "not run yet");
            m.put("minecraftSource", minecraftSource);
            m.put("worldDataCoreFinishMillis", coreMillis);
            m.put("worldDataCoreFinishDisplay", coreMillis > 0L ? minutes(coreMillis) : "not run yet");
            m.put("worldDataCoreSource", coreSource);
            m.put("comparable", comparable);
            m.put("verdict", verdict);
            if (!note.isEmpty()) m.put("note", note);
            return m;
        }

        String markdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("\n## Time to finish all ").append(totalOps).append(" ops\n\n");
            sb.append("| Side | Time to finish all ops | Ops per second | Source |\n|---|---|---|---|\n");
            sb.append("| Minecraft | ").append(cell(minecraftMillis)).append(" | ").append(rate(minecraftMillis)).append(" | ").append(minecraftSource).append(" |\n");
            sb.append("| WorldData Core | ").append(cell(coreMillis)).append(" | ").append(rate(coreMillis)).append(" | ").append(coreSource).append(" |\n");
            sb.append("\n").append(verdict).append("\n");
            if (!note.isEmpty()) sb.append("\n").append(note).append("\n");
            return sb.toString();
        }

        private String cell(long millis) {
            return millis > 0L ? minutes(millis) + " (" + String.format(Locale.ROOT, "%.1f", millis / 1000.0) + " s)" : "not run yet";
        }

        private String rate(long millis) {
            return millis > 0L ? String.format(Locale.ROOT, "%.0f", totalOps * 1000.0 / millis) : "n/a";
        }
    }

    private static TimeComparison compare(PackSession s, Arm arm, ArmRun other) {
        long total = s.ledger().total();
        long thisMillis = s.stoppedEarly() || s.timeToFinishMillis() < 0L ? -1L : s.timeToFinishMillis();
        String thisSource = thisMillis > 0L ? "this run (" + s.setup().runId() + ")" : "this run was stopped before finishing";
        long minecraft = arm == Arm.VANILLA ? Math.max(0L, thisMillis) : 0L;
        long core = arm == Arm.CORE ? Math.max(0L, thisMillis) : 0L;
        String minecraftSource = arm == Arm.VANILLA ? thisSource : "no saved Minecraft run";
        String coreSource = arm == Arm.CORE ? thisSource : "no saved WorldData Core run";
        boolean sameSeed = true;
        if (other != null && other.usable()) {
            String saved = "saved run " + other.runId() + " (" + other.totalOps() + " ops)";
            if (arm == Arm.CORE) {
                minecraft = other.finishMillis();
                minecraftSource = saved;
            } else {
                core = other.finishMillis();
                coreSource = saved;
            }
            sameSeed = other.seed() == s.setup().seed() && other.totalOps() == total && other.opsPerSecond() == s.setup().opsPerSecond();
        }
        boolean comparable = minecraft > 0L && core > 0L && sameSeed;
        String note = "";
        if (other != null && other.usable() && !sameSeed) {
            note = "The saved " + other.arm().displayName() + " run used " + other.totalOps() + " ops, seed " + other.seed() + " and " + rateText(other.opsPerSecond()) + "; run both sides with the same op count, seed and ops per second cap for a fair comparison.";
        } else if (minecraft <= 0L || core <= 0L) {
            note = "Run the same op count once with WorldData Core installed and once without it (a plain Minecraft run); the second report then shows both times side by side.";
        }
        boolean minecraftWithCore = arm == Arm.VANILLA ? s.setup().coreInstalled() : other != null && other.minecraftWithCoreInstalled();
        if (minecraftWithCore && (minecraft > 0L || arm == Arm.VANILLA)) {
            String caution = "The Minecraft run was made with WorldData Core installed: Core was not queried, but its own tick work (chunk ingestion and cache upkeep) was still running, so that time carries some Core overhead. Remove the Core jar for a pure Minecraft baseline.";
            note = note.isEmpty() ? caution : note + " " + caution;
        }
        String verdict;
        if (comparable) {
            long diff = Math.abs(minecraft - core);
            String faster = core < minecraft ? "WorldData Core" : "Minecraft";
            String slower = core < minecraft ? "Minecraft" : "WorldData Core";
            long slow = Math.max(minecraft, core);
            long fast = Math.min(minecraft, core);
            verdict = diff == 0L ? "Both sides finished in the same time."
                    : String.format(Locale.ROOT, "%s finished all %d ops %s faster than %s (%.1f %% less time, %.2fx).", faster, total, minutes(diff), slower,
                            diff * 100.0 / slow, slow / (double) fast);
        } else if (minecraft > 0L || core > 0L) {
            verdict = "Only one side has finished all ops so far, so there is nothing to compare yet.";
        } else {
            verdict = "No side has finished all ops yet.";
        }
        return new TimeComparison(total, minecraft, minecraftSource, core, coreSource, comparable, verdict, note);
    }

    public static String minutes(long millis) {
        long totalSeconds = Math.max(0L, Math.round(millis / 1000.0));
        long h = totalSeconds / 3600L;
        long m = (totalSeconds % 3600L) / 60L;
        long sec = totalSeconds % 60L;
        if (h > 0L) return String.format(Locale.ROOT, "%d:%02d:%02d hours", h, m, sec);
        return String.format(Locale.ROOT, "%d:%02d minutes", m, sec);
    }

    private static Map<String, Object> latencyRow(CallSnapshot c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("call", c.kind().label());
        row.put("id", c.kind().csvId());
        row.put("arm", c.kind().arm().displayName());
        row.put("family", c.kind().family());
        row.put("total", c.count());
        row.put("passed", c.passed());
        row.put("failed", c.failed());
        row.put("waiting", c.waiting());
        row.put("timeout", c.timeout());
        row.put("unavailable", c.unavailable());
        row.put("unloaded", c.unloaded());
        row.put("minNs", c.minNanos());
        row.put("p50Ns", c.p50Nanos());
        row.put("p95Ns", c.p95Nanos());
        row.put("p99Ns", c.p99Nanos());
        row.put("meanNs", Math.round(c.meanNanos()));
        row.put("maxNs", c.maxNanos());
        row.put("failRate", c.failRate());
        row.put("unavailableRate", c.unavailableRate());
        return row;
    }

    private static boolean armPresent(Arm primary, CallKind kind) {
        return kind.arm() == Arm.VANILLA || primary == Arm.CORE;
    }

    private static String markdown(PackSession s, Arm arm, TimeComparison time, String restore) {
        OpsLedger l = s.ledger();
        PerformanceStats tick = s.tickStats();
        StringBuilder sb = new StringBuilder();
        sb.append("# WorldData Core Test: pack run ").append(s.setup().runId()).append(" (ATM_PACK V1 workload)\n\n");
        sb.append("> ").append(ReportNotes.HARNESS).append("\n\n");
        sb.append("| Item | Value |\n|---|---|\n");
        row(sb, "Arm", arm.displayName() + (arm == Arm.CORE ? " (WorldData Core read side, Minecraft write side, Minecraft paired baseline)" : " (Minecraft read and write side)"));
        row(sb, "Source", ArmResolver.sourceText(arm, s.setup().armRequested(), s.setup().coreInstalled()));
        if (arm == Arm.CORE) row(sb, "Minecraft baseline sampling", BaselineSampler.policyText() + "; " + s.baselineSamples() + " baseline reads, " + baselinePassed(s) + " successful");
        row(sb, "Side / dimension", s.setup().side() + " / " + s.setup().dimension());
        row(sb, "Completed / total ops", l.completed() + " / " + l.total() + (s.stoppedEarly() ? " (stopped early)" : ""));
        row(sb, "Wall duration", String.format(Locale.ROOT, "%.1f s, of which drain %.1f s", s.wallMillis() / 1000.0, s.drainMillis() / 1000.0));
        row(sb, "Ops per second cap", s.opsPerSecondCap() > 0L ? s.opsPerSecondCap() + " ops/s" : "none: as fast as the time budget allows");
        row(sb, "Ops per second average / peak in any 1 s window", String.format(Locale.ROOT, "%.0f / %d", s.opsPerSecondAverage(), s.opsPerSecondPeak()));
        row(sb, "Headline: ops per MSPT-ms", String.format(Locale.ROOT, "%.2f", s.opsPerMsptMilli()));
        row(sb, "MSPT mean / p95 / p99 / max", String.format(Locale.ROOT, "%.2f / %.2f / %.2f / %.2f ms", s.meanTickMillis(), tick.p95TickMillis(), tick.p99TickMillis(), tick.maxTickMillis()));
        row(sb, "Test mod time budget per server tick", String.format(Locale.ROOT, "%.1f ms", s.budget().limitNanos() / 1_000_000.0));
        row(sb, "Harness time per tick mean / p50 / p99 / max", String.format(Locale.ROOT, "%.2f / %s / %s / %.2f ms", s.harnessMeanMillis(), nanos(s.harnessTick().percentile(0.5)), nanos(s.harnessTick().percentile(0.99)), s.harnessMaxMillis()));
        row(sb, "Server-thread allocation per tick (mean / max)", s.tickAllocMeanKilobytes() < 0.0 ? "not measured" : String.format(Locale.ROOT, "%.1f / %.1f KB", s.tickAllocMeanKilobytes(), s.tickAllocMaxKilobytes()));
        row(sb, "Thrash workers / seed", s.setup().thrashWorkers() + " / " + s.setup().seed());
        sb.append(time.markdown());

        sb.append("\n## Outcomes\n\n| Outcome | Ops |\n|---|---|\n");
        for (Outcome outcome : Outcome.values()) if (outcome.terminal()) sb.append("| ").append(outcome).append(" | ").append(l.outcome(outcome)).append(" |\n");
        sb.append("\nIn-flight references peaked at ").append(l.inFlightPeak()).append("; at the end ").append(l.inFlight()).append("; drain timeouts ").append(l.drainTimeouts()).append(".\n");

        sb.append("\n## WorldData Core vs Minecraft (paired call on the same coordinates)\n\n");
        sb.append("| Read | Minecraft call | Minecraft p50 | Minecraft p99 | WorldData Core call | Core p50 | Core p99 | Ratio p50 (Core / Minecraft) |\n|---|---|---|---|---|---|---|---|\n");
        for (ReadAction action : ReadAction.values()) {
            if (!action.baselineSampled()) continue;
            CallSnapshot v = s.recorder().snapshot(action.vanilla());
            CallSnapshot a = s.recorder().snapshot(action.core());
            if (v.count() == 0L && a.count() == 0L) continue;
            String ratio = v.p50Nanos() > 0L && a.count() > 0L ? String.format(Locale.ROOT, "%.2fx", a.p50Nanos() / (double) v.p50Nanos()) : "n/a";
            sb.append("| ").append(action).append(" | ").append(action.vanilla().label()).append(" | ").append(v.count() == 0L ? "n/a" : nanos(v.p50Nanos())).append(" | ")
                    .append(v.count() == 0L ? "n/a" : nanos(v.p99Nanos())).append(" | ").append(action.core().label()).append(" | ").append(a.count() == 0L ? "n/a" : nanos(a.p50Nanos()))
                    .append(" | ").append(a.count() == 0L ? "n/a" : nanos(a.p99Nanos())).append(" | ").append(ratio).append(" |\n");
        }

        sb.append("\n## Speed / Latency (wall nanoseconds around the public call only)\n\n");
        sb.append("| Call | Side | Total | Passed | Failed | Waiting | Timeout | Unavailable | Unloaded | p50 | p99 |\n|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (CallKind kind : CallKind.values()) {
            if (!armPresent(arm, kind)) continue;
            CallSnapshot c = s.recorder().snapshot(kind);
            if (c.count() == 0L) continue;
            sb.append("| ").append(kind.label()).append(" | ").append(kind.arm().displayName()).append(" | ").append(c.count()).append(" | ").append(c.passed()).append(" | ")
                    .append(c.failed()).append(" | ").append(c.waiting()).append(" | ").append(c.timeout()).append(" | ").append(c.unavailable()).append(" | ").append(c.unloaded())
                    .append(" | ").append(nanos(c.p50Nanos())).append(" | ").append(nanos(c.p99Nanos())).append(" |\n");
        }

        WaveRunner waves = s.waves();
        sb.append("\n## Minecraft force-load waves (Core is never asked to load)\n\n| Item | Value |\n|---|---|\n");
        row(sb, "Core force-load API calls", String.valueOf(coreForceLoadCalls(s)));
        row(sb, "Server-thread wait for loads", "none: tickets are added and removed, readiness is polled once per tick, nothing parks or blocks the server thread");
        row(sb, "Waves planned / started / finished / released", s.schedule().count() + " / " + waves.started() + " / " + waves.finished() + " / " + waves.released());
        row(sb, "Chunks requested / unique chunks", waves.chunksRequested() + " / " + s.loader().uniqueChunks());
        row(sb, "Load groups loaded / failed / timed out", waves.groupsLoaded() + " / " + waves.groupsFailed() + " / " + waves.groupsTimedOut());
        row(sb, "Read pending to terminal wait p50 / p99", nanos(s.pendingWait().percentile(0.5)) + " / " + nanos(s.pendingWait().percentile(0.99)));
        row(sb, "Reload of reused chunks p50, Minecraft against WorldData Core", restore);

        MutationEngine m = s.mutation();
        sb.append("\n## Block writes and parallel reads\n\n| Item | Value |\n|---|---|\n");
        row(sb, "Block writes (failed) / positions restored after the run", m.writes() + " (" + m.writeFailures() + ") / " + s.restoredPositions());
        row(sb, "Parallel read bursts (" + s.setup().thrashWorkers() + " threads) / mean parallel-to-single ratio / disagreements between threads", s.thrash().bursts() + " / " + String.format(Locale.ROOT, "%.3f", s.thrash().meanRatio()) + " / " + s.thrash().disagreements());

        sb.append("\n").append(ReportNotes.PACK_DETAIL).append("\n");
        return sb.toString();
    }

    private static void row(StringBuilder sb, String key, String value) {
        sb.append("| ").append(key).append(" | ").append(value).append(" |\n");
    }

    private static String wideCsv(PackSession s, Arm arm, TimeComparison time) {
        OpsLedger l = s.ledger();
        PerformanceStats tick = s.tickStats();
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        add(names, values, "run_id", s.setup().runId());
        add(names, values, "arm", arm.name());
        add(names, values, "total_ops", String.valueOf(l.total()));
        add(names, values, "completed_ops", String.valueOf(l.completed()));
        add(names, values, "wall_ms", String.valueOf(s.wallMillis()));
        add(names, values, "time_to_finish_ms", String.valueOf(timeToFinishMillis(s)));
        add(names, values, "minecraft_time_to_finish_ms", String.valueOf(time.minecraftMillis()));
        add(names, values, "worlddata_core_time_to_finish_ms", String.valueOf(time.coreMillis()));
        add(names, values, "ops_per_mspt_ms", String.format(Locale.ROOT, "%.4f", s.opsPerMsptMilli()));
        add(names, values, "ops_per_second_cap", String.valueOf(s.opsPerSecondCap()));
        add(names, values, "ops_per_second_avg", String.format(Locale.ROOT, "%.2f", s.opsPerSecondAverage()));
        add(names, values, "ops_per_second_peak", String.valueOf(s.opsPerSecondPeak()));
        add(names, values, "mspt_mean_ms", String.format(Locale.ROOT, "%.4f", s.meanTickMillis()));
        add(names, values, "mspt_p99_ms", String.format(Locale.ROOT, "%.4f", tick.p99TickMillis()));
        add(names, values, "thrash_mean_ratio", String.format(Locale.ROOT, "%.4f", s.thrash().meanRatio()));
        add(names, values, "core_force_load_api_calls", String.valueOf(coreForceLoadCalls(s)));
        add(names, values, "minecraft_ticket_calls", String.valueOf(s.forceLoadCalls()));
        add(names, values, "minecraft_waves_started", String.valueOf(s.waves().started()));
        add(names, values, "minecraft_unique_chunks", String.valueOf(s.loader().uniqueChunks()));
        for (CallKind kind : CallKind.values()) {
            if (!armPresent(arm, kind)) continue;
            CallSnapshot c = s.recorder().snapshot(kind);
            String id = "latency_" + kind.csvId();
            add(names, values, id + "_count", String.valueOf(c.count()));
            add(names, values, id + "_p50_ns", String.valueOf(c.p50Nanos()));
            add(names, values, id + "_p95_ns", String.valueOf(c.p95Nanos()));
            add(names, values, id + "_p99_ns", String.valueOf(c.p99Nanos()));
            add(names, values, id + "_mean_ns", String.valueOf(Math.round(c.meanNanos())));
            add(names, values, id + "_max_ns", String.valueOf(c.maxNanos()));
        }
        return String.join(",", names) + "\n" + String.join(",", values) + "\n";
    }

    private static void add(List<String> names, List<String> values, String name, String value) {
        names.add(name);
        values.add(value);
    }

    private static String latencyCsv(PackSession s, Arm arm) {
        StringBuilder sb = new StringBuilder("call_id,call,side,family,total,passed,failed,waiting,timeout,unavailable,unloaded,min_ns,p50_ns,p95_ns,p99_ns,mean_ns,max_ns,fail_rate,unavailable_rate\n");
        for (CallKind kind : CallKind.values()) {
            if (!armPresent(arm, kind)) continue;
            CallSnapshot c = s.recorder().snapshot(kind);
            sb.append(kind.csvId()).append(',').append('"').append(kind.label()).append('"').append(',').append(kind.arm().displayName()).append(',').append(kind.family()).append(',')
                    .append(c.count()).append(',').append(c.passed()).append(',').append(c.failed()).append(',').append(c.waiting()).append(',').append(c.timeout()).append(',')
                    .append(c.unavailable()).append(',').append(c.unloaded()).append(',').append(c.minNanos()).append(',').append(c.p50Nanos()).append(',').append(c.p95Nanos()).append(',').append(c.p99Nanos()).append(',')
                    .append(Math.round(c.meanNanos())).append(',').append(c.maxNanos()).append(',')
                    .append(String.format(Locale.ROOT, "%.5f,%.5f", c.failRate(), c.unavailableRate())).append('\n');
        }
        return sb.toString();
    }

    private static String curveCsv(PackSession s) {
        StringBuilder sb = new StringBuilder("tick,completed_ops,issued_ops,in_flight,tick_micros,batch_size,batch_no,harness_micros\n");
        for (long[] p : s.curve()) {
            sb.append(p[0]).append(',').append(p[1]).append(',').append(p[2]).append(',').append(p[3]).append(',').append(p[4]).append(',').append(p[5]).append(',').append(p[6]).append(',').append(p[7]).append('\n');
        }
        return sb.toString();
    }

    public static String nanos(long n) {
        if (n >= 1_000_000L) return String.format(Locale.ROOT, "%.2f ms", n / 1_000_000.0);
        if (n >= 1_000L) return String.format(Locale.ROOT, "%.1f us", n / 1_000.0);
        return n + " ns";
    }
}
