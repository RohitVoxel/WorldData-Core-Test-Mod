package net.worlddata.benchmark.bench;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.worlddata.benchmark.common.bench.BenchApi;
import net.worlddata.benchmark.common.bench.BenchMode;
import net.worlddata.benchmark.common.bench.BenchPositions;
import net.worlddata.benchmark.common.bench.BenchTarget;
import net.worlddata.benchmark.common.bench.ChunkProbe;

public final class VanillaBenchTarget implements BenchTarget {

    public static final String LABEL = "Minecraft";

    private final ServerLevel level;
    private final BenchPositions positions;
    private final LevelChunk[] chunks;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private VanillaBenchTarget(ServerLevel level, BenchPositions positions, LevelChunk[] chunks) {
        this.level = level;
        this.positions = positions;
        this.chunks = chunks;
    }

    public static ChunkProbe probe(ServerLevel level) {
        return (chunkX, chunkZ) -> level.getChunkSource().getChunkNow(chunkX, chunkZ) != null
                ? null
                : "chunk " + chunkX + "," + chunkZ + " is not loaded in Minecraft";
    }

    public static VanillaBenchTarget create(ServerLevel level, BenchPositions positions) {
        LevelChunk[] chunks = new LevelChunk[positions.slots()];
        int[] chunkXs = positions.chunkXs();
        int[] chunkZs = positions.chunkZs();
        for (int i = 0; i < chunkXs.length; i++) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkXs[i], chunkZs[i]);
            if (chunk == null) {
                throw new IllegalStateException("chunk " + chunkXs[i] + "," + chunkZs[i] + " is no longer loaded in Minecraft");
            }
            chunks[positions.slot(chunkXs[i] << 4, chunkZs[i] << 4)] = chunk;
        }
        return new VanillaBenchTarget(level, positions, chunks);
    }

    @Override
    public String label() {
        return LABEL;
    }

    @Override
    public String timed(BenchApi api, BenchMode mode) {
        return switch (api) {
            case STATE_ID_AT, BLOCK_AT -> "ServerLevel.getBlockState(pos) plus Block.getId(state) on every call, Minecraft looks the chunk up itself each time";
            case MATERIAL_AT -> "ServerLevel.getBlockState(pos) plus a tag classification (air, water, lava, leaves, wood, sand, dirt, other) on every call";
            case WATER_AT -> "ServerLevel.getBlockState(pos).getFluidState() water and source check on every call";
            case IS_PRESENT -> "ServerLevel.getBlockState(pos).isAir() on every call";
            case MATCHES_AT -> "ServerLevel.getBlockState(pos).is(Blocks.STONE) on every call";
            case HAS_BLOCK -> "LevelChunkSection.getBlockState over the non-empty sections of one chunk resolved before the timed loop, stops at the first Blocks.STONE";
            case COUNT_BLOCKS -> "LevelChunkSection.getBlockState over the non-empty sections of one chunk resolved before the timed loop, counts Blocks.STONE";
        };
    }

    @Override
    public long call(BenchApi api, int index) {
        int p = index & positions.mask();
        int x = positions.x(p);
        int y = positions.y(p);
        int z = positions.z(p);
        return switch (api) {
            case STATE_ID_AT, BLOCK_AT -> Block.getId(level.getBlockState(cursor.set(x, y, z)));
            case MATERIAL_AT -> classify(level.getBlockState(cursor.set(x, y, z)));
            case WATER_AT -> {
                FluidState fluid = level.getBlockState(cursor.set(x, y, z)).getFluidState();
                yield fluid.is(FluidTags.WATER) ? (fluid.isSource() ? 3L : 1L) : 0L;
            }
            case IS_PRESENT -> level.getBlockState(cursor.set(x, y, z)).isAir() ? 0L : 1L;
            case MATCHES_AT -> level.getBlockState(cursor.set(x, y, z)).is(Blocks.STONE) ? 1L : 0L;
            case HAS_BLOCK -> scan(chunks[positions.slot(x, z)], true) > 0 ? 1L : 0L;
            case COUNT_BLOCKS -> scan(chunks[positions.slot(x, z)], false);
        };
    }

    private static long classify(BlockState state) {
        if (state.isAir()) return 0L;
        if (state.getFluidState().is(FluidTags.WATER)) return 9L;
        if (state.getFluidState().is(FluidTags.LAVA)) return 10L;
        if (state.is(BlockTags.LEAVES)) return 12L;
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS)) return 15L;
        if (state.is(BlockTags.SAND)) return 4L;
        if (state.is(BlockTags.DIRT)) return 2L;
        return 1L;
    }

    private static int scan(LevelChunk chunk, boolean stopAtFirst) {
        int count = 0;
        for (LevelChunkSection section : chunk.getSections()) {
            if (section.hasOnlyAir()) continue;
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        if (section.getBlockState(lx, ly, lz).is(Blocks.STONE)) {
                            count++;
                            if (stopAtFirst) return count;
                        }
                    }
                }
            }
        }
        return count;
    }
}
