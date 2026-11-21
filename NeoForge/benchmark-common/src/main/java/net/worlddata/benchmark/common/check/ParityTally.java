package net.worlddata.benchmark.common.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public final class ParityTally {

    public static final int EXAMPLES = 5;

    private final Map<String, long[]> counts = new LinkedHashMap<>();
    private final Map<String, List<String>> examples = new LinkedHashMap<>();
    private final Set<String> soft = new HashSet<>();

    private long[] slot(String category) {
        return counts.computeIfAbsent(category, key -> new long[2]);
    }

    public void compare(String category, boolean equal, Supplier<String> example) {
        long[] slot = slot(category);
        slot[0]++;
        if (equal) return;
        slot[1]++;
        if (example != null) note(category, example.get());
    }

    public void add(String category, long compared, long mismatched) {
        long[] slot = slot(category);
        slot[0] += compared;
        slot[1] += mismatched;
    }

    public void note(String category, String example) {
        List<String> list = examples.computeIfAbsent(category, key -> new ArrayList<>());
        if (list.size() < EXAMPLES) list.add(example);
    }

    public void markSoft(String category) {
        soft.add(category);
    }

    public Set<String> categories() {
        return counts.keySet();
    }

    public long compared(String category) {
        long[] slot = counts.get(category);
        return slot == null ? 0L : slot[0];
    }

    public long mismatched(String category) {
        long[] slot = counts.get(category);
        return slot == null ? 0L : slot[1];
    }

    public long totalCompared() {
        long sum = 0L;
        for (long[] slot : counts.values()) sum += slot[0];
        return sum;
    }

    public long totalMismatched() {
        long sum = 0L;
        for (long[] slot : counts.values()) sum += slot[1];
        return sum;
    }

    public List<String> examples(String category) {
        List<String> list = examples.get(category);
        return list == null ? List.of() : List.copyOf(list);
    }

    public CheckReport report(String title) {
        CheckReport report = new CheckReport(title);
        for (String category : counts.keySet()) {
            long compared = compared(category);
            long mismatched = mismatched(category);
            if (compared == 0L) {
                report.warn(category + ": nothing was compared");
            } else if (mismatched == 0L) {
                report.pass(String.format(Locale.ROOT, "%s: %,d compared, all equal", category, compared));
            } else if (soft.contains(category)) {
                report.warn(String.format(Locale.ROOT, "%s: %,d of %,d differ (informational). Examples: %s", category, mismatched, compared, String.join("; ", examples(category))));
            } else {
                report.fail(String.format(Locale.ROOT, "%s: %,d of %,d differ. Examples: %s", category, mismatched, compared, String.join("; ", examples(category))));
            }
        }
        if (counts.isEmpty()) report.warn("Nothing was compared");
        return report;
    }
}
