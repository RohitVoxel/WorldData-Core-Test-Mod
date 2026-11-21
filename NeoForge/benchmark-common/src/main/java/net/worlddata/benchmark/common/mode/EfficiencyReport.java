package net.worlddata.benchmark.common.mode;

import net.worlddata.benchmark.common.pack.Arm;
import net.worlddata.benchmark.common.pack.Outcome;
import net.worlddata.benchmark.common.pack.ReadAction;
import net.worlddata.benchmark.common.pack.ReportNotes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static net.worlddata.benchmark.common.mode.ModeReportSupport.f;
import static net.worlddata.benchmark.common.mode.ModeReportSupport.row;

final class EfficiencyReport {

    private record Numbers(int ticks, double[] opsPerMs, double[] answeredPerMs, double[] passPerTick, double[] opsPerTick, double[] windowMs, long totalPass, long totalOps, long totalPending,
                           long totalWindowNanos, double aggregate, double answeredAggregate) {
    }

    private EfficiencyReport() {}

    private static Numbers numbers(EfficiencySession s) {
        Columns t = s.ticks();
        int n = t.size();
        double[] perMs = new double[n];
        double[] answered = new double[n];
        double[] passTick = new double[n];
        double[] opsTick = new double[n];
        double[] window = new double[n];
        long pass = 0L;
        long ops = 0L;
        long pending = 0L;
        long nanos = 0L;
        for (int i = 0; i < n; i++) {
            long w = Math.max(1L, t.get(EfficiencySession.C_WINDOW, i));
            long o = t.get(EfficiencySession.C_OPS, i);
            long p = t.get(EfficiencySession.C_PASS, i);
            long q = t.get(EfficiencySession.C_PENDING, i);
            double ms = w / 1_000_000.0;
            perMs[i] = p / ms;
            answered[i] = (o - q) / ms;
            passTick[i] = p;
            opsTick[i] = o;
            window[i] = ms;
            pass += p;
            ops += o;
            pending += q;
            nanos += w;
        }
        double totalMs = nanos / 1_000_000.0;
        return new Numbers(n, perMs, answered, passTick, opsTick, window, pass, ops, pending, nanos, totalMs > 0.0 ? pass / totalMs : 0.0, totalMs > 0.0 ? (ops - pending) / totalMs : 0.0);
    }

    static Map<String, String> storeValues(EfficiencySession s) {
        Numbers x = numbers(s);
        Map<String, String> m = new LinkedHashMap<>();
        if (x.ticks() == 0) return m;
        Dist per = Dist.of(x.opsPerMs());
        Dist tick = Dist.of(x.passPerTick());
        m.put("arm", s.arm().name());
        m.put("runId", s.setup().runId());
        m.put("seed", String.valueOf(s.setup().seed()));
        m.put("dimension", s.setup().dimension());
        m.put("windowMs", String.valueOf(s.config().windowMs()));
        m.put("ticks", String.valueOf(x.ticks()));
        m.put("opsPerMs", f(x.aggregate(), 4));
        m.put("medianOpsPerMs", f(per.p50(), 4));
        m.put("p95OpsPerMs", f(per.p95(), 4));
        m.put("medianOpsPerTick", f(tick.p50(), 2));
        m.put("passRate", f(x.totalOps() == 0L ? 0.0 : 100.0 * x.totalPass() / x.totalOps(), 3));
        if (s.arm() == Arm.CORE) m.put("coreBudgetMs", f(s.config().coreBudgetMs(), 2));
        return m;
    }

