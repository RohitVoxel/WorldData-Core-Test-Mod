package net.worlddata.benchmark.fabric.client;

import net.worlddata.benchmark.common.pack.CallKind;
import net.worlddata.benchmark.common.pack.PackLiveStatus;
import net.worlddata.benchmark.common.pack.PackReportBuilder;
import net.worlddata.benchmark.common.pack.PackSession;
import net.worlddata.benchmark.common.pack.PackSessionRegistry;

import java.util.ArrayList;
import java.util.List;

public final class PackDebugLines {

    public static final int FULL_ROWS = 6;
    public static final int COMPACT_ROWS = 3;

    private static final String AQUA = "\u00a7b";
    private static final String GRAY = "\u00a77";
    private static final String WHITE = "\u00a7f";
    private static final String GREEN = "\u00a7a";
    private static final String RED = "\u00a7c";

    private PackDebugLines() {}

    public static List<String> build(int freeRows) {
        List<String> lines = new ArrayList<>();
        PackSession session = PackSessionRegistry.active();
        if (session == null) {
            lines.add(AQUA + "[ATM_PACK]" + GRAY + " idle - /worlddatatest run [ops]");
            lines.add("");
            return lines;
        }
        PackLiveStatus s = PackLiveStatus.from(session);
        lines.add(String.format("%s[ATM_PACK] %s%s %s%s %s%s", AQUA, WHITE, s.phase().label(), GRAY, s.arm().displayName(), WHITE, PackReportBuilder.minutes(s.elapsedMillis())));
        if (freeRows >= COMPACT_ROWS) {
            lines.add(String.format("%sops %s%d%s/%d (%.1f %%) in flight %s%d%s mspt %s%.1f%s harness %s%.2f ms%s batch %s%d/%d", GRAY, WHITE, s.completed(), GRAY, s.total(), s.percentDone(), WHITE, s.inFlight(), GRAY, WHITE, s.tickMillis(), GRAY, WHITE, s.harnessMillis(), GRAY, WHITE, s.batch(), s.batchCount()));
            lines.add(String.format("%spass %s%d %sunloaded %s%d %sunavailable %s%d %stimeout %s%d %sfail %s%d", GRAY, GREEN, s.pass(), GRAY, WHITE, s.unloaded(), GRAY, WHITE, s.unavailable(), GRAY, WHITE, s.timeout(), GRAY, s.fail() > 0 ? RED : WHITE, s.fail()));
        }
        if (freeRows >= FULL_ROWS) {
            lines.add(String.format("%sp50 getBlockState %s%s %sstateIdAt %s%s %sblockAt %s%s", GRAY, WHITE, PackReportBuilder.nanos(s.p50VanillaNanos()), GRAY, WHITE,
                    PackReportBuilder.nanos(s.p50StateIdNanos()), GRAY, WHITE, PackReportBuilder.nanos(s.p50BlockNanos())));
            lines.add(String.format("%sMC force-load waves %s%d/%d%s, held %s%d%s, pending reads %s%d", GRAY, WHITE, s.wave(), s.waveCount(), GRAY, WHITE, s.heldGroups(), GRAY, WHITE, s.inFlight()));
        }
        lines.add("");
        return lines;
    }
}
