package net.worlddata.benchmark.common.pack;

import net.worlddata.benchmark.common.mode.ArmChoice;
import net.worlddata.benchmark.common.report.EnvironmentInfo;

import java.nio.file.Path;

public record PackSetup(
        long totalOps,
        long seed,
        String runId,
        String dimension,
        int baseChunkX,
        int baseChunkZ,
        WorldReader primary,
        WorldReader baseline,
        ChunkLoader loader,
        WorldWriter writer,
        CallRecorder recorder,
        boolean lightOnClientThread,
        Path sinkFile,
        EnvironmentInfo environment,
        String side,
        long tickBudgetNanos,
        long opsPerSecond,
        int minBuildHeight,
        int maxBuildHeight,
        int thrashWorkers,
        ArmChoice armRequested,
        boolean coreInstalled
) {

    public PackSetup {
        if (armRequested == null) armRequested = ArmChoice.AUTO;
        if (thrashWorkers < PackConfig.THRASH_WORKERS_MIN || thrashWorkers > PackConfig.THRASH_WORKERS_MAX) {
            throw new IllegalArgumentException("thrash workers must be between " + PackConfig.THRASH_WORKERS_MIN + " and " + PackConfig.THRASH_WORKERS_MAX + ", got " + thrashWorkers);
        }
    }

    public PackSetup(long totalOps, long seed, String runId, String dimension, int baseChunkX, int baseChunkZ, WorldReader primary, WorldReader baseline,
                     ChunkLoader loader, WorldWriter writer, CallRecorder recorder, boolean lightOnClientThread, Path sinkFile, EnvironmentInfo environment,
                     String side, long tickBudgetNanos, long opsPerSecond, int minBuildHeight, int maxBuildHeight, int thrashWorkers) {
        this(totalOps, seed, runId, dimension, baseChunkX, baseChunkZ, primary, baseline, loader, writer, recorder, lightOnClientThread, sinkFile, environment,
                side, tickBudgetNanos, opsPerSecond, minBuildHeight, maxBuildHeight, thrashWorkers, ArmChoice.AUTO, primary != null && primary.arm() == Arm.CORE);
    }

    public PackSetup(long totalOps, long seed, String runId, String dimension, int baseChunkX, int baseChunkZ, WorldReader primary, WorldReader baseline,
                     ChunkLoader loader, WorldWriter writer, CallRecorder recorder, boolean lightOnClientThread, Path sinkFile, EnvironmentInfo environment,
                     String side, long tickBudgetNanos, long opsPerSecond, int minBuildHeight, int maxBuildHeight) {
        this(totalOps, seed, runId, dimension, baseChunkX, baseChunkZ, primary, baseline, loader, writer, recorder, lightOnClientThread, sinkFile, environment,
                side, tickBudgetNanos, opsPerSecond, minBuildHeight, maxBuildHeight, PackConfig.THRASH_WORKERS);
    }

    public PackSetup(long totalOps, long seed, String runId, String dimension, int baseChunkX, int baseChunkZ, WorldReader primary, WorldReader baseline,
                     ChunkLoader loader, WorldWriter writer, CallRecorder recorder, boolean lightOnClientThread, Path sinkFile, EnvironmentInfo environment,
                     String side, long tickBudgetNanos) {
        this(totalOps, seed, runId, dimension, baseChunkX, baseChunkZ, primary, baseline, loader, writer, recorder, lightOnClientThread, sinkFile, environment,
                side, tickBudgetNanos, PackConfig.OPS_PER_SECOND_UNLIMITED, -64, 320, PackConfig.THRASH_WORKERS);
    }

    public YRange yRange() {
        return YRange.of(minBuildHeight, maxBuildHeight);
    }
}
