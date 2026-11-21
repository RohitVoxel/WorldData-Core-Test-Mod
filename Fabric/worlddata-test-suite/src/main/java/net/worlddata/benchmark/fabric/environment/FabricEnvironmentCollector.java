package net.worlddata.benchmark.fabric.environment;

import net.worlddata.benchmark.common.report.EnvironmentInfo;
import net.fabricmc.loader.api.FabricLoader;

public final class FabricEnvironmentCollector {

    private FabricEnvironmentCollector() {}

    public static EnvironmentInfo collect(String dimensionId, Integer renderDistance, Integer simulationDistance, Integer fpsCap) {
        return new EnvironmentInfo(
                null,
                versionOf("fabricloader"),
                versionOf("fabric-api"),
                versionOf("worlddata_core_test"),
                System.getProperty("java.version"),
                System.getProperty("os.name"),
                null,
                Runtime.getRuntime().maxMemory(),
                renderDistance,
                simulationDistance,
                fpsCap,
                dimensionId
        );
    }

    private static String versionOf(String modId) {
        return FabricLoader.getInstance()
                .getModContainer(modId)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse(null);
    }
}
