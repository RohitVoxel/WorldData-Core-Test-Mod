package net.worlddata.benchmark.common.bench;

public interface BenchTarget {

    String label();

    String timed(BenchApi api, BenchMode mode);

    long call(BenchApi api, int index);

    default long block(BenchApi api, int start, int count) {
        long digest = 0L;
        for (int i = 0; i < count; i++) digest += call(api, start + i);
        return digest;
    }
}
