package net.worlddata.benchmark.common.mode;

import net.worlddata.benchmark.common.pack.Arm;
import net.worlddata.benchmark.common.pack.Outcome;
import net.worlddata.benchmark.common.pack.ReportNotes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static net.worlddata.benchmark.common.mode.ModeReportSupport.f;
import static net.worlddata.benchmark.common.mode.ModeReportSupport.row;

final class RampReport {

    record StepStats(int step, long rate, int from, int to, int ticks, long intended, long issued, long dropped, long pass, double wallSeconds, double achieved,
                     Dist mspt, double harnessMeanMs, int overTicks, long gcMillis) {
    }

    private record Analysis(TreeMap<Integer, StepStats> steps, StepStats lastOk, StepStats failed, double maxAchieved, Dist winMspt, RampSession.Stop stop) {
    }

    private RampReport() {}

    private static Analysis analyze(RampSession s) {
        RampConfig c = s.config();
        Columns t = s.ticks();
        int n = t.size();
        TreeMap<Integer, StepStats> steps = steps(s);
        RampSession.Stop stop = s.stopReason() == null ? RampSession.Stop.STOPPED : s.stopReason();
        StepStats failed = null;
        StepStats lastOk = null;
        if (stop == RampSession.Stop.SUSTAINED_OVER && s.finalStreakStartRow() >= 0) {
            int failStep = (int) t.get(RampSession.C_STEP, s.finalStreakStartRow());
            failed = steps.get(failStep);
            Map.Entry<Integer, StepStats> before = steps.lowerEntry(failStep);
            lastOk = before == null ? null : before.getValue();
        } else if (n > 0) {
            lastOk = steps.lastEntry().getValue();
        }
        double maxAchieved = 0.0;
        for (StepStats st : steps.values()) maxAchieved = Math.max(maxAchieved, st.achieved());
        Dist win = Dist.of(t.millis(RampSession.C_MSPT, Math.max(0, n - c.holdTicks()), n));
        return new Analysis(steps, lastOk, failed, maxAchieved, win, stop);
    }

    static Map<String, String> storeValues(RampSession s) {
        Analysis a = analyze(s);
        RampConfig c = s.config();
        Map<String, String> m = new LinkedHashMap<>();
        if (a.steps().isEmpty()) return m;
        m.put("arm", s.arm().name());
        m.put("runId", s.setup().runId());
        m.put("seed", String.valueOf(s.setup().seed()));
        m.put("dimension", s.setup().dimension());
        m.put("targetMs", String.valueOf(c.targetMs()));
        m.put("overMs", String.valueOf(c.overMs()));
        m.put("holdTicks", String.valueOf(c.holdTicks()));
        m.put("startOps", String.valueOf(c.startOps()));
        m.put("stepOps", String.valueOf(c.stepOps()));
        m.put("stepSeconds", String.valueOf(c.stepSeconds()));
        m.put("tickIssueMs", String.valueOf(c.tickIssueMs()));
        m.put("msptStayedAbove", String.valueOf(a.stop() == RampSession.Stop.SUSTAINED_OVER));
        if (a.lastOk() != null) {
            m.put("lastOkRate", String.valueOf(a.lastOk().rate()));
            m.put("lastOkAchieved", f(a.lastOk().achieved(), 1));
        }
        if (a.failed() != null) {
            m.put("failRate", String.valueOf(a.failed().rate()));
            m.put("failAchieved", f(a.failed().achieved(), 1));
        }
        m.put("maxAchieved", f(a.maxAchieved(), 1));
        m.put("finalWindowMsptMean", f(a.winMspt().mean(), 3));
        return m;
    }

