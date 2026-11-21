package net.worlddata.benchmark.common.check;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CoverageTally {

    public static final int MAX_LISTED = 20;

    private final long[][] counts = new long[2][CoverageState.values().length];
    private final List<int[]> missing = new ArrayList<>();
    private final List<int[]> ghost = new ArrayList<>();
    private long missingTotal;
    private long ghostTotal;

    public void record(int chunkX, int chunkZ, boolean minecraftLoaded, CoverageState core) {
        if (core == null) throw new IllegalArgumentException("core state must not be null");
        counts[minecraftLoaded ? 1 : 0][core.ordinal()]++;
        if (minecraftLoaded && core != CoverageState.LIVE) {
            missingTotal++;
            if (missing.size() < MAX_LISTED) missing.add(new int[] {chunkX, chunkZ});
        }
        if (!minecraftLoaded && core == CoverageState.LIVE) {
            ghostTotal++;
            if (ghost.size() < MAX_LISTED) ghost.add(new int[] {chunkX, chunkZ});
        }
    }

    public long count(boolean minecraftLoaded, CoverageState core) {
        return counts[minecraftLoaded ? 1 : 0][core.ordinal()];
    }

    public long loadedTotal() {
        long sum = 0L;
        for (long n : counts[1]) sum += n;
        return sum;
    }

    public long unloadedTotal() {
        long sum = 0L;
        for (long n : counts[0]) sum += n;
        return sum;
    }

    public long total() {
        return loadedTotal() + unloadedTotal();
    }

    public long missingTotal() {
        return missingTotal;
    }

    public long ghostTotal() {
        return ghostTotal;
    }

    public List<int[]> missing() {
        return List.copyOf(missing);
    }

    public List<int[]> ghost() {
        return List.copyOf(ghost);
    }

    public boolean consistent() {
        return missingTotal == 0L && ghostTotal == 0L;
    }

    public String line() {
        return String.format(Locale.ROOT, "%d chunks looked at, Minecraft holds %d of them; Core serves %d of those live (%d stale, %d acquiring, %d unavailable); "
                        + "missing in Core %d, live in Core but not held by Minecraft %d",
                total(), loadedTotal(), count(true, CoverageState.LIVE), count(true, CoverageState.STALE), count(true, CoverageState.ACQUIRING),
                count(true, CoverageState.UNAVAILABLE), missingTotal, ghostTotal);
    }

    public static String listed(List<int[]> chunks, long total) {
        StringBuilder text = new StringBuilder();
        for (int[] chunk : chunks) {
            if (text.length() > 0) text.append(", ");
            text.append('(').append(chunk[0]).append(',').append(chunk[1]).append(')');
        }
        if (total > chunks.size()) text.append(", and ").append(total - chunks.size()).append(" more");
        return text.toString();
    }

    public static CheckReport report(String title, int radius, int centerX, int centerZ, List<String> labels, List<CoverageTally> tallies) {
        if (labels.size() != tallies.size() || tallies.isEmpty()) throw new IllegalArgumentException("one label per tally and at least one tally");
        CheckReport report = new CheckReport(title);
        report.info("Square of " + (2 * radius + 1) + " by " + (2 * radius + 1) + " chunks around chunk (" + centerX + ", " + centerZ + ")");
        for (int i = 0; i < tallies.size(); i++) report.info(labels.get(i) + ": " + tallies.get(i).line());
        CoverageTally first = tallies.get(0);
        CoverageTally last = tallies.get(tallies.size() - 1);
        if (last.missingTotal() > 0L) {
            report.fail(last.missingTotal() + " chunk(s) are held by Minecraft but Core does not serve them live after " + labels.get(labels.size() - 1) + ": "
                    + listed(last.missing(), last.missingTotal()));
        } else if (first.missingTotal() > 0L) {
            report.warn(first.missingTotal() + " chunk(s) were not live in Core on the first look but caught up later: " + listed(first.missing(), first.missingTotal()));
        } else {
            report.pass("Every chunk Minecraft holds is served live by Core");
        }
        if (last.ghostTotal() > 0L) {
            report.fail(last.ghostTotal() + " chunk(s) are live in Core but Minecraft no longer holds them after " + labels.get(labels.size() - 1) + ": "
                    + listed(last.ghost(), last.ghostTotal()));
        } else if (first.ghostTotal() > 0L) {
            report.warn(first.ghostTotal() + " chunk(s) were still live in Core on the first look but Minecraft had dropped them: " + listed(first.ghost(), first.ghostTotal()));
        } else {
            report.pass("Core serves no chunk live that Minecraft has dropped");
        }
        if (first.loadedTotal() == 0L) report.warn("Minecraft holds no chunk in this square, so nothing was compared");
        return report;
    }
}
