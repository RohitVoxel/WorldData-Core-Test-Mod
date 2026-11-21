package net.worlddata.benchmark.common.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BenchReport {

    public static final String TITLE = "WorldData Core Test micro-bench";
    public static final String DISCLAIMER = "These numbers come from a single-threaded burst on the server thread. "
            + "They are not pack TPS or MSPT and say nothing about world load.";

    private BenchReport() {}

    public static List<String> fileLines(BenchRun.Result result, String runId) {
        BenchConfig config = result.config();
        List<String> lines = new ArrayList<>();
        lines.add(TITLE);
        lines.add("Run: " + runId);
        lines.add("Api: " + config.api().commandName());
        lines.add("Mode: " + config.mode().commandName() + (config.compare() ? ", compared with Minecraft" : ""));
        lines.add("Dimension: " + result.dimension());
        lines.add("Count: " + config.count() + (config.clamped()
                ? " (requested " + config.requested() + ", clamped to the maximum of " + config.api().maxCount() + " for this API)"
                : ""));
        lines.add("Positions: " + result.positions().description());
        lines.add("Positions are LIVE: every position is in a chunk WorldData Core held as LIVE when the run started"
                + (config.compare() ? " and Minecraft had loaded, and both sides read exactly the same positions in the same order" : ""));
        lines.add("Included: " + (config.mode().acquiresEveryCall()
                ? "the WorldData Core chunk lookup on every call plus the API call"
                : "the API call only, the WorldData Core chunk lookup happens once before the timed loop"));
        lines.add("Warm-up: " + config.api().warmup() + " calls per side, not counted");
        lines.add("Timer floor: " + result.timerFloorNanos() + " ns per timed call, included in the latency numbers and not subtracted");
        for (BenchSideResult side : result.sides()) {
            lines.add("");
            lines.add("Side: " + side.label());
            lines.add("Timed: " + side.timed());
            BenchStats stats = side.stats();
            lines.add("Ops: " + stats.ops());
            lines.add("Wall: " + duration(stats.wallNanos()) + " (" + stats.wallNanos() + " ns)");
            lines.add("Throughput: " + rate(stats.opsPerSecond()) + " (" + Math.round(stats.opsPerSecond()) + " ops/s)");
            lines.add("Latency min: " + duration(stats.minNanos()));
            lines.add("Latency p50: " + duration(stats.p50Nanos()));
            lines.add("Latency p99: " + duration(stats.p99Nanos()));
            lines.add("Latency max: " + duration(stats.maxNanos()));
            lines.add("Latency mean: " + duration(Math.round(stats.meanNanos())));
            lines.add("Latency samples: " + stats.samples());
            lines.add("Stopped early: " + stoppedText(stats));
            lines.add("Checksum: " + side.checksum());
        }
        if (result.compared()) {
            lines.add("");
            lines.add("Compare: " + compareLine(result));
            lines.add("Checksum check: " + checksumLine(result));
            if (!config.mode().acquiresEveryCall()) {
                lines.add("Note: Minecraft looks the chunk up itself on every call, WorldData Core reads a chunk result resolved before the timed loop. "
                        + "Run the acquire mode to include the WorldData Core chunk lookup on every call too.");
            }
        }
        lines.add("");
        lines.add("Note: " + DISCLAIMER);
        return lines;
    }

    public static List<String> chatLines(BenchRun.Result result) {
        BenchConfig config = result.config();
        List<String> lines = new ArrayList<>();
        lines.add("WorldData Core Test bench: " + config.api().commandName() + ", " + config.mode().commandName()
                + ", " + config.count() + " ops" + (config.clamped() ? " (clamped from " + config.requested() + ")" : ""));
        for (BenchSideResult side : result.sides()) {
            BenchStats stats = side.stats();
            lines.add(side.label() + ": " + rate(stats.opsPerSecond()) + ", wall " + duration(stats.wallNanos())
                    + ", min/p50/p99/max " + duration(stats.minNanos()) + " / " + duration(stats.p50Nanos()) + " / "
                    + duration(stats.p99Nanos()) + " / " + duration(stats.maxNanos())
                    + (stats.truncated() ? " (stopped early at " + stats.ops() + " ops)" : ""));
        }
        if (result.compared()) {
            lines.add(compareLine(result));
            lines.add("Checksum: " + checksumLine(result));
        }
        lines.add("Timer floor " + result.timerFloorNanos() + " ns per timed call is included in the latency numbers");
        return lines;
    }

    public static String compareLine(BenchRun.Result result) {
        BenchSideResult core = result.sides().get(0);
        BenchSideResult other = result.sides().get(1);
        return core.label() + " vs " + other.label() + ": ops/s " + ratio(core.stats().opsPerSecond(), other.stats().opsPerSecond())
                + ", p50 latency " + ratio(core.stats().p50Nanos(), other.stats().p50Nanos())
                + ", p99 latency " + ratio(core.stats().p99Nanos(), other.stats().p99Nanos())
                + " (ratios are " + core.label() + " divided by " + other.label() + ")";
    }

    public static String checksumLine(BenchRun.Result result) {
        BenchSideResult first = result.sides().get(0);
        BenchSideResult second = result.sides().get(1);
        if (!result.config().api().comparable()) {
            return "not compared, the two sides encode state ids and materials differently";
        }
        if (first.stats().truncated() || second.stats().truncated()
                || first.stats().ops() != result.config().count() || second.stats().ops() != result.config().count()) {
            return "not compared, a side stopped before finishing all ops";
        }
        if (first.checksum() == second.checksum()) {
            return "both sides read the same values (" + first.checksum() + ")";
        }
        return "DIFFERS, " + first.label() + " " + first.checksum() + " and " + second.label() + " " + second.checksum()
                + ", the two sides did not read the same data";
    }

    public static String duration(long nanos) {
        if (nanos < 1_000L) return nanos + " ns";
        if (nanos < 1_000_000L) return String.format(Locale.ROOT, "%.2f \u00b5s", nanos / 1_000.0);
        if (nanos < 1_000_000_000L) return String.format(Locale.ROOT, "%.2f ms", nanos / 1_000_000.0);
        return String.format(Locale.ROOT, "%.2f s", nanos / 1_000_000_000.0);
    }

    public static String rate(double opsPerSecond) {
        if (opsPerSecond >= 1_000_000.0) return String.format(Locale.ROOT, "%.2f M ops/s", opsPerSecond / 1_000_000.0);
        if (opsPerSecond >= 1_000.0) return String.format(Locale.ROOT, "%.1f K ops/s", opsPerSecond / 1_000.0);
        return String.format(Locale.ROOT, "%.1f ops/s", opsPerSecond);
    }

    private static String ratio(double first, double second) {
        if (second <= 0.0) return "n/a";
        return String.format(Locale.ROOT, "%.2fx", first / second);
    }

    private static String stoppedText(BenchStats stats) {
        if (!stats.truncated()) return "no";
        if (stats.throughputTruncated() && stats.latencyTruncated()) return "yes, both passes hit the time budget";
        return stats.throughputTruncated() ? "yes, the throughput pass hit the time budget" : "yes, the latency pass hit the time budget";
    }
}