    private static List<String> comparison(RampSession s) {
        List<String> lines = new ArrayList<>();
        Map<String, String> other = s.other();
        if (other.isEmpty() || s.ticks().size() == 0) return lines;
        Map<String, String> mine = storeValues(s);
        if (mine.isEmpty()) return lines;
        boolean isCore = s.arm() == Arm.CORE;
        Map<String, String> core = isCore ? mine : other;
        Map<String, String> mc = isCore ? other : mine;
        lines.add("| Measure | WorldData Core | Minecraft | Core / Minecraft |");
        lines.add("|---|---|---|---|");
        for (String[] m : new String[][] {{"Last step before MSPT stayed above the stop level, achieved ops/s", "lastOkAchieved"}, {"Same step, intended ops/s", "lastOkRate"},
                {"Step where MSPT started to stay above it, intended ops/s", "failRate"}, {"Highest achieved ops/s in any step", "maxAchieved"}, {"MSPT mean in the final window (ms)", "finalWindowMsptMean"}}) {
            lines.add("| " + m[0] + " | " + core.getOrDefault(m[1], "n/a") + " | " + mc.getOrDefault(m[1], "n/a") + " | " + ModeCompare.ratio(ModeCompare.number(core, m[1]), ModeCompare.number(mc, m[1])) + " |");
        }
        lines.add("");
        lines.add("Core run " + core.getOrDefault("runId", "n/a") + ", Minecraft run " + mc.getOrDefault("runId", "n/a") + ".");
        if (!"true".equals(core.get("msptStayedAbove")) || !"true".equals(mc.get("msptStayedAbove"))) {
            lines.add("MSPT did not stay above the stop level in " + (!"true".equals(core.get("msptStayedAbove")) && !"true".equals(mc.get("msptStayedAbove")) ? "either run" : !"true".equals(core.get("msptStayedAbove")) ? "the Core run" : "the Minecraft run")
                    + ", so for that run the ops/s numbers are the highest rate reached before the run ended, not a point where it stopped coping.");
        }
        List<String> diff = ModeCompare.differences(mine, other, "seed", "dimension", "targetMs", "overMs", "holdTicks", "startOps", "stepOps", "stepSeconds", "tickIssueMs");
        lines.add(diff.isEmpty() ? "Both runs used the same seed, dimension and ramp settings." : "The two runs did not use the same settings (" + String.join("; ", diff) + "), so the ratio compares different set-ups.");
        return lines;
    }