    static ModeReport build(EfficiencySession s) {
        EfficiencyConfig c = s.config();
        Columns t = s.ticks();
        Arm arm = s.arm();
        Numbers x = numbers(s);
        int n = x.ticks();
        EfficiencySession.Stop stop = s.stopReason() == null ? EfficiencySession.Stop.STOPPED : s.stopReason();
        Dist perMs = Dist.of(x.opsPerMs());
        Dist answeredPerMs = Dist.of(x.answeredPerMs());
        Dist passPerTick = Dist.of(x.passPerTick());
        Dist opsPerTick = Dist.of(x.opsPerTick());
        Dist window = Dist.of(x.windowMs());
        Dist mspt = Dist.ofNanosAsMillis(longs(t, EfficiencySession.C_MSPT, n), 0, n);
        Dist issue = Dist.ofNanosAsMillis(longs(t, EfficiencySession.C_HARNESS, n), 0, n);
        double passRate = x.totalOps() == 0L ? 0.0 : 100.0 * x.totalPass() / x.totalOps();
        double answered = x.totalOps() - x.totalPending();
        double passOfAnswered = answered <= 0.0 ? 0.0 : 100.0 * x.totalPass() / answered;
        double overshoot = window.mean() - c.windowMs();
        String budgetText = budgetText(arm, c);

        StringBuilder md = new StringBuilder();
        md.append("# WorldData Core test: efficiency (ops per ms) ").append(s.setup().runId()).append("\n\n");
        md.append("> ").append(ReportNotes.HARNESS).append("\n\n");
        md.append("This is an efficiency measurement: how many ops each arm completes inside a fixed window of measured op time on the server thread. It is not a throttle limit (it does not look for the point where the server lags) and it has no gates.\n\n");
        md.append("| Item | Value |\n|---|---|\n");
        ModeReportSupport.header(md, s.setup(), arm, "efficiency (command: efficiency)");
        row(md, "Window", f(c.windowMs(), 3) + " ms of measured issue / op time per tick, ticks measured " + n + " of " + c.ticks() + " requested");
        row(md, "Warm-up", s.warmupUsed() + " ticks (not counted); hotspot clusters ready at the start of measuring " + (s.hotspotsReady() >= 0 ? s.hotspotsReady() + " of 6" : "n/a") + "; no pending reads in the last warm-up ticks: " + (s.warmupUsed() == 0 ? "n/a" : s.steadyReached() ? "yes" : "no"));
        row(md, "Core budget", budgetText);
        row(md, "Safety stop", "stop if the MSPT mean over " + c.holdTicks() + " ticks is above " + f(c.safetyMs(), 1) + " ms");
        row(md, "Thrash", ModeReportSupport.thrashText(c.thrashWorkers()));
        row(md, "Force-load waves / writes", "none / none");
        if (!c.note().isEmpty()) row(md, "Note", c.note());
        md.append("\n## Result\n\n| Item | Value |\n|---|---|\n");
        row(md, "Stop reason", stop.text());
        row(md, "Headline: useful ops (PASS) per ms of window time", f(x.aggregate(), 2) + " ops/ms over all measured windows");
        row(md, "Median ops/ms per tick window", f(perMs.p50(), 2));
        row(md, "Ops completed per window (median, PASS)", f(passPerTick.p50(), 1));
        row(md, "PASS rate inside windows", f(passRate, 2) + " % of issued ops (" + f(passOfAnswered, 2) + " % of the ops answered at once)");
        row(md, "Wall duration", f(s.wallNanos() / 1_000_000_000.0, 1) + " s, ticks measured " + n);
        md.append("\n## Ops per ms and ops per tick window\n\n");
        md.append(ModeReportSupport.distHeader());
        md.append(ModeReportSupport.distLine("Useful ops (PASS) per ms", perMs, 2, "ops/ms"));
        md.append(ModeReportSupport.distLine("All answered ops per ms (any terminal outcome)", answeredPerMs, 2, "ops/ms"));
        md.append(ModeReportSupport.distLine("Useful ops (PASS) per tick window", passPerTick, 1, "ops/tick"));
        md.append(ModeReportSupport.distLine("Ops issued per tick window (incl. pending)", opsPerTick, 1, "ops/tick"));
        md.append("\nOverall: ").append(f(x.aggregate(), 3)).append(" PASS ops/ms, ").append(f(x.answeredAggregate(), 3)).append(" answered ops/ms, window time summed ").append(f(x.totalWindowNanos() / 1_000_000.0, 1)).append(" ms.\n");
        md.append("\n## Window accuracy\n\n");
        md.append(ModeReportSupport.distHeader());
        md.append(ModeReportSupport.distLine("Measured window (target " + f(c.windowMs(), 3) + " ms)", window, 4, "ms"));
        md.append("\nMean overshoot past the target ").append(f(overshoot, 4)).append(" ms: the last op of a window ends after the deadline. ops/ms always uses the measured window, not the target. One clock read per op costs about ").append(f(s.clockReadNanos(), 1)).append(" ns and is inside the measured window, as is the call timing the readers do around each public call (two clock reads and a histogram update); both arms pay the same.\n");
        md.append("\n## Server context during the measured ticks\n\n");
        md.append(ModeReportSupport.distHeader());
        md.append(ModeReportSupport.distLine("MSPT", mspt, 2, "ms"));
        md.append(ModeReportSupport.distLine("Test mod time per tick (poll + window)", issue, 3, "ms"));
        md.append("\nGarbage collection during the measured ticks: ").append(s.gcMillis()).append(" ms in total.\n");
        ModeReportSupport.thrashSection(md, s.thrash());
        md.append("\n## Outcomes\n\n| Outcome | Answered at once | Settled later from pending | Total |\n|---|---|---|---|\n");
        long[] now = s.immediateOutcomes();
        long[] later = s.settledOutcomes();
        for (Outcome o : Outcome.values()) {
            if (!o.terminal()) continue;
            md.append("| ").append(o).append(" | ").append(now[o.ordinal()]).append(" | ").append(later[o.ordinal()]).append(" | ").append(now[o.ordinal()] + later[o.ordinal()]).append(" |\n");
        }
        md.append("\nPending references issued in the measured windows ").append(s.pendingIssued()).append(", still waiting at the end ").append(s.pendingLeft()).append(". Pending ops are not counted as completed in their window.\n");
        md.append("\n## By read action (time is the measured time between the end of one op and the end of the next)\n\n");
        md.append("| Read | Ops | PASS | Time in windows | Mean per op | PASS ops per ms of its own time | Share of window time |\n|---|---|---|---|---|---|---|\n");
        long totalNanos = 0L;
        for (long v : s.actionNanos()) totalNanos += v;
        List<Object> actions = new ArrayList<>();
        StringBuilder actionCsv = new StringBuilder("action,family,ops,pass,time_ns,mean_ns_per_op,pass_ops_per_ms,share_percent\n");
        for (ReadAction a : ReadAction.values()) {
            long ops = s.actionOps()[a.ordinal()];
            if (ops == 0L) continue;
            long pass = s.actionPass()[a.ordinal()];
            long nanos = s.actionNanos()[a.ordinal()];
            double perMsAction = nanos > 0L ? pass / (nanos / 1_000_000.0) : 0.0;
            double mean = nanos / (double) ops;
            double share = totalNanos > 0L ? 100.0 * nanos / totalNanos : 0.0;
            md.append("| ").append(a.name()).append(" | ").append(ops).append(" | ").append(pass).append(" | ").append(f(nanos / 1_000_000.0, 2)).append(" ms | ").append(f(mean / 1000.0, 2)).append(" us | ")
                    .append(f(perMsAction, 2)).append(" | ").append(f(share, 1)).append(" % |\n");
            actionCsv.append(a.name()).append(',').append(a.family()).append(',').append(ops).append(',').append(pass).append(',').append(nanos).append(',').append(Math.round(mean)).append(',')
                    .append(f(perMsAction, 3)).append(',').append(f(share, 2)).append('\n');
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("action", a.name());
            one.put("family", a.family().name());
            one.put("ops", ops);
            one.put("pass", pass);
            one.put("timeNs", nanos);
            one.put("meanNsPerOp", Math.round(mean));
            one.put("passOpsPerMs", ModeReportSupport.round(perMsAction));
            one.put("sharePercent", ModeReportSupport.round(share));
            actions.add(one);
        }
        List<String> comparison = comparison(s, x, perMs);
        if (!comparison.isEmpty()) {
            md.append("\n## Core vs Minecraft (this run against the last run of the other arm)\n\n");
            for (String line : comparison) md.append(line).append("\n");
        }
        md.append("\n## Call latency (wall time around the public call, this arm only)\n\n");
        ModeReportSupport.latencyTable(md, s.setup().recorder(), arm);
        ModeReportSupport.methodNotes(md);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("mode", "efficiency");
        json.put("runId", s.setup().runId());
        json.put("note", ReportNotes.HARNESS);
        json.put("resultChecksum", s.checksum());
        json.put("arm", arm.name());
        json.put("seed", s.setup().seed());
        json.put("dimension", s.setup().dimension());
        json.put("side", s.setup().side());
        json.put("environment", ModeReportSupport.environmentJson(s.setup()));
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("windowMs", c.windowMs());
        cfg.put("ticksRequested", c.ticks());
        cfg.put("warmupTicks", c.warmupTicks());
        cfg.put("safetyMs", c.safetyMs());
        cfg.put("safetyHoldTicks", c.holdTicks());
        cfg.put("thrashWorkers", c.thrashWorkers());
        cfg.put("coreBudgetMs", arm == Arm.CORE ? c.coreBudgetMs() : null);
        cfg.put("coreBudgetGiven", arm == Arm.CORE ? c.coreBudgetGiven() : null);
        json.put("config", cfg);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stopReason", stop.name());
        result.put("ticksMeasured", n);
        result.put("opsPerMsAggregate", ModeReportSupport.round(x.aggregate()));
        result.put("answeredOpsPerMsAggregate", ModeReportSupport.round(x.answeredAggregate()));
        result.put("passRatePercent", ModeReportSupport.round(passRate));
        result.put("opsIssued", x.totalOps());
        result.put("opsPass", x.totalPass());
        result.put("opsPending", x.totalPending());
        result.put("windowTimeMs", ModeReportSupport.round(x.totalWindowNanos() / 1_000_000.0));
        result.put("warmupTicksUsed", s.warmupUsed());
        result.put("steadyReached", s.steadyReached());
        result.put("hotspotClustersReady", s.hotspotsReady());
        result.put("clockReadNs", ModeReportSupport.round(s.clockReadNanos()));
        result.put("gcMillis", s.gcMillis());
        json.put("result", result);
        json.put("opsPerMs", ModeReportSupport.distJson(perMs));
        json.put("answeredOpsPerMs", ModeReportSupport.distJson(answeredPerMs));
        json.put("passOpsPerTick", ModeReportSupport.distJson(passPerTick));
        json.put("issuedOpsPerTick", ModeReportSupport.distJson(opsPerTick));
        json.put("windowMs", ModeReportSupport.distJson(window));
        json.put("msptMs", ModeReportSupport.distJson(mspt));
        json.put("testModTimePerTickMs", ModeReportSupport.distJson(issue));
        json.put("actions", actions);
        json.put("comparison", comparison);

        Map<String, String> files = new LinkedHashMap<>();
        files.put("ticks.csv", ticksCsv(t, x));
        files.put("actions.csv", actionCsv.toString());
        files.put("latency.csv", ModeReportSupport.latencyCsv(s.setup().recorder(), arm));

        String ratioText = "";
        Map<String, String> other = s.other();
        if (!other.isEmpty() && n > 0) {
            double otherValue = ModeCompare.number(other, "opsPerMs");
            ratioText = arm == Arm.CORE ? ", " + ModeCompare.ratio(x.aggregate(), otherValue) + " the last Minecraft run" : ", the last Core run was " + ModeCompare.ratio(otherValue, x.aggregate()) + " this run";
        }
        String summary = String.format(Locale.ROOT, "ATM_EFFICIENCY %s%s: %s ops/ms (median %s, p95 %s), %s ops per %s ms window, PASS %s %%, MSPT mean %s ms over %d ticks%s",
                arm.displayName(), arm == Arm.CORE ? " (Core budget " + f(c.coreBudgetMs(), 1) + " ms" + (c.coreBudgetGiven() ? "" : ", default") + ")" : "",
                f(x.aggregate(), 2), f(perMs.p50(), 2), f(perMs.p95(), 2), f(passPerTick.p50(), 1), f(c.windowMs(), 2), f(passRate, 1), f(mspt.mean(), 1), n, ratioText);
        return new ModeReport(s.setup().runId(), "efficiency", arm, json, md.toString(), files, summary);
    }

