package net.worlddata.benchmark.common.mode;

import net.worlddata.benchmark.common.pack.CallRecorder;
import net.worlddata.benchmark.common.pack.ChunkLoader;
import net.worlddata.benchmark.common.pack.WorldReader;
import net.worlddata.benchmark.common.pack.YRange;
import net.worlddata.benchmark.common.report.EnvironmentInfo;

public record ModeSetup(
        String runId,
        long seed,
        String dimension,
        int baseChunkX,
        int baseChunkZ,
        WorldReader primary,
        ChunkLoader loader,
        CallRecorder recorder,
        EnvironmentInfo environment,
        String side,
        int minBuildHeight,
        int maxBuildHeight,
        ArmChoice armRequested,
        boolean coreInstalled
) {

    public YRange yRange() {
        return YRange.of(minBuildHeight, maxBuildHeight);
    }
}
