package net.worlddata.benchmark.coreintegration;

import net.worlddata.benchmark.common.bench.BenchMode;
import net.worlddata.benchmark.common.bench.BenchPositions;
import net.worlddata.benchmark.common.bench.BenchTarget;
import net.worlddata.benchmark.common.bench.ChunkProbe;
import net.worlddata.benchmark.common.pack.CallRecorder;
import net.worlddata.benchmark.common.pack.WorldReader;

public final class CoreFactory {

    public static final String CORE_MOD_ID = "worlddata_core";

    private CoreFactory() {}

    public static boolean ready() {
        return CoreWorldReader.ready();
    }

    public static WorldReader create(String dimension, CallRecorder recorder) {
        return new CoreWorldReader(dimension, recorder);
    }

    public static ChunkProbe benchProbe(String dimension) {
        return CoreBenchTarget.probe(dimension);
    }

    public static BenchTarget benchTarget(String dimension, BenchPositions positions, BenchMode mode) {
        return CoreBenchTarget.create(dimension, positions, mode);
    }
}
