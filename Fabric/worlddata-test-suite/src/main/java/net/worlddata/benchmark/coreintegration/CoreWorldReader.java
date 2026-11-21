package net.worlddata.benchmark.coreintegration;

import net.worlddata.benchmark.common.pack.AllocationMeter;
import net.worlddata.benchmark.common.pack.Arm;
import net.worlddata.benchmark.common.pack.BlockIds;
import net.worlddata.benchmark.common.pack.CallKind;
import net.worlddata.benchmark.common.pack.CallRecorder;
import net.worlddata.benchmark.common.pack.ChunkPos;
import net.worlddata.benchmark.common.pack.LookupSet;
import net.worlddata.benchmark.common.pack.Outcome;
import net.worlddata.benchmark.common.pack.ReadAction;
import net.worlddata.benchmark.common.pack.ThrashProbe;
import net.worlddata.benchmark.common.pack.WorldReader;
import net.wdc.core.api.WDC;
import net.wdc.core.api.capabilities.WDCCapability;
import net.wdc.core.api.request.WDCAcquisitionStatus;
import net.wdc.core.api.request.WDCAvailability;
import net.wdc.core.api.request.WDCBiomeInfo;
import net.wdc.core.api.request.WDCBlockFilter;
import net.wdc.core.api.request.WDCBlockIndex;
import net.wdc.core.api.request.WDCBlockInfo;
import net.wdc.core.api.request.WDCBlockMatch;
import net.wdc.core.api.request.WDCChunkResult;
import net.wdc.core.api.request.WDCChunkView;
import net.wdc.core.api.request.WDCColumn;
import net.wdc.core.api.request.WDCHeightKind;
import net.wdc.core.api.request.WDCHeightmap;
import net.wdc.core.api.request.WDCLightInfo;
import net.wdc.core.api.request.WDCLightResult;
import net.wdc.core.api.request.WDCMaterial;
import net.wdc.core.api.request.WDCSectionOccupancy;
import net.wdc.core.api.request.WDCSectionView;
import net.wdc.core.api.request.WDCStateTag;
import net.wdc.core.api.request.WDCWaterInfo;
import net.wdc.core.api.request.BlockVisitor;
import net.wdc.core.api.spatial.WDCGridSample;
import net.wdc.core.api.spatial.WDCRegionSummary;
import net.wdc.core.api.spatial.WDCVisitResult;
import net.wdc.core.api.world.WDCWorldSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class CoreWorldReader implements WorldReader {

    private static final int VISIT_LIMIT = 64;
    private static final WDCBlockFilter[] FILTERS = {
            WDCBlockFilter.ofId("minecraft:stone"),
            WDCBlockFilter.ofId("minecraft:dirt"),
            WDCBlockFilter.ofTag("minecraft:leaves"),
            WDCBlockFilter.ofId("minecraft:deepslate")
    };

    private static final class CountingVisitor implements BlockVisitor {
        int visited;

        @Override
        public boolean visit(int x, int y, int z, int stateId, String blockId) {
            visited++;
            return visited < VISIT_LIMIT;
        }
    }

    private final String dimension;
    private final CallRecorder recorder;
    private final CountingVisitor visitor = new CountingVisitor();
    private final boolean spatial;
    private final boolean light;
    private final boolean biome;
    private final WDCStateTag[] stateTags = new WDCStateTag[LookupSet.STATE_TAGS.length];
    private final int[][] knownStateIds = new int[LookupSet.BLOCKS.length][];
    private int knownStateCount = -1;
    private long digest;
    private final long[] lightMisses = new long[WDCLightResult.Status.values().length];
    private long exceptions;
    private String firstException = "";

    public CoreWorldReader(String dimension, CallRecorder recorder) {
        this.dimension = dimension;
        this.recorder = recorder;
        this.spatial = WDC.capabilities().supports(WDCCapability.SPATIAL_QUERIES);
        this.light = WDC.capabilities().supports(WDCCapability.LIGHT_QUERY);
        this.biome = WDC.capabilities().supports(WDCCapability.BIOME_DATA);
        for (int i = 0; i < stateTags.length; i++) stateTags[i] = WDCStateTag.valueOf(LookupSet.STATE_TAGS[i]);
    }

    public static boolean ready() {
        try {
            return WDC.capabilities().supports(WDCCapability.CHUNK_REQUESTS);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public Arm arm() {
        return Arm.CORE;
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
        return ChunkPos.pack(chunkX, chunkZ);
    }

    @Override
    public Outcome read(ReadAction action, int x, int y, int z, int extra) {
        try {
            return switch (action.family()) {
                case POINT, BIOME, LAYER, RAW, FILTER -> readChunkData(action, x, y, z, extra);
                case SPATIAL -> readSpatial(action, x, y, z, extra);
                case LOOKUP -> action.registryLookup() ? readRegistry(action, extra) : readLookupChunk(action, x, y, z, extra);
                case LIGHT -> readLight(x, y, z);
                case CHUNK_ENTRY -> readChunkEntry(action, x >> 4, z >> 4);
                case WORLD -> readWorld();
                default -> Outcome.FAIL;
            };
        } catch (RuntimeException e) {
            noteException(e);
            return Outcome.FAIL;
        }
    }

    private Outcome readChunkData(ReadAction action, int x, int y, int z, int extra) {
        int cx = x >> 4;
        int cz = z >> 4;
        long q = System.nanoTime();
        WDCChunkResult r = WDC.requests().chunk(dimension, cx, cz);
        Outcome gate = r.isUsable() ? Outcome.PASS : classify(r);
        recorder.record(CallKind.C_CHUNK_QUERY, System.nanoTime() - q, gate);
        if (gate != Outcome.PASS) return gate;
        int lx = x & 15;
        int lz = z & 15;
        long t = System.nanoTime();
        Outcome outcome = Outcome.PASS;
        switch (action) {
            case STATE_ID -> digest = r.stateIdAt(x, y, z);
            case IS_PRESENT -> digest = r.isPresent(x, y, z) ? 1L : 0L;
            case BLOCK_AT -> {
                WDCBlockInfo info = r.blockAt(x, y, z);
                digest = info.stateId();
            }
            case MATERIAL_AT -> {
                WDCMaterial material = r.materialAt(x, y, z);
                digest = material.ordinal();
            }
            case WATER_AT -> {
                WDCWaterInfo water = r.waterAt(x, y, z);
                digest = (water.isWater() ? 1L : 0L) | (water.isSource() ? 2L : 0L) | ((long) water.fluidLevel() << 4);
            }
            case IS_SUBMERGED -> digest = r.isSubmerged(x, y, z) ? 1L : 0L;
            case BIOME -> {
                WDCBiomeInfo info = biome ? r.biome() : null;
                if (info == null || !info.isPresent()) {
                    outcome = Outcome.UNAVAILABLE;
                } else {
                    digest = Float.floatToIntBits(info.rawTemperature()) ^ (info.hasPrecipitation() ? 1L : 0L);
                }
            }
            case HEIGHTMAP -> {
                WDCHeightmap heightmap = r.heightmap();
                if (heightmap == null) outcome = Outcome.UNAVAILABLE;
                else digest = heightmap.topY(lx, lz, WDCHeightKind.values()[extra & 3]);
            }
            case COLUMN -> {
                WDCColumn column = r.column();
                if (column == null) {
                    outcome = Outcome.UNAVAILABLE;
                } else {
                    digest = ((long) column.topSolidY(lx, lz) << 20) ^ ((long) column.topWaterY(lx, lz) << 8) ^ column.surfaceMaterial(lx, lz).ordinal() ^ (column.isWaterColumn(lx, lz) ? 1L : 0L);
                }
            }
            case OCCUPANCY -> {
                WDCSectionOccupancy occupancy = r.occupancy();
                if (occupancy == null) {
                    outcome = Outcome.UNAVAILABLE;
                } else {
                    int section = Math.floorDiv(y, 16);
                    digest = occupancy.countOf(section, WDCMaterial.STONE) + (occupancy.isEmpty(section) ? 1L : 0L);
                }
            }
            case BLOCK_INDEX -> {
                WDCBlockIndex index = r.blockIndex();
                if (index == null) {
                    outcome = Outcome.UNAVAILABLE;
                } else {
                    digest = index.materialAt(lx, lz, y).ordinal() + (index.isPresent(lx, lz, y) ? 100L : 0L);
                }
            }
            case RAW_VIEW -> {
                WDCChunkView view = r.raw();
                if (view == null) {
                    outcome = Outcome.UNAVAILABLE;
                } else {
                    int section = Math.floorDiv(y, 16);
                    WDCSectionView sectionView = view.hasSection(section) ? view.getSection(section) : null;
                    digest = view.sectionCount() + (sectionView == null ? 0L : sectionView.occupiedCount());
                }
            }
            case MATCHES_AT -> digest = r.matchesAt(x, y, z, FILTERS[extra & 3]) ? 1L : 0L;
            case HAS_BLOCK -> digest = r.hasBlock(FILTERS[extra & 3]) ? 1L : 0L;
            case COUNT_BLOCKS -> digest = r.countBlocks(FILTERS[extra & 3]);
            case FIND_BLOCKS -> {
                List<WDCBlockMatch> matches = r.findBlocks(FILTERS[1 + extra % 3]);
                digest = matches.size();
            }
            case VISIT_BLOCKS -> {
                visitor.visited = 0;
                r.visitBlocks(FILTERS[extra & 3], visitor);
                digest = visitor.visited;
            }
            default -> outcome = Outcome.FAIL;
        }
        recorder.record(action.core(), System.nanoTime() - t, outcome);
        return outcome;
    }

    private int[] stateIdsFor(int extra) {
        int count = WDC.blocks().stateCount();
        if (count != knownStateCount) {
            for (int i = 0; i < knownStateIds.length; i++) knownStateIds[i] = WDC.blocks().stateIdsOfBlock(LookupSet.BLOCKS[i]);
            knownStateCount = count;
        }
        int start = LookupSet.block(extra);
        for (int k = 0; k < knownStateIds.length; k++) {
            int[] ids = knownStateIds[(start + k) % knownStateIds.length];
            if (ids != null && ids.length > 0) return ids;
        }
        return null;
    }

    private Outcome readLookupChunk(ReadAction action, int x, int y, int z, int extra) {
        int cx = x >> 4;
        int cz = z >> 4;
        long q = System.nanoTime();
        WDCChunkResult r = WDC.requests().chunk(dimension, cx, cz);
        Outcome gate = r.isUsable() ? Outcome.PASS : classify(r);
        recorder.record(CallKind.C_CHUNK_QUERY, System.nanoTime() - q, gate);
        if (gate != Outcome.PASS) return gate;
        String tag = LookupSet.TAGS[LookupSet.tag(extra)];
        String name = LookupSet.BLOCKS[LookupSet.block(extra)];
        int[] ids = null;
        if (action == ReadAction.FIND_BY_STATE_ID || action == ReadAction.COUNT_BY_STATE_ID || action == ReadAction.HAS_BY_STATE_ID) {
            ids = stateIdsFor(extra);
            if (ids == null) return Outcome.UNAVAILABLE;
        }
        long t = System.nanoTime();
        Outcome outcome = Outcome.PASS;
        switch (action) {
            case FIND_BY_TAG -> digest = r.findBlocksByTag(tag).size();
            case FIND_BY_NAME -> digest = r.findBlocksByName(name).size();
            case FIND_BY_STATE_ID -> digest = r.findBlocksByStateId(ids).size();
            case COUNT_BY_TAG -> digest = r.countBlocksByTag(tag);
            case COUNT_BY_NAME -> digest = r.countBlocksByName(name);
            case COUNT_BY_STATE_ID -> digest = r.countBlocksByStateId(ids);
            case HAS_BY_TAG -> digest = r.hasBlockByTag(tag) ? 1L : 0L;
            case HAS_BY_NAME -> digest = r.hasBlockByName(name) ? 1L : 0L;
            case HAS_BY_STATE_ID -> digest = r.hasBlockByStateId(ids) ? 1L : 0L;
            case HAS_TAG_AT -> digest = r.hasTagAt(x, y, z, stateTags[LookupSet.stateTag(extra)]) ? 1L : 0L;
            default -> outcome = Outcome.FAIL;
        }
        recorder.record(action.core(), System.nanoTime() - t, outcome);
        return outcome;
    }

    private Outcome readRegistry(ReadAction action, int extra) {
        String name = LookupSet.BLOCKS[LookupSet.block(extra)];
        String tag = LookupSet.TAGS[LookupSet.tag(extra)];
        int stateId = 0;
        if (action == ReadAction.BLOCK_ID_OF || action == ReadAction.STATE_INFO || action == ReadAction.STATE_HAS_TAG) {
            int[] ids = stateIdsFor(extra);
            if (ids == null) return Outcome.UNAVAILABLE;
            stateId = ids[0];
        }
        long t = System.nanoTime();
        Outcome outcome = Outcome.PASS;
        switch (action) {
            case STATE_ID_OF -> digest = WDC.blocks().stateIdOf(name);
            case STATE_IDS_OF_BLOCK -> digest = WDC.blocks().stateIdsOfBlock(name).length;
            case STATE_IDS_OF_TAG -> digest = WDC.blocks().stateIdsOfTag(tag).length;
            case BLOCK_ID_OF -> digest = WDC.blocks().blockIdOf(stateId).length();
            case STATE_INFO -> digest = WDC.blocks().info(stateId).stateId();
            case STATE_HAS_TAG -> digest = WDC.blocks().hasTag(stateId, stateTags[LookupSet.stateTag(extra)]) ? 1L : 0L;
            default -> outcome = Outcome.FAIL;
        }
        recorder.record(action.core(), System.nanoTime() - t, outcome);
        return outcome;
    }

    private Outcome readSpatial(ReadAction action, int x, int y, int z, int extra) {
        if (!spatial) return Outcome.UNAVAILABLE;
        int half = 2 + extra % 5;
        long t = System.nanoTime();
        boolean complete;
        switch (action) {
            case SUMMARIZE_BOX -> {
                WDCRegionSummary summary = WDC.spatial().summarizeBox(dimension, x - half, y - half, z - half, x + half, y + half, z + half);
                complete = summary.coverage().complete();
                digest = summary.nonAirCount();
            }
            case SUMMARIZE_SPHERE -> {
                WDCRegionSummary summary = WDC.spatial().summarizeSphere(dimension, x, y, z, half);
                complete = summary.coverage().complete();
                digest = summary.nonAirCount();
            }
            case VISIT_BOX -> {
                visitor.visited = 0;
                WDCVisitResult result = WDC.spatial().visitBox(dimension, x - half, y - half, z - half, x + half, y + half, z + half, FILTERS[extra & 3], visitor);
                complete = result.coverage().complete();
                digest = result.visited();
            }
            case VISIT_SPHERE -> {
                visitor.visited = 0;
                WDCVisitResult result = WDC.spatial().visitSphere(dimension, x, y, z, half, FILTERS[extra & 3], visitor);
                complete = result.coverage().complete();
                digest = result.visited();
            }
            case VISIT_COLUMN -> {
                visitor.visited = 0;
                WDCVisitResult result = WDC.spatial().visitColumn(dimension, x, z, y - 16, y + 16, visitor);
                complete = result.coverage().complete();
                digest = result.visited();
            }
            default -> {
                WDCGridSample grid = WDC.spatial().sampleGrid(dimension, x - 8, z - 8, x + 8, z + 8, 4);
                complete = grid.coverage().complete();
                digest = grid.cellSize();
            }
        }
        Outcome outcome = complete ? Outcome.PASS : Outcome.UNLOADED;
        recorder.record(action.core(), System.nanoTime() - t, outcome);
        return outcome;
    }

    private Outcome readLight(int x, int y, int z) {
        if (!light) return Outcome.UNAVAILABLE;
        long t = System.nanoTime();
        Optional<WDCLightInfo> info = WDC.light().lightAt(dimension, x, y, z);
        boolean present = info.isPresent() && info.get().isPresent();
        recorder.record(CallKind.C_LIGHT_AT, System.nanoTime() - t, present ? Outcome.PASS : Outcome.UNAVAILABLE);
        if (!present) {
            lightMisses[WDC.light().read(dimension, x, y, z).status().ordinal()]++;
            return Outcome.UNAVAILABLE;
        }
        digest = info.get().combinedLight();
        return Outcome.PASS;
    }

    private Outcome readChunkEntry(ReadAction action, int cx, int cz) {
        long t = System.nanoTime();
        switch (action) {
            case AVAILABILITY -> {
                WDCAvailability availability = WDC.requests().availability(dimension, cx, cz);
                Outcome outcome = availability.isUsable() ? Outcome.PASS : availability == WDCAvailability.ACQUIRING ? Outcome.PENDING : Outcome.UNLOADED;
                recorder.record(CallKind.C_AVAILABILITY, System.nanoTime() - t, outcome);
                digest = availability.ordinal();
                return outcome;
            }
            default -> {
                WDCChunkResult result = WDC.requests().peek(dimension, cx, cz);
                Outcome outcome = result.isUsable() ? Outcome.PASS : Outcome.UNLOADED;
                recorder.record(CallKind.C_PEEK, System.nanoTime() - t, outcome);
                digest = result.freshness().ordinal();
                return outcome;
            }
        }
    }

    private Outcome readWorld() {
        long t = System.nanoTime();
        Optional<WDCWorldSnapshot> snapshot = WDC.world().snapshot(dimension);
        recorder.record(CallKind.C_WORLD_SNAPSHOT, System.nanoTime() - t, snapshot.isPresent() ? Outcome.PASS : Outcome.UNAVAILABLE);
        if (snapshot.isEmpty()) return Outcome.UNAVAILABLE;
        digest = snapshot.get().gameTime();
        return Outcome.PASS;
    }

    static Outcome classify(WDCChunkResult result) {
        WDCAcquisitionStatus status = result.acquisitionStatus();
        if (status == null) return Outcome.UNAVAILABLE;
        return switch (status) {
            case PENDING, DELIVERED -> Outcome.PENDING;
            case DATA_UNLOADED -> Outcome.UNLOADED;
            case NOT_AVAILABLE, NOT_REQUESTED -> Outcome.UNAVAILABLE;
        };
    }

    @Override
    public Outcome poll(long token) {
        try {
            int cx = ChunkPos.x(token);
            int cz = ChunkPos.z(token);
            long t = System.nanoTime();
            WDCAvailability availability = WDC.requests().availability(dimension, cx, cz);
            Outcome outcome = availability.isUsable() ? Outcome.PASS : availability == WDCAvailability.ACQUIRING ? Outcome.PENDING : Outcome.UNLOADED;
            recorder.record(CallKind.C_AVAILABILITY, System.nanoTime() - t, outcome);
            return outcome;
        } catch (RuntimeException e) {
            noteException(e);
            return Outcome.FAIL;
        }
    }

    @Override
    public String blockIdAt(int x, int y, int z) {
        try {
            WDCChunkResult result = WDC.requests().chunk(dimension, x >> 4, z >> 4);
            if (!result.isUsable()) return BlockIds.NO_DATA;
            WDCBlockInfo info = result.blockAt(x, y, z);
            if (!info.present()) return BlockIds.AIR;
            return info.blockId() == null ? BlockIds.NO_DATA : info.blockId();
        } catch (RuntimeException e) {
            noteException(e);
            return BlockIds.NO_DATA;
        }
    }

    @Override
    public ThrashProbe openThrash(int chunkX, int chunkZ) {
        try {
            if (!WDC.requests().chunk(dimension, chunkX, chunkZ).isUsable()) return null;
        } catch (RuntimeException e) {
            noteException(e);
            return null;
        }
        String dim = dimension;
        return (x, y, z) -> WDC.requests().chunk(dim, x >> 4, z >> 4).stateIdAt(x, y, z);
    }

    @Override
    public double visitAllocationBytesPerCall(int chunkX, int chunkZ, int calls) {
        try {
            WDCChunkResult result = WDC.requests().chunk(dimension, chunkX, chunkZ);
            if (!result.isUsable()) return -1.0;
            WDCBlockFilter filter = FILTERS[3];
            for (int i = 0; i < 20; i++) {
                visitor.visited = 0;
                result.visitBlocks(filter, visitor);
            }
            return AllocationMeter.bytesPerCall(calls, () -> {
                for (int i = 0; i < calls; i++) {
                    visitor.visited = 0;
                    result.visitBlocks(filter, visitor);
                }
            });
        } catch (RuntimeException e) {
            noteException(e);
            return -1.0;
        }
    }

    private String lightMissSummary() {
        StringBuilder out = new StringBuilder();
        WDCLightResult.Status[] statuses = WDCLightResult.Status.values();
        for (int i = 0; i < statuses.length; i++) {
            if (lightMisses[i] == 0L) continue;
            if (out.length() > 0) out.append(", ");
            out.append(statuses[i].name()).append('=').append(lightMisses[i]);
        }
        return out.toString();
    }

    private void noteException(RuntimeException e) {
        exceptions++;
        if (firstException.isEmpty()) firstException = e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    @Override
    public Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("reader", "WorldData Core public API");
        info.put("dimension", dimension);
        info.put("capabilities", WDC.capabilities().all().toString());
        info.put("exceptions", exceptions);
        String misses = lightMissSummary();
        if (!misses.isEmpty()) info.put("lightMisses", misses);
        if (!firstException.isEmpty()) info.put("firstException", firstException);
        return info;
    }
}