    static ModeReport build(RampSession s) {
        RampConfig c = s.config();
        Columns t = s.ticks();
        int n = t.size();
        Arm arm = s.arm();
        long limitNanos = c.limitNanos();
        TreeMap<Integer, StepStats> steps = steps(s);
        int winFrom = Math.max(0, n - c.holdTicks());
        Dist allMspt = Dist.of(t.millis(RampSession.C_MSPT, 0, n));
        Dist winMspt = Dist.of(t.millis(RampSession.C_MSPT, winFrom, n));
        Dist winHarness = Dist.of(t.millis(RampSession.C_HARNESS, winFrom, n));
        Dist allHarness = Dist.of(t.millis(RampSession.C_HARNESS, 0, n));
        int tailOver = 0;
        for (int i = n - 1; i >= 0 && t.get(RampSession.C_MSPT, i) > limitNanos; i--) tailOver++;
        RampSession.Stop stop = s.stopReason() == null ? RampSession.Stop.STOPPED : s.stopReason();
        StepStats failed = null;
        StepStats lastOk = null;
        if (stop == RampSession.Stop.SUSTAINED_OVER && s.finalStreakStartRow() >= 0) {
            int failStep = (int) t.get(RampSession.C_STEP, s.finalStreakStartRow());
            failed = steps.get(failStep);
            Map.Entry<Integer, StepStats> before = steps.lowerEntry(failStep);
            lastOk = before == null ? null : before.getValue();
        } else if (n > 0) {
            lastOk = steps.lastEntry().getValue();
        }
        double wallSeconds = s.wallNanos() / 1_000_000_000.0;
        long issuedTotal = t.sum(RampSession.C_ISSUED, 0, n);
        long intendedTotal = t.sum(RampSession.C_INTENDED, 0, n);
        long droppedTotal = t.sum(RampSession.C_DROPPED, 0, n);
        long passImmediate = t.sum(RampSession.C_PASS, 0, n);
        double maxAchieved = 0.0;
        for (StepStats st : steps.values()) maxAchieved = Math.max(maxAchieved, st.achieved());

        String headline = headline(c, stop, lastOk, failed, steps);
        List<String> causes = causes(s, winFrom, n, winMspt, winHarness);

        StringBuilder md = new StringBuilder();
        md.append("# WorldData Core test: throttle ramp (max sustained ops/s) ").append(s.setup().runId()).append("\n\n");
        md.append("> ").append(ReportNotes.HARNESS).append("\n\n");
        md.append("This report shows how many ops per second the server handled while MSPT stayed under the stop level, and where it stopped being able to. It is not an efficiency (ops per ms) measurement and it has no gates.\n\n");
        md.append("| Item | Value |\n|---|---|\n");
        ModeReportSupport.header(md, s.setup(), arm, "throttle ramp (command: ramp / throttle)");
        row(md, "Target MSPT / over margin / stop level", f(c.targetMs(), 1) + " ms / " + f(c.overMs(), 1) + " ms / " + f(c.limitMs(), 1) + " ms (the run stops only when MSPT is above the stop level)");
        row(md, "Hold window", c.holdTicks() + " consecutive ticks above the stop level; shorter runs above it are counted as spikes and never stop the run");
        row(md, "Ramp schedule", "starts at " + c.startOps() + " ops/s, +" + c.stepOps() + " ops/s every " + f(c.stepSeconds(), 2) + " s of wall clock, ceiling " + c.maxOps() + " ops/s, time limit " + f(c.maxMinutes(), 1) + " min");
        row(md, "Hard tick ceiling for issuing", c.tickIssueMs() > 0.0 ? f(c.tickIssueMs(), 2) + " ms from the start of the tick; intended ops beyond it are dropped and counted"
                : "none: every intended op of a tick is issued, however long it takes (50 ms is only a reference when reading MSPT)");
        row(md, "Thrash", ModeReportSupport.thrashText(c.thrashWorkers()));
        row(md, "Pack baseline MSPT (author provided)", c.packBaselineMs() >= 0.0 ? f(c.packBaselineMs(), 1) + " ms idle" : "not provided");
        if (arm == Arm.CORE) row(md, "Core budget (author provided)", c.coreBudgetMs() >= 0.0 ? f(c.coreBudgetMs(), 1) + " ms, recorded as stated; the test mod cannot read or set it through the public Core API (set it with /worlddata budget fixed <ms>)" : "not provided");
        if (!c.note().isEmpty()) row(md, "Note", c.note());
        md.append("\n## Result\n\n| Item | Value |\n|---|---|\n");
        row(md, "Stop reason", stop.text());
        row(md, "Headline", headline);
        row(md, "Last step before MSPT stayed above the stop level", lastOk == null ? "none" : stepText(lastOk));
        row(md, "Step in which MSPT started to stay above the stop level", failed == null ? "none" : stepText(failed));
        row(md, "Consecutive ticks above the stop level at the end", tailOver + " (hold window " + c.holdTicks() + ")");
        row(md, "Ticks above the stop level in the run / spike runs shorter than the hold window / longest run", s.overTicks() + " / " + s.spikeRuns() + " / " + s.longestRun());
        row(md, "Highest achieved ops/s in any step", f(maxAchieved, 0));
        row(md, "Ticks run / wall duration", n + " ticks / " + f(wallSeconds, 1) + " s");
        row(md, "Ops intended / issued / dropped by the tick ceiling", intendedTotal + " / " + issuedTotal + " / " + droppedTotal);
        md.append("\n## MSPT (ms, measured from the start to the end of each server tick, so it includes the test mod's own issue time)\n\n");
        md.append(ModeReportSupport.distHeader());
        md.append(ModeReportSupport.distLine("MSPT, whole run", allMspt, 2, "ms"));
        md.append(ModeReportSupport.distLine("MSPT, final hold window", winMspt, 2, "ms"));
        md.append(ModeReportSupport.distLine("Test mod issue time per tick, whole run", allHarness, 3, "ms"));
        md.append(ModeReportSupport.distLine("Test mod issue time per tick, final hold window", winHarness, 3, "ms"));
        md.append("\n## Outcomes (ops classified as before)\n\n| Outcome | Answered at once | Settled later from pending | Total |\n|---|---|---|---|\n");
        long[] now = s.immediateOutcomes();
        long[] later = s.settledOutcomes();
        for (Outcome o : Outcome.values()) {
            if (!o.terminal()) continue;
            md.append("| ").append(o).append(" | ").append(now[o.ordinal()]).append(" | ").append(later[o.ordinal()]).append(" | ").append(now[o.ordinal()] + later[o.ordinal()]).append(" |\n");
        }
        md.append("\nPending references issued ").append(s.pendingIssued()).append(", still waiting at the end ").append(s.pendingLeft()).append(".\n");
        md.append("\n## Where the tick time went (measured, no thresholds)\n\n");
        for (String cause : causes) md.append("- ").append(cause).append("\n");
        md.append("\n## Ops per second timeline (one row per step)\n\n");
        md.append("| Step | Intended ops/s | Ticks | Issued | Dropped | Achieved ops/s | MSPT mean | MSPT p95 | MSPT max | Issue ms per tick | Ticks above stop level |\n|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (StepStats st : pick(steps)) {
            md.append("| ").append(st.step()).append(" | ").append(st.rate()).append(" | ").append(st.ticks()).append(" | ").append(st.issued()).append(" | ").append(st.dropped()).append(" | ")
                    .append(f(st.achieved(), 0)).append(" | ").append(f(st.mspt().mean(), 2)).append(" | ").append(f(st.mspt().p95(), 2)).append(" | ").append(f(st.mspt().max(), 2)).append(" | ")
                    .append(f(st.harnessMeanMs(), 3)).append(" | ").append(st.overTicks()).append(" |\n");
        }
        if (pick(steps).size() < steps.size()) md.append("\nRows were left out of this table; timeline.csv has every step and ticks.csv every tick.\n");
        ModeReportSupport.thrashSection(md, s.thrash());
        List<String> comparison = comparison(s);
        if (!comparison.isEmpty()) {
            md.append("\n## Core vs Minecraft (this run against the last run of the other arm)\n\n");
            for (String line : comparison) md.append(line).append("\n");
        }
        md.append("\n## Call latency (wall time around the public call, this arm only)\n\n");
        ModeReportSupport.latencyTable(md, s.setup().recorder(), arm);
        ModeReportSupport.methodNotes(md);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("mode", "ramp");
        json.put("runId", s.setup().runId());
        json.put("note", ReportNotes.HARNESS);
        json.put("resultChecksum", s.checksum());
        json.put("arm", arm.name());
        json.put("seed", s.setup().seed());
        json.put("dimension", s.setup().dimension());
        json.put("side", s.setup().side());
        json.put("environment", ModeReportSupport.environmentJson(s.setup()));
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("targetMs", c.targetMs());
        cfg.put("overMs", c.overMs());
        cfg.put("stopLevelMs", c.limitMs());
        cfg.put("holdTicks", c.holdTicks());
        cfg.put("startOps", c.startOps());
        cfg.put("stepOps", c.stepOps());
        cfg.put("stepSeconds", c.stepSeconds());
        cfg.put("tickIssueMs", c.tickIssueMs());
        cfg.put("maxOps", c.maxOps());
        cfg.put("maxMinutes", c.maxMinutes());
        cfg.put("thrashWorkers", c.thrashWorkers());
        cfg.put("packBaselineMs", c.packBaselineMs() >= 0.0 ? c.packBaselineMs() : null);
        cfg.put("coreBudgetMsAsStated", arm == Arm.CORE && c.coreBudgetMs() >= 0.0 ? c.coreBudgetMs() : null);
        json.put("config", cfg);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stopReason", stop.name());
        result.put("msptStayedAboveStopLevel", stop == RampSession.Stop.SUSTAINED_OVER);
        result.put("headline", headline);
        result.put("lastOkStep", lastOk == null ? null : stepJson(lastOk));
        result.put("failedStep", failed == null ? null : stepJson(failed));
        result.put("consecutiveTicksAboveStopLevelAtEnd", tailOver);
        result.put("ticksAboveStopLevel", s.overTicks());
        result.put("spikeRuns", s.spikeRuns());
        result.put("longestRunAboveStopLevel", s.longestRun());
        result.put("maxAchievedOpsPerSecond", Math.round(maxAchieved));
        result.put("ticks", n);
        result.put("wallSeconds", ModeReportSupport.round(wallSeconds));
        result.put("opsIntended", intendedTotal);
        result.put("opsIssued", issuedTotal);
        result.put("opsDropped", droppedTotal);
        result.put("opsPassedAtOnce", passImmediate);
        json.put("result", result);
        Map<String, Object> mspt = new LinkedHashMap<>();
        mspt.put("wholeRunMs", ModeReportSupport.distJson(allMspt));
        mspt.put("finalWindowMs", ModeReportSupport.distJson(winMspt));
        mspt.put("issueTimeWholeRunMs", ModeReportSupport.distJson(allHarness));
        mspt.put("issueTimeFinalWindowMs", ModeReportSupport.distJson(winHarness));
        json.put("mspt", mspt);
        Map<String, Object> outcomes = new LinkedHashMap<>();
        for (Outcome o : Outcome.values()) {
            if (!o.terminal()) continue;
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("atOnce", now[o.ordinal()]);
            one.put("settledLater", later[o.ordinal()]);
            outcomes.put(o.name(), one);
        }
        outcomes.put("pendingIssued", s.pendingIssued());
        outcomes.put("pendingLeft", s.pendingLeft());
        json.put("outcomes", outcomes);
        json.put("causeNotes", causes);
        json.put("comparison", comparison);
        List<Object> stepList = new ArrayList<>();
        for (StepStats st : steps.values()) stepList.add(stepJson(st));
        json.put("steps", stepList);

        Map<String, String> files = new LinkedHashMap<>();
        files.put("timeline.csv", timelineCsv(steps));
        files.put("ticks.csv", ticksCsv(t));
        files.put("latency.csv", ModeReportSupport.latencyCsv(s.setup().recorder(), arm));

        String summary = summary(arm, c, stop, lastOk, failed, n, wallSeconds);
        return new ModeReport(s.setup().runId(), "ramp", arm, json, md.toString(), files, summary);
    }

