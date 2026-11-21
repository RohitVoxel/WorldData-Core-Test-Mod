package net.worlddata.benchmark.coreintegration;

import net.wdc.core.api.WDC;
import net.wdc.core.api.request.WDCBlockFilter;
import net.wdc.core.api.request.WDCChunkResult;
import net.wdc.core.api.request.WDCFreshness;
import net.wdc.core.api.request.WDCWaterInfo;
import net.worlddata.benchmark.common.bench.BenchApi;
import net.worlddata.benchmark.common.bench.BenchMode;
import net.worlddata.benchmark.common.bench.BenchPositions;
import net.worlddata.benchmark.common.bench.BenchTarget;
import net.worlddata.benchmark.common.bench.ChunkProbe;

public final class CoreBenchTarget implements BenchTarget {

    public static final String LABEL = "WorldData Core";
    public static final String FILTER_BLOCK = "minecraft:stone";

    private final String dimension;
    private final BenchPositions positions;
    private final boolean acquire;
    private final WDCChunkResult[] handles;
    private final WDCBlockFilter filter = WDCBlockFilter.ofId(FILTER_BLOCK);

    private CoreBenchTarget(String dimension, BenchPositions positions, boolean acquire, WDCChunkResult[] handles) {
        this.dimension = dimension;
        this.positions = positions;
        this.acquire = acquire;
        this.handles = handles;
    }

    public static ChunkProbe probe(String dimension) {
        return (chunkX, chunkZ) -> {
            WDCChunkResult result = WDC.requests().chunk(dimension, chunkX, chunkZ);
            if (result.freshness() == WDCFreshness.LIVE) return null;
            return "chunk " + chunkX + "," + chunkZ + " is not LIVE in WorldData Core (" + result.freshness() + ", "
                    + result.acquisitionStatus() + (result.isAcquiring() ? ", still being acquired so try again in a moment" : "") + ")";
        };
    }

    public static CoreBenchTarget create(String dimension, BenchPositions positions, BenchMode mode) {
        WDCChunkResult[] handles = new WDCChunkResult[positions.slots()];
        int[] chunkXs = positions.chunkXs();
        int[] chunkZs = positions.chunkZs();
        for (int i = 0; i < chunkXs.length; i++) {
            WDCChunkResult result = WDC.requests().chunk(dimension, chunkXs[i], chunkZs[i]);
            if (result.freshness() != WDCFreshness.LIVE) {
                throw new IllegalStateException("chunk " + chunkXs[i] + "," + chunkZs[i]
                        + " stopped being LIVE in WorldData Core before the run (" + result.freshness() + ")");
            }
            handles[positions.slot(chunkXs[i] << 4, chunkZs[i] << 4)] = result;
        }
        return new CoreBenchTarget(dimension, positions, mode.acquiresEveryCall(), handles);
    }

    @Override
    public String label() {
        return LABEL;
    }

    @Override
    public String timed(BenchApi api, BenchMode mode) {
        String read = api.scan()
                ? api.commandName() + "(" + FILTER_BLOCK + ") over one whole chunk"
                : api == BenchApi.MATCHES_AT ? "matchesAt(x, y, z, " + FILTER_BLOCK + ")" : api.commandName() + "(x, y, z)";
        if (mode.acquiresEveryCall()) {
            return "WDC.requests().chunk(dimension, chunkX, chunkZ) plus " + read + " on every call";
        }
        return read + " on a chunk result resolved before the timed loop; picking that result by position is timed, the chunk lookup is not";
    }

    @Override
    public long call(BenchApi api, int index) {
        int p = index & positions.mask();
        int x = positions.x(p);
        int y = positions.y(p);
        int z = positions.z(p);
        WDCChunkResult result = acquire
                ? WDC.requests().chunk(dimension, x >> 4, z >> 4)
                : handles[positions.slot(x, z)];
        return switch (api) {
            case STATE_ID_AT -> result.stateIdAt(x, y, z);
            case BLOCK_AT -> result.blockAt(x, y, z).stateId();
            case MATERIAL_AT -> result.materialAt(x, y, z).ordinal();
            case WATER_AT -> {
                WDCWaterInfo water = result.waterAt(x, y, z);
                yield water.isWater() ? (water.isSource() ? 3L : 1L) : 0L;
            }
            case IS_PRESENT -> result.isPresent(x, y, z) ? 1L : 0L;
            case MATCHES_AT -> result.matchesAt(x, y, z, filter) ? 1L : 0L;
            case HAS_BLOCK -> result.hasBlock(filter) ? 1L : 0L;
            case COUNT_BLOCKS -> result.countBlocks(filter);
        };
    }
}
