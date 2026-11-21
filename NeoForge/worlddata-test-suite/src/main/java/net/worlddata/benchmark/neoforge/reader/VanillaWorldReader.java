package net.worlddata.benchmark.neoforge.reader;

import net.worlddata.benchmark.common.pack.AllocationMeter;
import net.worlddata.benchmark.common.pack.Arm;
import net.worlddata.benchmark.common.pack.CallKind;
import net.worlddata.benchmark.common.pack.CallRecorder;
import net.worlddata.benchmark.common.pack.ChunkLoader;
import net.worlddata.benchmark.common.pack.LookupSet;
import net.worlddata.benchmark.common.pack.Outcome;
import net.worlddata.benchmark.common.pack.ReadAction;
import net.worlddata.benchmark.common.pack.ThrashProbe;
import net.worlddata.benchmark.common.pack.WorldReader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

public final class VanillaWorldReader implements WorldReader, ChunkLoader {

    private static final int HOLD_EXPIRY_TICKS = 6000;
    private static final int PIN_EXPIRY_TICKS = 1_728_000;
    private static final TicketType<ChunkPos> HOLD_TICKET = TicketType.create("worlddata_core_test:hold", Comparator.comparingLong(ChunkPos::toLong), HOLD_EXPIRY_TICKS);
    private static final TicketType<ChunkPos> PIN_TICKET = TicketType.create("worlddata_core_test:pin", Comparator.comparingLong(ChunkPos::toLong), PIN_EXPIRY_TICKS);

    private static final long PENDING_DEADLINE_NANOS = 60_000_000_000L;
    private static final int FIND_LIMIT = 256;
    private static final int VISIT_LIMIT = 64;

    private final Supplier<ServerLevel> levelSupplier;
    private final CallRecorder recorder;
    private final long forceLoadCallCap;
    private final int uniqueCap;
    private final String dimension;
    private final HashMap<Long, FutureSlot> futures = new HashMap<>();
    private final HashMap<Long, Integer> refs = new HashMap<>();
    private final HashSet<Long> unique = new HashSet<>();
    private final List<int[]> pins = new ArrayList<>();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final int[] found = new int[FIND_LIMIT];
    private final StatePredicate[] tagPredicates = new StatePredicate[LookupSet.TAGS.length];
    private final StatePredicate[] namePredicates = new StatePredicate[LookupSet.BLOCKS.length];
    private final StatePredicate[] idPredicates = new StatePredicate[LookupSet.BLOCKS.length];
    private final int[][] lookupStateIds = new int[LookupSet.BLOCKS.length][];
    private final List<TagKey<Block>> stateTagKeys = new ArrayList<>();
    private ExecutorService dispatcher;
    private long digest;
    private long forceLoadCalls;
    private long forceLoadBlocked;

    private interface StatePredicate {
        boolean test(BlockState state);
    }

    private static final class FutureSlot {
        final long startNanos = System.nanoTime();
        final CompletableFuture<ChunkResult<ChunkAccess>> result = new CompletableFuture<>();

        boolean failed() {
            return result.isDone() && (result.isCompletedExceptionally() || !result.join().isSuccess());
        }

        void finish(ChunkResult<ChunkAccess> value, Throwable error, CallRecorder recorder) {
            boolean ok = error == null && value != null && value.isSuccess();
            recorder.record(CallKind.V_CHUNK_FUTURE, System.nanoTime() - startNanos, ok ? Outcome.PASS : Outcome.FAIL);
            if (error != null) result.completeExceptionally(error);
            else result.complete(value);
        }
    }