    private static TreeMap<Integer, StepStats> steps(RampSession s) {
        Columns t = s.ticks();
        RampConfig c = s.config();
        TreeMap<Integer, StepStats> map = new TreeMap<>();
        int n = t.size();
        int from = 0;
        while (from < n) {
            int step = (int) t.get(RampSession.C_STEP, from);
            int to = from;
            while (to < n && (int) t.get(RampSession.C_STEP, to) == step) to++;
            long startRel = t.get(RampSession.C_START, from);
            long endRel = to < n ? t.get(RampSession.C_START, to) : t.get(RampSession.C_START, to - 1) + t.get(RampSession.C_MSPT, to - 1);
            double wall = Math.max(endRel - startRel, t.get(RampSession.C_MSPT, from)) / 1_000_000_000.0;
            long issued = t.sum(RampSession.C_ISSUED, from, to);
            int over = 0;
            for (int i = from; i < to; i++) if (t.get(RampSession.C_MSPT, i) > c.limitNanos()) over++;
            long gcBefore = from == 0 ? t.get(RampSession.C_GC, 0) : t.get(RampSession.C_GC, from - 1);
            long gcDelta = Math.max(0L, t.get(RampSession.C_GC, to - 1) - gcBefore);
            Dist mspt = Dist.of(t.millis(RampSession.C_MSPT, from, to));
            double harness = Dist.of(t.millis(RampSession.C_HARNESS, from, to)).mean();
            map.put(step, new StepStats(step, c.rateAtStep(step), from, to, to - from, t.sum(RampSession.C_INTENDED, from, to), issued, t.sum(RampSession.C_DROPPED, from, to),
                    t.sum(RampSession.C_PASS, from, to), wall, wall > 0.0 ? issued / wall : 0.0, mspt, harness, over, gcDelta));
            from = to;
        }
        return map;
    }

