package net.worlddata.benchmark.common.bench;

public record BenchSideResult(String label, String timed, long checksum, BenchStats stats) {
}
