package net.worlddata.benchmark.common.mode;

import java.util.Arrays;

public record Dist(int count, double mean, double min, double p05, double p50, double p95, double p99, double max) {

    public static final Dist EMPTY = new Dist(0, 0, 0, 0, 0, 0, 0, 0);

    public static Dist of(double[] values, int from, int to) {
        int n = Math.max(0, to - from);
        if (n == 0) return EMPTY;
        double[] sorted = Arrays.copyOfRange(values, from, to);
        Arrays.sort(sorted);
        double sum = 0.0;
        for (double v : sorted) sum += v;
        return new Dist(n, sum / n, sorted[0], at(sorted, 0.05), at(sorted, 0.50), at(sorted, 0.95), at(sorted, 0.99), sorted[n - 1]);
    }

    public static Dist of(double[] values) {
        return of(values, 0, values.length);
    }

    public static Dist ofNanosAsMillis(long[] values, int from, int to) {
        double[] converted = new double[Math.max(0, to - from)];
        for (int i = 0; i < converted.length; i++) converted[i] = values[from + i] / 1_000_000.0;
        return of(converted);
    }

    private static double at(double[] sorted, double fraction) {
        int index = (int) Math.ceil(fraction * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }
}