    private static List<StepStats> pick(TreeMap<Integer, StepStats> steps) {
        List<StepStats> all = new ArrayList<>(steps.values());
        if (all.size() <= 100) return all;
        List<StepStats> kept = new ArrayList<>();
        int total = all.size();
        int stride = Math.max(1, (total - 50) / 40);
        for (int i = 0; i < total; i++) {
            if (i < 10 || i >= total - 40 || i % stride == 0) kept.add(all.get(i));
        }
        return kept;
    }

    private static String stepText(StepStats st) {
        return String.format(Locale.ROOT, "step %d: %d ops/s intended, %.0f ops/s achieved, MSPT mean %.2f / p95 %.2f / max %.2f ms, %d ticks, %d above the stop level",
                st.step(), st.rate(), st.achieved(), st.mspt().mean(), st.mspt().p95(), st.mspt().max(), st.ticks(), st.overTicks());
    }

    private static Map<String, Object> stepJson(StepStats st) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("step", st.step());
        m.put("intendedOpsPerSecond", st.rate());
        m.put("ticks", st.ticks());
        m.put("opsIntended", st.intended());
        m.put("opsIssued", st.issued());
        m.put("opsDropped", st.dropped());
        m.put("opsPassedAtOnce", st.pass());
        m.put("wallSeconds", ModeReportSupport.round(st.wallSeconds()));
        m.put("achievedOpsPerSecond", Math.round(st.achieved()));
        m.put("msptMeanMs", ModeReportSupport.round(st.mspt().mean()));
        m.put("msptP95Ms", ModeReportSupport.round(st.mspt().p95()));
        m.put("msptP99Ms", ModeReportSupport.round(st.mspt().p99()));
        m.put("msptMaxMs", ModeReportSupport.round(st.mspt().max()));
        m.put("issueMsPerTick", ModeReportSupport.round(st.harnessMeanMs()));
        m.put("ticksAboveStopLevel", st.overTicks());
        m.put("gcMillis", st.gcMillis());
        return m;
    }

    private static String headline(RampConfig c, RampSession.Stop stop, StepStats lastOk, StepStats failed, TreeMap<Integer, StepStats> steps) {
        if (steps.isEmpty()) return "no tick ran, nothing was measured";
        if (stop == RampSession.Stop.SUSTAINED_OVER) {
            String ok = lastOk == null ? "MSPT was already above the stop level in the first step" : String.format(Locale.ROOT, "%d ops/s intended (%.0f achieved) was the last step before MSPT stayed above the stop level", lastOk.rate(), lastOk.achieved());
            String bad = failed == null ? "" : String.format(Locale.ROOT, "; MSPT started to stay above it at %d ops/s intended (%.0f achieved)", failed.rate(), failed.achieved());
            return ok + bad + String.format(Locale.ROOT, " (stop level %.1f ms)", c.limitMs());
        }
        StepStats last = steps.lastEntry().getValue();
        return String.format(Locale.ROOT, "MSPT did not stay above %.1f ms for %d ticks in a row; the run ended at %d ops/s intended (%.0f achieved)", c.limitMs(), c.holdTicks(), last.rate(), last.achieved());
    }

    private static List<String> causes(RampSession s, int winFrom, int n, Dist winMspt, Dist winHarness) {
        List<String> notes = new ArrayList<>();
        if (n == 0) {
            notes.add("No tick ran.");
            return notes;
        }
        Columns t = s.ticks();
        int ticks = n - winFrom;
        double msptMean = winMspt.mean();
        double issueMean = winHarness.mean();
        double rest = Math.max(0.0, msptMean - issueMean);
        long gcBefore = winFrom == 0 ? t.get(RampSession.C_GC, 0) : t.get(RampSession.C_GC, winFrom - 1);
        double gcPerTick = Math.max(0L, t.get(RampSession.C_GC, n - 1) - gcBefore) / (double) Math.max(1, ticks);
        long issued = t.sum(RampSession.C_ISSUED, winFrom, n);
        long pass = t.sum(RampSession.C_PASS, winFrom, n);
        notes.add(String.format(Locale.ROOT, "Last %d ticks: MSPT mean %.2f ms = test mod issue loop %.2f ms (%.0f %%) + rest of the server tick %.2f ms (%.0f %%).", ticks, msptMean, issueMean, share(issueMean, msptMean), rest, share(rest, msptMean)));
        notes.add(String.format(Locale.ROOT, "Largest measured part: %s.", issueMean >= rest ? "the issue loop (the ops themselves)" : "the rest of the server tick (vanilla tick, chunk work, other mods" + (s.arm() == Arm.CORE ? ", WorldData Core's own ingest" : "") + ")"));
        notes.add(String.format(Locale.ROOT, "Garbage collection during those ticks: %.2f ms per tick (already inside the tick time above).", gcPerTick));
        if (s.config().packBaselineMs() >= 0.0) {
            notes.add(String.format(Locale.ROOT, "Pack baseline given by the author: %.1f ms idle. MSPT in the last %d ticks is %.2f ms above it; the issue loop took %.2f ms per tick.", s.config().packBaselineMs(), ticks, msptMean - s.config().packBaselineMs(), issueMean));
        }
        if (issued > 0L) notes.add(String.format(Locale.ROOT, "%.1f %% of the ops issued in those ticks were answered PASS at once; the rest were pending, unloaded or unavailable (chunk readiness shows up here).", 100.0 * pass / issued));
        if (s.arm() == Arm.CORE) notes.add("The public Core API exposes no timers; read /worlddata budget (last tick used) or a Spark profile to see Core's own share of the rest of the tick.");
        if (s.config().tickIssueMs() > 0.0 && s.droppedTotal() > 0L) notes.add(s.droppedTotal() + " intended ops were dropped by the hard tick ceiling, so achieved rates are below intended rates by that amount.");
        return notes;
    }

    private static double share(double part, double whole) {
        return whole > 0.0 ? part * 100.0 / whole : 0.0;
    }

    private static String timelineCsv(TreeMap<Integer, StepStats> steps) {
        StringBuilder sb = new StringBuilder("step,intended_ops_per_s,ticks,ops_intended,ops_issued,ops_dropped,ops_pass_at_once,wall_s,achieved_ops_per_s,mspt_mean_ms,mspt_p95_ms,mspt_p99_ms,mspt_max_ms,issue_ms_per_tick,ticks_above_stop_level,gc_ms\n");
        for (StepStats st : steps.values()) {
            sb.append(st.step()).append(',').append(st.rate()).append(',').append(st.ticks()).append(',').append(st.intended()).append(',').append(st.issued()).append(',').append(st.dropped()).append(',')
                    .append(st.pass()).append(',').append(f(st.wallSeconds(), 3)).append(',').append(f(st.achieved(), 1)).append(',').append(f(st.mspt().mean(), 3)).append(',').append(f(st.mspt().p95(), 3)).append(',')
                    .append(f(st.mspt().p99(), 3)).append(',').append(f(st.mspt().max(), 3)).append(',').append(f(st.harnessMeanMs(), 4)).append(',').append(st.overTicks()).append(',').append(st.gcMillis()).append('\n');
        }
        return sb.toString();
    }

    private static String ticksCsv(Columns t) {
        StringBuilder sb = new StringBuilder("tick,start_s,step,intended_ops_per_s,ops_intended,ops_issued,ops_dropped,ops_pass_at_once,issue_ms,mspt_ms\n");
        for (int i = 0; i < t.size(); i++) {
            sb.append(i).append(',').append(f(t.get(RampSession.C_START, i) / 1_000_000_000.0, 3)).append(',').append(t.get(RampSession.C_STEP, i)).append(',').append(t.get(RampSession.C_RATE, i)).append(',')
                    .append(t.get(RampSession.C_INTENDED, i)).append(',').append(t.get(RampSession.C_ISSUED, i)).append(',').append(t.get(RampSession.C_DROPPED, i)).append(',').append(t.get(RampSession.C_PASS, i)).append(',')
                    .append(f(t.get(RampSession.C_HARNESS, i) / 1_000_000.0, 4)).append(',').append(f(t.get(RampSession.C_MSPT, i) / 1_000_000.0, 4)).append('\n');
        }
        return sb.toString();
    }

    private static String summary(Arm arm, RampConfig c, RampSession.Stop stop, StepStats lastOk, StepStats failed, int ticks, double wallSeconds) {
        String result;
        if (stop == RampSession.Stop.SUSTAINED_OVER) {
            result = lastOk == null ? "MSPT was above the stop level from the first step"
                    : String.format(Locale.ROOT, "last step before MSPT stayed above the stop level: %d ops/s intended (%.0f achieved)", lastOk.rate(), lastOk.achieved());
            if (failed != null) result += String.format(Locale.ROOT, ", it stayed above from %d ops/s", failed.rate());
        } else {
            result = "MSPT never stayed above the stop level (" + stop.name().toLowerCase(Locale.ROOT).replace('_', ' ') + ")";
        }
        return String.format(Locale.ROOT, "ATM_RAMP %s: %s; stop level %.1f ms for %d ticks, %d ticks in %.0f s", arm.displayName(), result, c.limitMs(), c.holdTicks(), ticks, wallSeconds);
    }
}
