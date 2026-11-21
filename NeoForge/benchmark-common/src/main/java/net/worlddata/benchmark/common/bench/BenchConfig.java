package net.worlddata.benchmark.common.bench;

public record BenchConfig(BenchApi api, int count, long requested, BenchMode mode, boolean compare) {

    public static BenchConfig parse(String api, Long count, String mode, boolean compare) {
        BenchApi parsedApi = BenchApi.parse(api);
        BenchMode parsedMode = BenchMode.parse(mode);
        long wanted = count == null ? parsedApi.defaultCount() : count;
        if (wanted < 1) throw new IllegalArgumentException("Count must be at least 1");
        int effective = (int) Math.min(wanted, parsedApi.maxCount());
        return new BenchConfig(parsedApi, effective, wanted, parsedMode, compare);
    }

    public boolean clamped() {
        return requested != count;
    }
}