    private static String budgetText(Arm arm, EfficiencyConfig c) {
        if (arm == Arm.CORE) {
            return f(c.coreBudgetMs(), 1) + " ms configured (" + (c.coreBudgetGiven() ? "given in the command" : "default, not given in the command")
                    + "); used: not exposed by the public Core API (the test mod can neither read nor set the Core budget, set it with /worlddata budget fixed <ms> and read /worlddata budget after the run)";
        }
        return c.coreBudgetGiven() ? "Minecraft arm: the Core budget " + f(c.coreBudgetMs(), 1) + " ms that was given has no effect" : "Minecraft arm: not applicable";
    }

    private static List<String> comparison(EfficiencySession s, Numbers x, Dist perMs) {
        List<String> lines = new ArrayList<>();
        Map<String, String> other = s.other();
        if (other.isEmpty() || x.ticks() == 0) return lines;
        Arm arm = s.arm();
        Map<String, String> mine = storeValues(s);
        if (mine.isEmpty()) return lines;
        Map<String, String> core = arm == Arm.CORE ? mine : other;
        Map<String, String> mc = arm == Arm.CORE ? other : mine;
        lines.add("| Measure | WorldData Core | Minecraft | Core / Minecraft |");
        lines.add("|---|---|---|---|");
        for (String[] m : new String[][] {{"Useful ops (PASS) per ms, overall", "opsPerMs"}, {"Median ops/ms per tick window", "medianOpsPerMs"}, {"p95 ops/ms per tick window", "p95OpsPerMs"},
                {"Median ops per tick window", "medianOpsPerTick"}, {"PASS rate inside windows (%)", "passRate"}}) {
            lines.add("| " + m[0] + " | " + core.getOrDefault(m[1], "n/a") + " | " + mc.getOrDefault(m[1], "n/a") + " | " + ModeCompare.ratio(ModeCompare.number(core, m[1]), ModeCompare.number(mc, m[1])) + " |");
        }
        lines.add("");
        lines.add("Core run " + core.getOrDefault("runId", "n/a") + " (Core budget " + core.getOrDefault("coreBudgetMs", "n/a") + " ms as stated), Minecraft run " + mc.getOrDefault("runId", "n/a") + ".");
        List<String> diff = ModeCompare.differences(mine, other, "seed", "dimension", "windowMs", "ticks");
        lines.add(diff.isEmpty() ? "Both runs used the same seed, dimension, window and tick count." : "The two runs did not use the same settings (" + String.join("; ", diff) + "), so the ratio compares different set-ups.");
        return lines;
    }

    private static long[] longs(Columns t, int column, int n) {
        long[] out = new long[n];
        for (int i = 0; i < n; i++) out[i] = t.get(column, i);
        return out;
    }

    private static String ticksCsv(Columns t, Numbers x) {
        StringBuilder sb = new StringBuilder("tick,ops_issued,ops_pass,ops_pending,window_ms,pass_ops_per_ms,answered_ops_per_ms,test_mod_ms,mspt_ms\n");
        for (int i = 0; i < t.size(); i++) {
            sb.append(i).append(',').append(t.get(EfficiencySession.C_OPS, i)).append(',').append(t.get(EfficiencySession.C_PASS, i)).append(',').append(t.get(EfficiencySession.C_PENDING, i)).append(',')
                    .append(f(x.windowMs()[i], 4)).append(',').append(f(x.opsPerMs()[i], 3)).append(',').append(f(x.answeredPerMs()[i], 3)).append(',')
                    .append(f(t.get(EfficiencySession.C_HARNESS, i) / 1_000_000.0, 4)).append(',').append(f(t.get(EfficiencySession.C_MSPT, i) / 1_000_000.0, 4)).append('\n');
        }
        return sb.toString();
    }
}
