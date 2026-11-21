package net.worlddata.benchmark.common.mode;

import net.worlddata.benchmark.common.pack.Arm;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ModeCompare {

    private ModeCompare() {}

    static double number(Map<String, String> values, String key) {
        String raw = values.get(key);
        if (raw == null) return Double.NaN;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static String ratio(double core, double minecraft) {
        if (Double.isNaN(core) || Double.isNaN(minecraft) || minecraft <= 0.0) return "n/a";
        return String.format(Locale.ROOT, "%.2fx", core / minecraft);
    }

    static List<String> differences(Map<String, String> mine, Map<String, String> other, String... keys) {
        List<String> differences = new ArrayList<>();
        for (String key : keys) {
            String a = mine.get(key);
            String b = other.get(key);
            if (a != null && b != null && !a.equals(b)) differences.add(key + " " + a + " vs " + b);
        }
        return differences;
    }

    static Arm otherOf(Arm arm) {
        return arm == Arm.CORE ? Arm.VANILLA : Arm.CORE;
    }
}
