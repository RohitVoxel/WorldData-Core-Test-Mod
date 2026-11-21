package net.worlddata.benchmark.common.mode;

import net.worlddata.benchmark.common.pack.Arm;
import net.worlddata.benchmark.common.pack.CallKind;
import net.worlddata.benchmark.common.pack.CallRecorder;
import net.worlddata.benchmark.common.pack.CallSnapshot;
import net.worlddata.benchmark.common.pack.PackConfig;
import net.worlddata.benchmark.common.pack.PackReportBuilder;
import net.worlddata.benchmark.common.report.EnvironmentInfo;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class ModeReportSupport {

    static final String STABLE_AREA = "Run in a loaded, unchanging area: stand still near the middle of the loaded square, no players or mobs moving nearby, no running farms, redstone or fluids, and no other tests running. Use the same world, dimension, seed and spot for every arm you compare, and run each arm more than once.";

    static final String LOAD_NOTE = "The load is not assumed identical across arms. The Minecraft arm calls Minecraft APIs only. The WorldData Core arm calls the Core public API only, with no paired Minecraft baseline; Core's own ingest and budget work happens in the server tick outside the op calls. The mix is the pack read mix (point, layer, filter, spatial, biome / availability / raw / peek / world snapshot) on the 24 pinned hotspot chunks, with no force-load waves, no writes and no light reads (the pack run issues those from the client tick on an integrated server; these modes do not).";

    private ModeReportSupport() {}

    static String f(double value, int digits) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "n/a";
        return String.format(Locale.ROOT, "%." + digits + "f", value);
    }

    static void row(StringBuilder sb, String key, String value) {
        sb.append("| ").append(key).append(" | ").append(value).append(" |\n");
    }

    static String armText(Arm arm, boolean coreInstalled) {
        if (arm == Arm.CORE) return "WorldData Core (Core public API reads only, no paired Minecraft baseline)";
        return coreInstalled ? "Minecraft (Minecraft APIs only; WorldData Core is installed but is not queried, its own tick work still runs)"
                : "Minecraft (Minecraft APIs only; WorldData Core is not installed)";
    }

    static void header(StringBuilder sb, ModeSetup s, Arm arm, String modeText) {
        row(sb, "Mode", modeText);
        row(sb, "Arm used", armText(arm, s.coreInstalled()));
        row(sb, "Arm requested", s.armRequested().name().toLowerCase(Locale.ROOT) + (s.coreInstalled() ? ", WorldData Core installed" : ", WorldData Core not installed"));
        row(sb, "Seed", String.valueOf(s.seed()));
        row(sb, "Dimension", s.dimension());
        row(sb, "Side", s.side());
        row(sb, "Player chunk at start / hotspot ring", s.baseChunkX() + ", " + s.baseChunkZ() + " / " + PackConfig.HOTSPOT_CLUSTERS + " clusters of " + PackConfig.HOTSPOT_CLUSTER_SIDE + " x " + PackConfig.HOTSPOT_CLUSTER_SIDE + " chunks around it, pinned with tickets");
        row(sb, "Dimension height range", s.minBuildHeight() + " to " + s.maxBuildHeight());
        EnvironmentInfo e = s.environment();
        if (e != null) {
            row(sb, "Test mod version", nullable(e.benchmarkVersion()));
            row(sb, "Java / OS", nullable(e.javaVersion()) + " / " + nullable(e.os()));
            row(sb, "View / simulation distance", nullable(e.renderDistance()) + " / " + nullable(e.simulationDistance()));
        }
    }

    static void methodNotes(StringBuilder sb) {
        sb.append("\n## Method notes\n\n");
        sb.append("- Same-area guidance: ").append(STABLE_AREA).append("\n");
        sb.append("- Load across arms: ").append(LOAD_NOTE).append("\n");
    }

    static String thrashText(int workers) {
        return workers > 0 ? "on: " + workers + " real threads, one burst every " + ModeConfig.THRASH_EVERY_TICKS + " ticks, outside the measured window (stress)" : "off (default; thrash=on is 16 threads, up to " + PackConfig.THRASH_WORKERS_MAX + ")";
    }

    static void thrashSection(StringBuilder md, ModeThrash thrash) {
        if (thrash == null) return;
        md.append("\n## Thrash\n\n");
        md.append("Threads ").append(thrash.workers()).append(", bursts completed ").append(thrash.bursts()).append(", failed ").append(thrash.failed()).append(", skipped ").append(thrash.skipped())
                .append(", mean parallel / single ratio ").append(f(thrash.meanRatio(), 3)).append(", reads ").append(thrash.reads()).append(".\n");
    }

    static String nullable(Object value) {
        return value == null ? "n/a" : String.valueOf(value);
    }

    static String distLine(String label, Dist d, int digits, String unit) {
        return "| " + label + " | " + f(d.mean(), digits) + " | " + f(d.p50(), digits) + " | " + f(d.p95(), digits) + " | " + f(d.p99(), digits) + " | " + f(d.min(), digits) + " | " + f(d.max(), digits) + " | " + unit + " |\n";
    }

    static String distHeader() {
        return "| Metric | mean | p50 / median | p95 | p99 | min | max | unit |\n|---|---|---|---|---|---|---|---|\n";
    }

    static Map<String, Object> distJson(Dist d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", d.count());
        m.put("mean", round(d.mean()));
        m.put("min", round(d.min()));
        m.put("p05", round(d.p05()));
        m.put("p50", round(d.p50()));
        m.put("p95", round(d.p95()));
        m.put("p99", round(d.p99()));
        m.put("max", round(d.max()));
        return m;
    }

    static double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    static boolean armPresent(Arm primary, CallKind kind) {
        return kind.arm() == primary;
    }

    static void latencyTable(StringBuilder sb, CallRecorder recorder, Arm arm) {
        sb.append("| Call | Family | Total | Passed | Pending | Unloaded | Unavailable | min | p50 | p95 | p99 | mean | max |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (CallKind kind : CallKind.values()) {
            if (!armPresent(arm, kind)) continue;
            CallSnapshot c = recorder.snapshot(kind);
            if (c.count() == 0L) continue;
            sb.append("| ").append(kind.label()).append(" | ").append(kind.family().label()).append(" | ").append(c.count()).append(" | ").append(c.passed()).append(" | ")
                    .append(c.waiting()).append(" | ").append(c.unloaded()).append(" | ").append(c.unavailable()).append(" | ")
                    .append(PackReportBuilder.nanos(c.minNanos())).append(" | ").append(PackReportBuilder.nanos(c.p50Nanos())).append(" | ").append(PackReportBuilder.nanos(c.p95Nanos())).append(" | ")
                    .append(PackReportBuilder.nanos(c.p99Nanos())).append(" | ").append(PackReportBuilder.nanos(Math.round(c.meanNanos()))).append(" | ").append(PackReportBuilder.nanos(c.maxNanos())).append(" |\n");
        }
    }

    static String latencyCsv(CallRecorder recorder, Arm arm) {
        StringBuilder sb = new StringBuilder("call_id,call,family,total,passed,pending,unloaded,unavailable,min_ns,p50_ns,p95_ns,p99_ns,mean_ns,max_ns\n");
        for (CallKind kind : CallKind.values()) {
            if (!armPresent(arm, kind)) continue;
            CallSnapshot c = recorder.snapshot(kind);
            if (c.count() == 0L) continue;
            sb.append(kind.csvId()).append(',').append('"').append(kind.label()).append('"').append(',').append(kind.family()).append(',').append(c.count()).append(',').append(c.passed()).append(',')
                    .append(c.waiting()).append(',').append(c.unloaded()).append(',').append(c.unavailable()).append(',').append(c.minNanos()).append(',').append(c.p50Nanos()).append(',')
                    .append(c.p95Nanos()).append(',').append(c.p99Nanos()).append(',').append(Math.round(c.meanNanos())).append(',').append(c.maxNanos()).append('\n');
        }
        return sb.toString();
    }

    static Map<String, Object> environmentJson(ModeSetup s) {
        Map<String, Object> m = new LinkedHashMap<>();
        EnvironmentInfo e = s.environment();
        if (e == null) return m;
        m.put("testMod", e.benchmarkVersion());
        m.put("java", e.javaVersion());
        m.put("os", e.os());
        m.put("viewDistance", e.renderDistance());
        m.put("simulationDistance", e.simulationDistance());
        m.put("maxMemoryBytes", e.totalMemoryBytes());
        return m;
    }
}
