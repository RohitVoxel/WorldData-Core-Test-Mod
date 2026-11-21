package net.worlddata.benchmark.common.bench;

public interface BenchClock {

    BenchClock SYSTEM = System::nanoTime;

    long nanos();
}