    public VanillaWorldReader(Supplier<ServerLevel> levelSupplier, CallRecorder recorder, long forceLoadCallCap, int uniqueCap) {
        this.levelSupplier = levelSupplier;
        this.recorder = recorder;
        this.forceLoadCallCap = forceLoadCallCap;
        this.uniqueCap = uniqueCap;
        ServerLevel level = levelSupplier.get();
        this.dimension = level == null ? "minecraft:overworld" : level.dimension().location().toString();
        for (int i = 0; i < LookupSet.TAGS.length; i++) {
            TagKey<Block> key = TagKey.create(Registries.BLOCK, ResourceLocation.tryParse(LookupSet.TAGS[i]));
            tagPredicates[i] = state -> state.is(key);
        }
        for (String id : LookupSet.STATE_TAG_IDS) stateTagKeys.add(TagKey.create(Registries.BLOCK, ResourceLocation.tryParse(id)));
        for (int i = 0; i < LookupSet.BLOCKS.length; i++) {
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(LookupSet.BLOCKS[i]));
            namePredicates[i] = state -> state.is(block);
            List<BlockState> states = block.getStateDefinition().getPossibleStates();
            BitSet bits = new BitSet();
            int[] ids = new int[states.size()];
            for (int k = 0; k < ids.length; k++) {
                ids[k] = Block.getId(states.get(k));
                bits.set(ids[k]);
            }
            lookupStateIds[i] = ids;
            idPredicates[i] = state -> bits.get(Block.getId(state));
        }
    }

    @Override
    public Arm arm() {
        return Arm.VANILLA;
    }

    @Override
    public String dimension() {
        return dimension;
    }

    @Override
    public long lastDigest() {
        return digest;
    }

    @Override
    public long chunkToken(int chunkX, int chunkZ) {
        return ChunkPos.asLong(chunkX, chunkZ);
    }

    @Override
    public Outcome read(ReadAction action, int x, int y, int z, int extra) {
        ServerLevel level = levelSupplier.get();
        if (level == null) return Outcome.UNAVAILABLE;
        int cx = x >> 4;
        int cz = z >> 4;
        switch (action.family()) {
            case POINT, BIOME, LAYER, RAW, FILTER -> {
                LevelChunk chunk = loadedChunk(level, cx, cz);
                if (chunk == null) return missing(cx, cz);
                return readChunkData(level, chunk, action, x, y, z, extra);
            }
            case SPATIAL -> {
                return readSpatial(level, action, x, y, z, extra);
            }
            case LOOKUP -> {
                if (action.registryLookup()) return readRegistry(action, extra);
                LevelChunk chunk = loadedChunk(level, cx, cz);
                if (chunk == null) return missing(cx, cz);
                return readLookupChunk(level, chunk, action, x, y, z, extra);
            }
            case LIGHT -> {
                if (!level.hasChunk(cx, cz)) return missing(cx, cz);
                cursor.set(x, y, z);
                long t = System.nanoTime();
                int sky = level.getBrightness(LightLayer.SKY, cursor);
                int block = level.getBrightness(LightLayer.BLOCK, cursor);
                recorder.record(CallKind.V_BRIGHTNESS, System.nanoTime() - t, Outcome.PASS);
                digest = ((long) sky << 4) | block;
                return Outcome.PASS;
            }
            case CHUNK_ENTRY -> {
                return readChunkEntry(level, action, cx, cz);
            }
            case WORLD -> {
                long t = System.nanoTime();
                long time = level.getDayTime();
                boolean raining = level.isRaining();
                recorder.record(CallKind.V_WORLD_TIME, System.nanoTime() - t, Outcome.PASS);
                digest = time ^ (raining ? 1L : 0L);
                return Outcome.PASS;
            }
            default -> {
                return Outcome.FAIL;
            }
        }
    }

    private static boolean ready(ServerLevel level, int cx, int cz) {
        return level.getChunkSource().getChunkNow(cx, cz) != null;
    }

    private LevelChunk loadedChunk(ServerLevel level, int cx, int cz) {
        long t = System.nanoTime();
        boolean loaded = level.hasChunk(cx, cz);
        recorder.record(CallKind.V_HAS_CHUNK, System.nanoTime() - t, loaded ? Outcome.PASS : Outcome.UNLOADED);
        if (!loaded || !ready(level, cx, cz)) return null;
        long g = System.nanoTime();
        LevelChunk chunk = level.getChunk(cx, cz);
        recorder.record(CallKind.V_GET_CHUNK, System.nanoTime() - g, Outcome.PASS);
        return chunk;
    }

    private Outcome missing(int cx, int cz) {
        return futures.containsKey(ChunkPos.asLong(cx, cz)) ? Outcome.PENDING : Outcome.UNLOADED;
    }

    private Outcome readChunkData(ServerLevel level, LevelChunk chunk, ReadAction action, int x, int y, int z, int extra) {
        cursor.set(x, y, z);
        long t = System.nanoTime();
        switch (action) {
            case STATE_ID, IS_PRESENT, BLOCK_AT, MATCHES_AT -> {
                BlockState state = level.getBlockState(cursor);
                recorder.record(CallKind.V_BLOCK_STATE, System.nanoTime() - t, Outcome.PASS);
                digest = action == ReadAction.IS_PRESENT ? (state.isAir() ? 0L : 1L) : action == ReadAction.MATCHES_AT ? (matches(state, extra) ? 1L : 0L) : Block.getId(state);
            }
            case MATERIAL_AT -> {
                BlockState state = level.getBlockState(cursor);
                int material = classify(state);
                recorder.record(CallKind.V_BLOCK_STATE_TAGS, System.nanoTime() - t, Outcome.PASS);
                digest = material;
            }
            case WATER_AT, IS_SUBMERGED -> {
                FluidState fluid = level.getBlockState(cursor).getFluidState();
                boolean water = fluid.is(FluidTags.WATER);
                recorder.record(CallKind.V_FLUID_STATE, System.nanoTime() - t, Outcome.PASS);
                digest = (water ? 1L : 0L) | (fluid.isSource() ? 2L : 0L);
            }
            case BIOME -> {
                Biome biome = level.getBiome(cursor).value();
                float temperature = biome.getBaseTemperature();
                boolean precipitation = biome.hasPrecipitation();
                recorder.record(CallKind.V_BIOME, System.nanoTime() - t, Outcome.PASS);
                digest = Float.floatToIntBits(temperature) ^ (precipitation ? 1L : 0L);
            }
            case HEIGHTMAP -> {
                int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                recorder.record(CallKind.V_HEIGHT, System.nanoTime() - t, Outcome.PASS);
                digest = height;
            }
            case COLUMN -> {
                int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                int floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
                cursor.set(x, surface - 1, z);
                BlockState top = level.getBlockState(cursor);
                recorder.record(CallKind.V_HEIGHT_COLUMN, System.nanoTime() - t, Outcome.PASS);
                digest = ((long) surface << 20) ^ ((long) floor << 8) ^ Block.getId(top);
            }
            case OCCUPANCY -> {
                int index = chunk.getSectionIndex(y);
                boolean empty = index < 0 || index >= chunk.getSectionsCount() || chunk.getSection(index).hasOnlyAir();
                recorder.record(CallKind.V_SECTION_EMPTY, System.nanoTime() - t, Outcome.PASS);
                digest = empty ? 1L : 0L;
            }
            case BLOCK_INDEX -> {
                int solid = 0;
                for (int yy = y - 8; yy < y + 8; yy++) {
                    cursor.set(x, yy, z);
                    if (!level.getBlockState(cursor).isAir()) solid++;
                }
                recorder.record(CallKind.V_COLUMN_SCAN, System.nanoTime() - t, Outcome.PASS);
                digest = solid;
            }
            case RAW_VIEW -> {
                int occupied = 0;
                for (int i = 0; i < chunk.getSectionsCount(); i++) if (!chunk.getSection(i).hasOnlyAir()) occupied++;
                recorder.record(CallKind.V_CHUNK_SECTIONS, System.nanoTime() - t, Outcome.PASS);
                digest = occupied;
            }
            case HAS_BLOCK -> {
                boolean any = scanChunk(level, chunk, extra, true) > 0;
                recorder.record(CallKind.V_CHUNK_SCAN, System.nanoTime() - t, Outcome.PASS);
                digest = any ? 1L : 0L;
            }
            case COUNT_BLOCKS -> {
                int count = scanChunk(level, chunk, extra, false);
                recorder.record(CallKind.V_CHUNK_SCAN, System.nanoTime() - t, Outcome.PASS);
                digest = count;
            }
            case FIND_BLOCKS -> {
                int count = findInChunk(level, chunk, 1 + extra % 3);
                recorder.record(CallKind.V_CHUNK_SCAN, System.nanoTime() - t, Outcome.PASS);
                digest = count > 0 ? ((long) count << 16) | found[count - 1] : 0L;
            }
            case VISIT_BLOCKS -> {
                int count = visitWindow(level, chunk, y, extra);
                recorder.record(CallKind.V_WINDOW_SCAN, System.nanoTime() - t, Outcome.PASS);
                digest = count;
            }
            default -> {
                return Outcome.FAIL;
            }
        }
        return Outcome.PASS;
    }

    private Outcome readLookupChunk(ServerLevel level, LevelChunk chunk, ReadAction action, int x, int y, int z, int extra) {
        int block = LookupSet.block(extra);
        int tag = LookupSet.tag(extra);
        long t = System.nanoTime();
        switch (action) {
            case FIND_BY_TAG, COUNT_BY_TAG -> digest = scanChunk(level, chunk, tagPredicates[tag], false);
            case FIND_BY_NAME, COUNT_BY_NAME -> digest = scanChunk(level, chunk, namePredicates[block], false);
            case FIND_BY_STATE_ID, COUNT_BY_STATE_ID -> digest = scanChunk(level, chunk, idPredicates[block], false);
            case HAS_BY_TAG -> digest = scanChunk(level, chunk, tagPredicates[tag], true) > 0 ? 1L : 0L;
            case HAS_BY_NAME -> digest = scanChunk(level, chunk, namePredicates[block], true) > 0 ? 1L : 0L;
            case HAS_BY_STATE_ID -> digest = scanChunk(level, chunk, idPredicates[block], true) > 0 ? 1L : 0L;
            case HAS_TAG_AT -> {
                cursor.set(x, y, z);
                digest = level.getBlockState(cursor).is(stateTagKeys.get(LookupSet.stateTag(extra))) ? 1L : 0L;
            }
            default -> {
                return Outcome.FAIL;
            }
        }
        recorder.record(action.vanilla(), System.nanoTime() - t, Outcome.PASS);
        return Outcome.PASS;
    }

    private Outcome readRegistry(ReadAction action, int extra) {
        int block = LookupSet.block(extra);
        String name = LookupSet.BLOCKS[block];
        int stateId = lookupStateIds[block][0];
        long t = System.nanoTime();
        switch (action) {
            case STATE_ID_OF -> digest = Block.getId(BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(name)).defaultBlockState());
            case STATE_IDS_OF_BLOCK -> {
                List<BlockState> states = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(name)).getStateDefinition().getPossibleStates();
                int[] ids = new int[states.size()];
                for (int i = 0; i < ids.length; i++) ids[i] = Block.getId(states.get(i));
                digest = ids.length > 0 ? ((long) ids.length << 32) | (ids[ids.length - 1] & 0xFFFFFFFFL) : 0L;
            }
            case STATE_IDS_OF_TAG -> {
                TagKey<Block> key = TagKey.create(Registries.BLOCK, ResourceLocation.tryParse(LookupSet.TAGS[LookupSet.tag(extra)]));
                int count = 0;
                for (Block candidate : BuiltInRegistries.BLOCK) {
                    if (candidate.builtInRegistryHolder().is(key)) count += candidate.getStateDefinition().getPossibleStates().size();
                }
                digest = count;
            }
            case BLOCK_ID_OF -> digest = BuiltInRegistries.BLOCK.getKey(Block.stateById(stateId).getBlock()).toString().length();
            case STATE_INFO -> {
                BlockState state = Block.stateById(stateId);
                digest = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().length() + state.getValues().size();
            }
            case STATE_HAS_TAG -> digest = Block.stateById(stateId).is(stateTagKeys.get(LookupSet.stateTag(extra))) ? 1L : 0L;
            default -> {
                return Outcome.FAIL;
            }
        }
        recorder.record(action.vanilla(), System.nanoTime() - t, Outcome.PASS);
        return Outcome.PASS;
    }

    private int scanChunk(ServerLevel level, LevelChunk chunk, StatePredicate predicate, boolean stopAtFirst) {
        int count = 0;
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int s = 0; s < chunk.getSectionsCount(); s++) {
            if (chunk.getSection(s).hasOnlyAir()) continue;
            int baseY = chunk.getMinBuildHeight() + s * 16;
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        cursor.set(baseX + lx, baseY + ly, baseZ + lz);
                        if (predicate.test(level.getBlockState(cursor))) {
                            count++;
                            if (stopAtFirst) return count;
                        }
                    }
                }
            }
        }
        return count;
    }

    private static boolean matches(BlockState state, int variant) {
        return switch (variant & 3) {
            case 0 -> state.is(Blocks.STONE);
            case 1 -> state.is(Blocks.DIRT);
            case 2 -> state.is(BlockTags.LEAVES);
            default -> state.is(Blocks.DEEPSLATE);
        };
    }

    private static int classify(BlockState state) {
        if (state.isAir()) return 0;
        if (state.getFluidState().is(FluidTags.WATER)) return 9;
        if (state.getFluidState().is(FluidTags.LAVA)) return 10;
        if (state.is(BlockTags.LEAVES)) return 12;
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS)) return 15;
        if (state.is(BlockTags.SAND)) return 4;
        if (state.is(BlockTags.DIRT)) return 2;
        return 1;
    }

    private int scanChunk(ServerLevel level, LevelChunk chunk, int variant, boolean stopAtFirst) {
        int count = 0;
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int s = 0; s < chunk.getSectionsCount(); s++) {
            if (chunk.getSection(s).hasOnlyAir()) continue;
            int baseY = chunk.getMinBuildHeight() + s * 16;
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        cursor.set(baseX + lx, baseY + ly, baseZ + lz);
                        if (matches(level.getBlockState(cursor), variant)) {
                            count++;
                            if (stopAtFirst) return count;
                        }
                    }
                }
            }
        }
        return count;
    }

    private int findInChunk(ServerLevel level, LevelChunk chunk, int variant) {
        int count = 0;
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int s = 0; s < chunk.getSectionsCount() && count < FIND_LIMIT; s++) {
            if (chunk.getSection(s).hasOnlyAir()) continue;
            int baseY = chunk.getMinBuildHeight() + s * 16;
            for (int ly = 0; ly < 16 && count < FIND_LIMIT; ly++) {
                for (int lz = 0; lz < 16 && count < FIND_LIMIT; lz++) {
                    for (int lx = 0; lx < 16 && count < FIND_LIMIT; lx++) {
                        cursor.set(baseX + lx, baseY + ly, baseZ + lz);
                        if (matches(level.getBlockState(cursor), variant)) found[count++] = (ly << 8) | (lz << 4) | lx;
                    }
                }
            }
        }
        return count;
    }

    private int visitWindow(ServerLevel level, LevelChunk chunk, int y, int variant) {
        int index = chunk.getSectionIndex(y);
        if (index < 0 || index >= chunk.getSectionsCount()) return 0;
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int baseY = chunk.getMinBuildHeight() + index * 16;
        int count = 0;
        for (int ly = 0; ly < 16; ly++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    cursor.set(baseX + lx, baseY + ly, baseZ + lz);
                    if (matches(level.getBlockState(cursor), variant) && ++count >= VISIT_LIMIT) return count;
                }
            }
        }
        return count;
    }

    private Outcome readSpatial(ServerLevel level, ReadAction action, int x, int y, int z, int extra) {
        int half = 2 + extra % 5;
        int minX = x - half;
        int maxX = x + half;
        int minZ = z - half;
        int maxZ = z + half;
        if (action == ReadAction.VISIT_COLUMN) {
            minX = x;
            maxX = x;
            minZ = z;
            maxZ = z;
        } else if (action == ReadAction.SAMPLE_GRID) {
            minX = x - 8;
            maxX = x + 8;
            minZ = z - 8;
            maxZ = z + 8;
        }
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                if (!ready(level, cx, cz)) return Outcome.UNLOADED;
            }
        }
        long t = System.nanoTime();
        long sum = 0L;
        switch (action) {
            case SUMMARIZE_BOX, VISIT_BOX -> {
                for (int xx = minX; xx <= maxX; xx++) for (int zz = minZ; zz <= maxZ; zz++) for (int yy = y - half; yy <= y + half; yy++) {
                    cursor.set(xx, yy, zz);
                    if (!level.getBlockState(cursor).isAir()) sum++;
                }
                recorder.record(CallKind.V_BOX_SCAN, System.nanoTime() - t, Outcome.PASS);
            }
            case SUMMARIZE_SPHERE, VISIT_SPHERE -> {
                int r2 = half * half;
                for (int xx = minX; xx <= maxX; xx++) for (int zz = minZ; zz <= maxZ; zz++) for (int yy = y - half; yy <= y + half; yy++) {
                    int dx = xx - x;
                    int dy = yy - y;
                    int dz = zz - z;
                    if (dx * dx + dy * dy + dz * dz > r2) continue;
                    cursor.set(xx, yy, zz);
                    if (!level.getBlockState(cursor).isAir()) sum++;
                }
                recorder.record(CallKind.V_BOX_SCAN, System.nanoTime() - t, Outcome.PASS);
            }
            case VISIT_COLUMN -> {
                for (int yy = y - 16; yy <= y + 16; yy++) {
                    cursor.set(x, yy, z);
                    if (!level.getBlockState(cursor).isAir()) sum++;
                }
                recorder.record(CallKind.V_COLUMN_SCAN, System.nanoTime() - t, Outcome.PASS);
            }
            default -> {
                for (int gx = 0; gx < 4; gx++) for (int gz = 0; gz < 4; gz++) {
                    sum += level.getHeight(Heightmap.Types.MOTION_BLOCKING, minX + gx * 4, minZ + gz * 4);
                }
                recorder.record(CallKind.V_GRID_SCAN, System.nanoTime() - t, Outcome.PASS);
            }
        }
        digest = sum;
        return Outcome.PASS;
    }

    private Outcome readChunkEntry(ServerLevel level, ReadAction action, int cx, int cz) {
        long token = ChunkPos.asLong(cx, cz);
        long t = System.nanoTime();
        switch (action) {
            case AVAILABILITY -> {
                boolean loaded = level.hasChunk(cx, cz);
                recorder.record(CallKind.V_HAS_CHUNK, System.nanoTime() - t, loaded ? Outcome.PASS : Outcome.UNLOADED);
                digest = loaded ? 1L : 0L;
                if (loaded) return Outcome.PASS;
                return futures.containsKey(token) ? Outcome.PENDING : Outcome.UNLOADED;
            }
            default -> {
                boolean full = ready(level, cx, cz);
                long g = System.nanoTime();
                boolean loaded = full && level.hasChunk(cx, cz);
                LevelChunk chunk = loaded ? level.getChunk(cx, cz) : null;
                recorder.record(CallKind.V_GET_CHUNK, System.nanoTime() - g, loaded ? Outcome.PASS : Outcome.UNLOADED);
                digest = chunk == null ? 0L : chunk.getInhabitedTime();
                return loaded ? Outcome.PASS : Outcome.UNLOADED;
            }
        }
    }

    private boolean refuse(int minX, int minZ, int maxX, int maxZ) {
        if (forceLoadCalls >= forceLoadCallCap) {
            forceLoadBlocked++;
            return true;
        }
        int fresh = 0;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) if (!unique.contains(ChunkPos.asLong(cx, cz))) fresh++;
        }
        if (unique.size() + fresh > uniqueCap) {
            forceLoadBlocked++;
            return true;
        }
        forceLoadCalls++;
        return false;
    }

    private boolean allLoaded(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) if (!ready(level, cx, cz)) return false;
        }
        return true;
    }

    @Override
    public Outcome request(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        ServerLevel level = levelSupplier.get();
        if (level == null) return Outcome.UNAVAILABLE;
        if (refuse(minChunkX, minChunkZ, maxChunkX, maxChunkZ)) return Outcome.UNAVAILABLE;
        ServerChunkCache cache = level.getChunkSource();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                long key = ChunkPos.asLong(cx, cz);
                unique.add(key);
                if (refs.merge(key, 1, Integer::sum) == 1) {
                    ChunkPos pos = new ChunkPos(cx, cz);
                    long a = System.nanoTime();
                    cache.addRegionTicket(HOLD_TICKET, pos, 0, pos);
                    recorder.record(CallKind.V_ADD_TICKET, System.nanoTime() - a, Outcome.PENDING);
                }
            }
        }
        boolean single = minChunkX == maxChunkX && minChunkZ == maxChunkZ;
        long key = ChunkPos.asLong(minChunkX, minChunkZ);
        if (single && !futures.containsKey(key) && !ready(level, minChunkX, minChunkZ)) startFuture(cache, minChunkX, minChunkZ, key);
        return allLoaded(level, minChunkX, minChunkZ, maxChunkX, maxChunkZ) ? Outcome.PASS : Outcome.PENDING;
    }

    private void ensureDispatcher() {
        if (dispatcher == null) {
            dispatcher = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "worlddata-core-test-chunk-future");
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    private void startFuture(ServerChunkCache cache, int cx, int cz, long key) {
        FutureSlot slot = new FutureSlot();
        futures.put(key, slot);
        ensureDispatcher();
        try {
            dispatcher.execute(() -> {
                try {
                    cache.getChunkFuture(cx, cz, ChunkStatus.FULL, true).whenComplete((value, error) -> slot.finish(value, error, recorder));
                } catch (Throwable error) {
                    slot.finish(null, error, recorder);
                }
            });
        } catch (RejectedExecutionException error) {
            slot.finish(null, error, recorder);
        }
    }

    @Override
    public Outcome pin(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        ServerLevel level = levelSupplier.get();
        if (level == null) return Outcome.UNAVAILABLE;
        if (refuse(minChunkX, minChunkZ, maxChunkX, maxChunkZ)) return Outcome.UNAVAILABLE;
        ServerChunkCache cache = level.getChunkSource();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                ChunkPos pos = new ChunkPos(cx, cz);
                unique.add(ChunkPos.asLong(cx, cz));
                long a = System.nanoTime();
                cache.addRegionTicket(PIN_TICKET, pos, 0, pos);
                recorder.record(CallKind.V_ADD_TICKET, System.nanoTime() - a, Outcome.PENDING);
            }
        }
        pins.add(new int[] {minChunkX, minChunkZ, maxChunkX, maxChunkZ});
        return allLoaded(level, minChunkX, minChunkZ, maxChunkX, maxChunkZ) ? Outcome.PASS : Outcome.PENDING;
    }

    @Override
    public Outcome status(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        ServerLevel level = levelSupplier.get();
        if (level == null) return Outcome.UNAVAILABLE;
        long t = System.nanoTime();
        Outcome outcome;
        boolean single = minChunkX == maxChunkX && minChunkZ == maxChunkZ;
        if (single) {
            long key = ChunkPos.asLong(minChunkX, minChunkZ);
            FutureSlot slot = futures.get(key);
            if (ready(level, minChunkX, minChunkZ)) {
                futures.remove(key);
                outcome = Outcome.PASS;
            } else if (slot != null && slot.failed()) {
                futures.remove(key);
                outcome = Outcome.FAIL;
            } else {
                outcome = Outcome.PENDING;
            }
        } else {
            outcome = allLoaded(level, minChunkX, minChunkZ, maxChunkX, maxChunkZ) ? Outcome.PASS : Outcome.PENDING;
        }
        recorder.record(CallKind.V_FUTURE_STATE, System.nanoTime() - t, outcome);
        return outcome;
    }

    @Override
    public void release(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        ServerLevel level = levelSupplier.get();
        if (level == null) return;
        ServerChunkCache cache = level.getChunkSource();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                long key = ChunkPos.asLong(cx, cz);
                Integer count = refs.get(key);
                if (count == null) continue;
                if (count > 1) {
                    refs.put(key, count - 1);
                    continue;
                }
                refs.remove(key);
                futures.remove(key);
                ChunkPos pos = new ChunkPos(cx, cz);
                long t = System.nanoTime();
                cache.removeRegionTicket(HOLD_TICKET, pos, 0, pos);
                recorder.record(CallKind.V_REMOVE_TICKET, System.nanoTime() - t, Outcome.PASS);
            }
        }
    }

    @Override
    public Outcome poll(long token) {
        ServerLevel level = levelSupplier.get();
        if (level == null) return Outcome.UNAVAILABLE;
        long t = System.nanoTime();
        int cx = ChunkPos.getX(token);
        int cz = ChunkPos.getZ(token);
        FutureSlot slot = futures.get(token);
        Outcome outcome;
        if (ready(level, cx, cz)) {
            outcome = Outcome.PASS;
        } else if (slot == null) {
            outcome = Outcome.UNLOADED;
        } else if (slot.failed()) {
            outcome = Outcome.FAIL;
        } else if (t - slot.startNanos > PENDING_DEADLINE_NANOS) {
            outcome = Outcome.TIMEOUT;
        } else {
            outcome = Outcome.PENDING;
        }
        recorder.record(CallKind.V_FUTURE_STATE, System.nanoTime() - t, outcome);
        return outcome;
    }

    @Override
    public String blockIdAt(int x, int y, int z) {
        ServerLevel level = levelSupplier.get();
        if (level == null || !ready(level, x >> 4, z >> 4)) return "";
        cursor.set(x, y, z);
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(cursor).getBlock()).toString();
    }

    @Override
    public ThrashProbe openThrash(int chunkX, int chunkZ) {
        ServerLevel level = levelSupplier.get();
        if (level == null || !ready(level, chunkX, chunkZ)) return null;
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        return (x, y, z) -> Block.getId(chunk.getBlockState(new BlockPos(x, y, z)));
    }

    @Override
    public double visitAllocationBytesPerCall(int chunkX, int chunkZ, int calls) {
        ServerLevel level = levelSupplier.get();
        if (level == null || !ready(level, chunkX, chunkZ)) return -1.0;
        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        int y = Math.max(chunk.getMinBuildHeight(), 56);
        return AllocationMeter.bytesPerCall(calls, () -> {
            for (int i = 0; i < calls; i++) visitWindow(level, chunk, y, i);
        });
    }

    @Override
    public long forceLoadCalls() {
        return forceLoadCalls;
    }

    @Override
    public long forceLoadBlocked() {
        return forceLoadBlocked;
    }

    @Override
    public int uniqueChunks() {
        return unique.size();
    }

    @Override
    public int heldChunks() {
        int pinned = 0;
        for (int[] pin : pins) pinned += (pin[2] - pin[0] + 1) * (pin[3] - pin[1] + 1);
        return refs.size() + pinned;
    }

    @Override
    public void releaseAll() {
        ServerLevel level = levelSupplier.get();
        if (level != null) {
            ServerChunkCache cache = level.getChunkSource();
            for (Long key : new ArrayList<>(refs.keySet())) {
                ChunkPos pos = new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key));
                cache.removeRegionTicket(HOLD_TICKET, pos, 0, pos);
            }
            for (int[] pin : pins) {
                for (int cx = pin[0]; cx <= pin[2]; cx++) {
                    for (int cz = pin[1]; cz <= pin[3]; cz++) {
                        ChunkPos pos = new ChunkPos(cx, cz);
                        cache.removeRegionTicket(PIN_TICKET, pos, 0, pos);
                    }
                }
            }
        }
        refs.clear();
        pins.clear();
        futures.clear();
        if (dispatcher != null) {
            dispatcher.shutdownNow();
            dispatcher = null;
        }
    }

    @Override
    public Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("reader", "Minecraft ServerLevel");
        info.put("dimension", dimension);
        info.put("forceLoadCalls", forceLoadCalls);
        return info;
    }
}
