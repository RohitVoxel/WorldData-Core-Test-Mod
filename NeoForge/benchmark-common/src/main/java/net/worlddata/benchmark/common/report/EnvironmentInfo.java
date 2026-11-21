package net.worlddata.benchmark.common.report;

public record EnvironmentInfo(
        String minecraftVersion,
        String loaderName,
        String loaderVersion,
        String benchmarkVersion,
        String javaVersion,
        String os,
        String cpuInfo,
        Long totalMemoryBytes,
        Integer renderDistance,
        Integer simulationDistance,
        Integer fpsCap,
        String dimension
) {
    public static EnvironmentInfo empty() {
        return new EnvironmentInfo(null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
