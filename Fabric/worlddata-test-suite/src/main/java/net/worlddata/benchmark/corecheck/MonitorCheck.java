package net.worlddata.benchmark.corecheck;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.wdc.core.api.WDC;
import net.wdc.core.api.request.WDCChunkResult;
import net.wdc.core.debug.monitor.MonitorApi;
import net.wdc.core.debug.monitor.MonitorSnapshot;
import net.wdc.core.debug.monitor.WDCApiMonitor;
import net.worlddata.benchmark.common.check.CheckReport;

import java.nio.file.Path;

public final class MonitorCheck {

    public static final int OFF_READS = 200;
    public static final int DISTINCT_READS = 300;
    public static final int REPEATED_READS = 50;
    public static final int CHUNK_CALLS = 20;
    public static final int SCAN_CALLS = 10;
    public static final int SPATIAL_CALLS = 5;
    public static final int LIGHT_CALLS = 5;
    public static final int SAMPLED_READS = 8000;
    public static final int SAMPLE_RATE = 8;

    private MonitorCheck() {}

    public static int start(CommandSourceStack source, Path root) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            CheckOutput.fail(source, "Needs a player: run it in-game on a server or integrated server");
            return 0;
        }
        ServerLevel level = source.getLevel();
        String dimension = level.dimension().location().toString();
        BlockPos origin = player.blockPosition();
        int cx = origin.getX() >> 4;
        int cz = origin.getZ() >> 4;
        WDCChunkResult chunk = WDC.requests().chunk(dimension, cx, cz);
        CheckReport report = new CheckReport("WorldData Core API monitor self-check");
        if (!chunk.isUsable()) {
            report.fail("The chunk under the player is not served by Core (" + chunk.freshness() + ", " + chunk.acquisitionStatus() + "), so the check cannot run");
            CheckOutput.publish(source, report, "monitor-check", root);
            return 0;
        }
        if (!WDCApiMonitor.allowed()) {
            report.warn("monitor.allowed is false in worlddata_core.json, so the monitor cannot be switched on and was not tested");
            CheckOutput.publish(source, report, "monitor-check", root);
            return 1;
        }
        boolean wasOn = WDCApiMonitor.enabled;
        int oldSample = WDCApiMonitor.sampleEvery();
        try {
            run(report, dimension, chunk, cx, cz);
        } finally {
            WDCApiMonitor.disable();
            WDCApiMonitor.reset();
            WDCApiMonitor.setSampleEvery(oldSample);
            if (wasOn) WDCApiMonitor.enable();
        }
        report.info("The check cleared the monitor counters and restored its state (" + (wasOn ? "on" : "off") + ", sampling 1 in " + oldSample + ")");
        CheckOutput.publish(source, report, "monitor-check", root);
        return 1;
    }

    static void run(CheckReport report, String dimension, WDCChunkResult chunk, int cx, int cz) {
        WDCApiMonitor.disable();
        WDCApiMonitor.reset();
        WDCApiMonitor.setSampleEvery(1);

        readPoints(chunk, cx, cz, OFF_READS, 0);
        long whileOff = WDCApiMonitor.stats().totalCalls();
        report.add(whileOff == 0L ? net.worlddata.benchmark.common.check.CheckStatus.PASS : net.worlddata.benchmark.common.check.CheckStatus.FAIL,
                "While off, " + OFF_READS + " reads were recorded as " + whileOff + " (expected 0)");

        WDCApiMonitor.enable();
        readPoints(chunk, cx, cz, DISTINCT_READS, 1);
        repeatedRead(chunk, cx, cz, REPEATED_READS);
        for (int i = 0; i < CHUNK_CALLS; i++) WDC.requests().chunk(dimension, cx, cz);
        for (int i = 0; i < SCAN_CALLS; i++) chunk.countBlocksByName("minecraft:stone");
        for (int i = 0; i < SPATIAL_CALLS; i++) WDC.spatial().summarizeBox(dimension, cx * 16, 60, cz * 16, cx * 16 + 15, 70, cz * 16 + 15);
        for (int i = 0; i < LIGHT_CALLS; i++) WDC.light().lightAt(dimension, cx * 16, 64, cz * 16);
        WDCApiMonitor.disable();
        MonitorSnapshot snapshot = WDCApiMonitor.snapshot(1000);

        expect(report, "blockAt", snapshot.callsOf(MonitorApi.BLOCK_AT), DISTINCT_READS);
        expect(report, "stateIdAt", snapshot.callsOf(MonitorApi.STATE_ID_AT), REPEATED_READS);
        expect(report, "requests().chunk", snapshot.callsOf(MonitorApi.CHUNK), CHUNK_CALLS);
        expect(report, "countBlocksByName", snapshot.callsOf(MonitorApi.COUNT_BLOCKS_BY_NAME), SCAN_CALLS);
        expect(report, "spatial().summarizeBox", snapshot.callsOf(MonitorApi.SUMMARIZE_BOX), SPATIAL_CALLS);
        expect(report, "light().lightAt", snapshot.callsOf(MonitorApi.LIGHT_AT), LIGHT_CALLS);
        long expectedTotal = DISTINCT_READS + REPEATED_READS + CHUNK_CALLS + SCAN_CALLS + SPATIAL_CALLS + LIGHT_CALLS;
        expect(report, "all calls together", snapshot.totalCalls(), expectedTotal);
        if (snapshot.nestedCalls() > 0L) {
            report.pass("Reads made inside spatial calls were kept apart: " + snapshot.nestedCalls() + " nested, not counted as caller activity");
        } else {
            report.fail("The spatial calls made no nested reads at all, so the nested counter did not work");
        }

        if (snapshot.callers().size() == 1 && snapshot.callers().get(0).calls() == expectedTotal) {
            String caller = snapshot.callers().get(0).caller();
            report.pass("Every call was attributed to one caller: " + caller);
            if (caller.startsWith("mod:")) {
                report.pass("The loader mapped the calling class to a mod id (" + caller + ")");
            } else {
                report.warn("The calling class was not mapped to a mod id, so the report falls back to class names (" + caller
                        + "). Callers can still be told apart, but they show as classes, not as mods");
            }
        } else {
            report.fail("Expected one caller with " + expectedTotal + " calls, got " + snapshot.callers().size() + " caller(s): " + snapshot.callers());
        }
        long repeats = snapshot.pairs().stream().filter(p -> p.api() == MonitorApi.STATE_ID_AT).mapToLong(MonitorSnapshot.PairRow::repeats).sum();
        if (repeats >= REPEATED_READS - 5) {
            report.pass("Repeated identical reads were flagged: " + repeats + " of " + REPEATED_READS);
        } else {
            report.fail("Only " + repeats + " of " + REPEATED_READS + " identical reads were flagged as repeats");
        }

        WDCApiMonitor.reset();
        WDCApiMonitor.setSampleEvery(SAMPLE_RATE);
        WDCApiMonitor.enable();
        readPoints(chunk, cx, cz, SAMPLED_READS, 2);
        WDCApiMonitor.disable();
        MonitorSnapshot sampled = WDCApiMonitor.snapshot(1000);
        expect(report, "blockAt with sampling 1 in " + SAMPLE_RATE, sampled.callsOf(MonitorApi.BLOCK_AT), SAMPLED_READS);
        long estimate = sampled.callers().isEmpty() ? 0L : sampled.callers().get(0).calls();
        if (Math.abs(estimate - SAMPLED_READS) <= 1L) {
            report.pass("The scaled caller estimate matches the exact total: " + estimate);
        } else {
            report.fail("The scaled caller estimate is " + estimate + " but the exact total is " + SAMPLED_READS);
        }
        long low = SAMPLED_READS / SAMPLE_RATE / 2;
        long high = SAMPLED_READS / SAMPLE_RATE * 2;
        if (sampled.walks() >= low && sampled.walks() <= high) {
            report.pass("Stack walks: " + sampled.walks() + " for " + SAMPLED_READS + " calls (about 1 in " + SAMPLE_RATE + "), average "
                    + String.format(java.util.Locale.ROOT, "%.1f", sampled.averageWalkNanos() / 1000.0) + " us each");
        } else {
            report.fail("Stack walks: " + sampled.walks() + " for " + SAMPLED_READS + " calls; expected between " + low + " and " + high);
        }

        long before = WDCApiMonitor.stats().totalCalls();
        readPoints(chunk, cx, cz, OFF_READS, 3);
        long after = WDCApiMonitor.stats().totalCalls();
        report.add(before == after ? net.worlddata.benchmark.common.check.CheckStatus.PASS : net.worlddata.benchmark.common.check.CheckStatus.FAIL,
                "After switching off, " + OFF_READS + " more reads changed the total from " + before + " to " + after);
    }

    private static void expect(CheckReport report, String what, long actual, long expected) {
        if (actual == expected) report.pass(what + ": " + actual + " calls counted, as expected");
        else report.fail(what + ": " + actual + " calls counted, expected " + expected);
    }

    private static void readPoints(WDCChunkResult chunk, int cx, int cz, int count, int salt) {
        for (int i = 0; i < count; i++) {
            chunk.blockAt(cx * 16 + ((i + salt) & 15), 60 + ((i >> 4) & 7), cz * 16 + ((i >> 7) & 15));
        }
    }

    private static void repeatedRead(WDCChunkResult chunk, int cx, int cz, int count) {
        for (int i = 0; i < count; i++) chunk.stateIdAt(cx * 16 + 3, 64, cz * 16 + 3);
    }
}
