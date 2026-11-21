package net.worlddata.benchmark.corecheck;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.wdc.core.api.WDC;
import net.wdc.core.api.request.WDCChunkResult;
import net.wdc.core.api.request.WDCHeightKind;
import net.wdc.core.api.request.WDCHeightmap;
import net.wdc.core.api.request.WDCSectionOccupancy;
import net.worlddata.benchmark.common.check.CheckReport;
import net.worlddata.benchmark.common.check.ParityTally;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class VerifyCheck {

    public static final String[] TAGS = {"minecraft:leaves", "minecraft:logs", "minecraft:ice"};
    public static final int TOP_BLOCKS = 3;

    private static final WDCHeightKind[] KINDS = WDCHeightKind.values();
    private static final Heightmap.Types[] VANILLA_KINDS = vanillaKinds();
    private static final String[] SCAN_LABELS = labels(" against block scan");
    private static final String[] MAP_LABELS = labels(" against Minecraft heightmap");

    private VerifyCheck() {}

    private static Heightmap.Types[] vanillaKinds() {
        Heightmap.Types[] types = new Heightmap.Types[KINDS.length];
        for (int i = 0; i < KINDS.length; i++) types[i] = Heightmap.Types.valueOf(KINDS[i].name());
        return types;
    }

    private static String[] labels(String suffix) {
        String[] labels = new String[KINDS.length];
        for (int i = 0; i < KINDS.length; i++) labels[i] = "height " + KINDS[i].name().toLowerCase(Locale.ROOT).replace('_', ' ') + suffix;
        return labels;
    }

    public static int start(CommandSourceStack source, int radius, Path root) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            CheckOutput.fail(source, "Needs a player: run it in-game on a server or integrated server");
            return 0;
        }
        ServerLevel level = source.getLevel();
        String dimension = level.dimension().location().toString();
        BlockPos origin = player.blockPosition();
        int cx = origin.getX() >> 4;
        int cz = origin.getZ() >> 4;
        ParityTally parity = new ParityTally();
        for (String label : MAP_LABELS) parity.markSoft(label);
        int unloaded = 0;
        int notServed = 0;
        int checked = 0;
        List<String> skipped = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) {
                    unloaded++;
                    continue;
                }
                WDCChunkResult core = WDC.requests().chunk(dimension, x, z);
                if (!core.isUsable()) {
                    notServed++;
                    if (skipped.size() < 5) skipped.add("(" + x + "," + z + ") " + core.freshness() + " " + core.acquisitionStatus());
                    continue;
                }
                checked++;
                verifyChunk(level, chunk, core, x, z, parity);
            }
        }
        CheckReport report = parity.report("WorldData Core derived layers against Minecraft");
        report.info("Square of " + (2 * radius + 1) + " by " + (2 * radius + 1) + " chunks around chunk (" + cx + ", " + cz + "): " + checked + " compared, "
                + unloaded + " not held by Minecraft, " + notServed + " held by Minecraft but not served by Core");
        if (notServed > 0) report.warn("Core did not serve " + notServed + " chunk(s) that Minecraft holds, so they were skipped: " + String.join(", ", skipped));
        if (checked == 0) report.warn("No chunk could be compared; stand in loaded terrain and try again");
        report.info("Run it when the world is quiet (no fluid flow, growth or explosions): Core learns of block changes a moment after Minecraft does");
        CheckOutput.publish(source, report, "verify", root);
        return 1;
    }

    private static void verifyChunk(ServerLevel level, LevelChunk chunk, WDCChunkResult core, int cx, int cz, ParityTally parity) {
        int minBuild = level.getMinBuildHeight();
        int minSection = minBuild >> 4;
        LevelChunkSection[] sections = chunk.getSections();
        WDCSectionOccupancy occupancy = core.occupancy();
        WDCHeightmap heightmap = core.heightmap();
        if (occupancy == null) parity.add("section emptiness", 0L, 0L);
        if (heightmap == null) {
            for (int k = 0; k < KINDS.length; k++) {
                parity.add(SCAN_LABELS[k], 0L, 0L);
                parity.add(MAP_LABELS[k], 0L, 0L);
            }
        }
        List<TagKey<Block>> tags = new ArrayList<>();
        for (String tag : TAGS) tags.add(TagKey.create(Registries.BLOCK, ResourceLocation.parse(tag)));
        long[] tagCounts = new long[TAGS.length];
        Map<String, Integer> byName = new HashMap<>();
        int[][] top = new int[KINDS.length][256];
        for (int[] row : top) java.util.Arrays.fill(row, Integer.MIN_VALUE);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            int sectionY = minSection + i;
            boolean mcEmpty = section == null || section.hasOnlyAir();
            if (occupancy != null) {
                boolean coreEmpty = occupancy.isEmpty(sectionY);
                parity.compare("section emptiness", mcEmpty == coreEmpty,
                        () -> "chunk (" + cx + "," + cz + ") section " + sectionY + " Minecraft " + (mcEmpty ? "empty" : "has blocks") + ", Core " + (coreEmpty ? "empty" : "has blocks"));
            }
            if (mcEmpty) continue;
            for (int ly = 0; ly < 16; ly++) {
                int y = sectionY * 16 + ly;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        BlockState state = section.getBlockState(lx, ly, lz);
                        int x = cx * 16 + lx;
                        int z = cz * 16 + lz;
                        boolean mcWater = state.getFluidState().is(FluidTags.WATER);
                        boolean coreWater = core.waterAt(x, y, z).isWater();
                        if (mcWater != coreWater) {
                            parity.compare("water at block", false, () -> x + " " + y + " " + z + " Minecraft " + mcWater + ", Core " + coreWater);
                        } else {
                            parity.compare("water at block", true, null);
                        }
                        if (state.isAir()) continue;
                        int column = lz * 16 + lx;
                        for (int k = 0; k < KINDS.length; k++) {
                            if (y > top[k][column] && VANILLA_KINDS[k].isOpaque().test(state)) top[k][column] = y;
                        }
                        byName.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), 1, Integer::sum);
                        for (int t = 0; t < tags.size(); t++) if (state.is(tags.get(t))) tagCounts[t]++;
                    }
                }
            }
        }
        if (heightmap != null) {
            for (int k = 0; k < KINDS.length; k++) {
                WDCHeightKind kind = KINDS[k];
                Heightmap.Types type = VANILLA_KINDS[k];
                String scanLabel = SCAN_LABELS[k];
                String mapLabel = MAP_LABELS[k];
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int scanned = top[k][lz * 16 + lx];
                        int coreTop = heightmap.topY(lx, lz, kind);
                        boolean empty = scanned == Integer.MIN_VALUE;
                        boolean equal = empty ? coreTop == WDCHeightmap.UNKNOWN : coreTop == scanned;
                        int column = lx;
                        int row = lz;
                        parity.compare(scanLabel, equal,
                                () -> "chunk (" + cx + "," + cz + ") column " + column + "," + row + " scan " + (empty ? "empty" : scanned) + ", Core " + (coreTop == WDCHeightmap.UNKNOWN ? "unknown" : coreTop));
                        int vanilla = level.getHeight(type, cx * 16 + lx, cz * 16 + lz);
                        int expected = empty ? minBuild : scanned + 1;
                        parity.compare(mapLabel, vanilla == expected,
                                () -> "chunk (" + cx + "," + cz + ") column " + column + "," + row + " Minecraft heightmap " + vanilla + ", block scan implies " + expected);
                    }
                }
            }
        }
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(byName.entrySet());
        ranked.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()));
        for (int i = 0; i < Math.min(TOP_BLOCKS, ranked.size()); i++) {
            String name = ranked.get(i).getKey();
            int expected = ranked.get(i).getValue();
            int counted = core.countBlocksByName(name);
            int found = core.findBlocksByName(name).size();
            parity.compare("scan countBlocksByName", counted == expected, () -> "chunk (" + cx + "," + cz + ") " + name + " Minecraft " + expected + ", Core " + counted);
            parity.compare("scan findBlocksByName agrees with its count", found == counted, () -> "chunk (" + cx + "," + cz + ") " + name + " found " + found + ", counted " + counted);
            parity.compare("scan hasBlockByName", core.hasBlockByName(name), () -> "chunk (" + cx + "," + cz + ") " + name + " is present but Core says no");
        }
        for (int t = 0; t < TAGS.length; t++) {
            String tag = TAGS[t];
            long expected = tagCounts[t];
            int counted = core.countBlocksByTag(tag);
            parity.compare("scan countBlocksByTag", counted == expected, () -> "chunk (" + cx + "," + cz + ") #" + tag + " Minecraft " + expected + ", Core " + counted);
        }
    }
}
