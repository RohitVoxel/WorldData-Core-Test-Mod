package net.worlddata.benchmark.common.pack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record PackLiveStatus(
        PackSessionState state,
        PackPhase phase,
        Arm arm,
        long completed,
        long total,
        long issued,
        int inFlight,
        long pass,
        long unloaded,
        long unavailable,
        long timeout,
        long fail,
        double tickMillis,
        double harnessMillis,
        int batch,
        int batchCount,
        int batchSize,
        long p50BlockNanos,
        long p50StateIdNanos,
        long p50VanillaNanos,
        int wave,
        int waveCount,
        int heldGroups,
        long elapsedMillis,
        long opsPerSecond,
        long opsPerSecondCap
) {

    public static PackLiveStatus from(PackSession s) {
        OpsLedger l = s.ledger();
        CallRecorder r = s.recorder();
        return new PackLiveStatus(s.state(), s.phase(), s.setup().primary().arm(), l.completed(), l.total(), l.issued(), l.inFlight(),
                l.outcome(Outcome.PASS), l.outcome(Outcome.UNLOADED), l.outcome(Outcome.UNAVAILABLE), l.outcome(Outcome.TIMEOUT), l.outcome(Outcome.FAIL),
                s.lastTickMillis(), s.lastHarnessMillis(), s.batchNumber() + 1, s.planner().batchCount(), s.currentBatchSize(), r.p50(CallKind.C_BLOCK_AT), r.p50(CallKind.C_STATE_ID), r.p50(CallKind.V_BLOCK_STATE), s.waves().started(), s.waves().planned(), s.waves().heldGroups(), s.wallMillis(), s.opsPerSecondNow(), s.opsPerSecondCap());
    }

    public double percentDone() {
        return total == 0L ? 0.0 : Math.min(100.0, completed * 100.0 / total);
    }

    public String statusLine() {
        return String.format(Locale.ROOT, "ATM_PACK %s %s arm=%s %s completed %d/%d (%.1f %%) issued=%d inflight=%d pass=%d unloaded=%d unavailable=%d timeout=%d fail=%d mspt=%.1f harness=%.2f ms batch=%d/%d (%d ops) mc-force-load waves=%d/%d held=%d ops/s=%s",
                state, phase, arm.displayName(), PackReportBuilder.minutes(elapsedMillis), completed, total, percentDone(), issued, inFlight, pass, unloaded, unavailable, timeout, fail, tickMillis, harnessMillis, batch, batchCount, batchSize, wave, waveCount, heldGroups,
                opsPerSecondCap > 0L ? opsPerSecond + "/" + opsPerSecondCap : String.valueOf(opsPerSecond));
    }

    public static List<String> latencyLines(PackSession s) {
        List<String> lines = new ArrayList<>();
        lines.add("Per-API p50 / p99 (calls)");
        for (CallKind kind : CallKind.values()) {
            CallSnapshot c = s.recorder().snapshot(kind);
            if (c.count() == 0L) continue;
            lines.add(String.format(Locale.ROOT, "%s %s: p50 %s p99 %s (%d)", kind.arm() == Arm.CORE ? "A" : "V", kind.label(),
                    PackReportBuilder.nanos(c.p50Nanos()), PackReportBuilder.nanos(c.p99Nanos()), c.count()));
        }
        return lines;
    }

    public static List<String> coverageLines(PackSession s) {
        List<String> lines = new ArrayList<>();
        for (Family family : Family.values()) {
            lines.add(String.format(Locale.ROOT, "%s: WorldData Core %d, Minecraft %d, ops %d", family.label(), s.recorder().familyCount(Arm.CORE, family),
                    s.recorder().familyCount(Arm.VANILLA, family), s.ledger().familyCompleted(family)));
        }
        return lines;
    }
}
