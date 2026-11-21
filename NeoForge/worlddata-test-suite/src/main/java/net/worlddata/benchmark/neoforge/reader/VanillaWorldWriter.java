package net.worlddata.benchmark.neoforge.reader;

import net.worlddata.benchmark.common.pack.BlockCode;
import net.worlddata.benchmark.common.pack.CallKind;
import net.worlddata.benchmark.common.pack.CallRecorder;
import net.worlddata.benchmark.common.pack.Outcome;
import net.worlddata.benchmark.common.pack.WorldWriter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public final class VanillaWorldWriter implements WorldWriter {

    private final Supplier<ServerLevel> levelSupplier;
    private final CallRecorder recorder;
    private final BlockState[] states = new BlockState[BlockCode.values().length];
    private final Map<Long, BlockState> journal = new LinkedHashMap<>();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public VanillaWorldWriter(Supplier<ServerLevel> levelSupplier, CallRecorder recorder) {
        this.levelSupplier = levelSupplier;
        this.recorder = recorder;
        states[BlockCode.AIR.ordinal()] = Blocks.AIR.defaultBlockState();
        states[BlockCode.STONE.ordinal()] = Blocks.STONE.defaultBlockState();
        states[BlockCode.GLASS.ordinal()] = Blocks.GLASS.defaultBlockState();
        states[BlockCode.LAMP_ON.ordinal()] = Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, true);
        states[BlockCode.LAMP_OFF.ordinal()] = Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, false);
        states[BlockCode.WATER_L6.ordinal()] = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 6);
        states[BlockCode.WATER_L7.ordinal()] = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 7);
    }

    @Override
    public Outcome write(int x, int y, int z, BlockCode code) {
        ServerLevel level = levelSupplier.get();
        if (level == null) return Outcome.UNAVAILABLE;
        if (!level.hasChunk(x >> 4, z >> 4)) return Outcome.UNLOADED;
        cursor.set(x, y, z);
        long key = cursor.asLong();
        if (!journal.containsKey(key)) journal.put(key, level.getBlockState(cursor));
        BlockState target = states[code.ordinal()];
        long t = System.nanoTime();
        boolean changed = level.setBlock(cursor, target, Block.UPDATE_ALL);
        recorder.record(CallKind.V_SET_BLOCK, System.nanoTime() - t, Outcome.PASS);
        return changed || level.getBlockState(cursor) == target ? Outcome.PASS : Outcome.FAIL;
    }

    @Override
    public String blockId(int x, int y, int z) {
        ServerLevel level = levelSupplier.get();
        if (level == null || !level.hasChunk(x >> 4, z >> 4)) return "";
        cursor.set(x, y, z);
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(cursor).getBlock()).toString();
    }

    @Override
    public int restoreAll() {
        ServerLevel level = levelSupplier.get();
        int count = journal.size();
        if (level != null) {
            for (Map.Entry<Long, BlockState> entry : journal.entrySet()) {
                BlockPos pos = BlockPos.of(entry.getKey());
                if (level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) level.setBlock(pos, entry.getValue(), Block.UPDATE_ALL);
            }
        }
        journal.clear();
        return count;
    }

    @Override
    public int journalSize() {
        return journal.size();
    }
}
