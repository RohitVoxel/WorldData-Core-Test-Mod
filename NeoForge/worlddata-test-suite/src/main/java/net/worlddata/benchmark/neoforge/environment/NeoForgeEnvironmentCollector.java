package net.worlddata.benchmark.neoforge.environment;

import net.neoforged.fml.ModList;
import net.worlddata.benchmark.common.report.EnvironmentInfo;

public final class NeoForgeEnvironmentCollector {

    private NeoForgeEnvironmentCollector() {}

    public static EnvironmentInfo collect(String dimensionId, Integer renderDistance, Integer simulationDistance, Integer fpsCap) {
        return new EnvironmentInfo(
                null,
                "neoforge",
                versionOf("neoforge"),
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
        return ModList.get()
                .getModContainerById(modId)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse(null);
    }
}
