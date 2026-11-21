package net.worlddata.benchmark.coreintegration;

import com.mojang.brigadier.context.CommandContext;
import net.wdc.core.api.WDC;
import net.wdc.core.api.request.WDCBlockInfo;
import net.wdc.core.api.request.WDCChunkResult;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.worlddata.benchmark.fabric.PackRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public final class CountsCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("worlddata_core_test");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String AIR_KEY = "minecraft:air";
    private static final String CORE_ERROR_KEY = "<core_error>";
    private static final String CORE_NULL_ID_KEY = "<core_null_id>";

    private CountsCommand() {}

    static final class Positions {
        long match;
        long mismatch;
        long coreErrors;
        final List<String> lines = new ArrayList<>();
        final Map<String, Integer> pairs = new TreeMap<>();
        final Map<String, Integer> byMc = new TreeMap<>();
        final Map<String, Integer> byCore = new TreeMap<>();

        void add(int x, int y, int z, String mcKey, String mcRepr, String coreKey, String coreRepr) {
            mismatch++;
            if (CORE_ERROR_KEY.equals(coreKey)) coreErrors++;
            lines.add(x + " " + y + " " + z + " | mc=" + mcRepr + " | core=" + coreRepr);
            pairs.merge(mcKey + " -> " + coreKey, 1, Integer::sum);
            byMc.merge(mcKey, 1, Integer::sum);
            byCore.merge(coreKey, 1, Integer::sum);
        }
    }

    record Diff(List<String> onlyInMc, List<String> onlyInCore, List<String> countMismatch, List<String> locationOnly, List<String> table) {
        int mismatches() {
            return onlyInMc.size() + onlyInCore.size() + countMismatch.size();
        }

        boolean countsPass() {
            return mismatches() == 0;
        }
    }

    public static int run(CommandContext<CommandSourceStack> ctx, boolean sectionOnly) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Needs a player: run it in-game on a server or integrated server"));
            return 0;
        }
        ServerLevel level = source.getLevel();
        MinecraftServer server = source.getServer();
        String dim = level.dimension().location().toString();
        BlockPos origin = player.blockPosition();
        int cx = origin.getX() >> 4;
        int cz = origin.getZ() >> 4;
        int yMin = level.getMinBuildHeight();
        int yMax = level.getMaxBuildHeight() - 1;
        if (sectionOnly) {
            int secY = origin.getY() >> 4;
            yMin = Math.max(yMin, secY * 16);
            yMax = Math.min(yMax, secY * 16 + 15);
        }
        String abortReason = null;
        if (yMin > yMax) {
            abortReason = "empty y range, the player is outside the world height";
        } else if (level.getChunkSource().getChunkNow(cx, cz) == null) {
            abortReason = "Minecraft chunk is not loaded";
        }
        String coreStatus = "not queried";
        WDCChunkResult core = null;
        long versionBefore = -1L;
        if (abortReason == null) {
            try {
                if (!PackRunner.coreInstalled()) {
                    coreStatus = "WorldData Core not installed or not ready";
                } else {
                    core = WDC.requests().chunk(dim, cx, cz);
                    versionBefore = core.version();
                    coreStatus = core.isUsable()
                            ? "usable acquisitionStatus=" + core.acquisitionStatus() + " freshness=" + core.freshness() + " ageMs=" + core.ageMillis() + " version=" + versionBefore
                            : "not usable acquisitionStatus=" + core.acquisitionStatus() + " freshness=" + core.freshness();
                }
            } catch (RuntimeException e) {
                core = null;
                coreStatus = "error " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            if (core == null || !core.isUsable()) {
                abortReason = "Core chunk not usable";
            }
        }
        long total = 0;
        Map<String, Integer> byMc = new TreeMap<>();
        Map<String, Integer> byCore = new TreeMap<>();
        Positions positions = new Positions();
        Diff diff = null;
        if (abortReason == null) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = yMin; y <= yMax; y++) {
                for (int z = cz * 16; z < cz * 16 + 16; z++) {
                    for (int x = cx * 16; x < cx * 16 + 16; x++) {
                        total++;
                        BlockState state = level.getBlockState(pos.set(x, y, z));
                        String mcKey = state.isAir() ? AIR_KEY : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                        String coreKey = coreKey(core, x, y, z);
                        byMc.merge(mcKey, 1, Integer::sum);
                        byCore.merge(coreKey, 1, Integer::sum);
                        if (mcKey.equals(coreKey)) {
                            positions.match++;
                        } else {
                            String mcRepr = BuiltInRegistries.BLOCK.getKey(state.getBlock()) + props(state);
                            String coreRepr = AIR_KEY.equals(coreKey) ? "ABSENT(air)" : coreKey;
                            positions.add(x, y, z, mcKey, mcRepr, coreKey, coreRepr);
                        }
                    }
                }
            }
            diff = compare(byMc, byCore, positions.byMc, positions.byCore);
        }
        long versionAfter = versionBefore;
        if (abortReason == null) {
            versionAfter = pullVersion(dim, cx, cz);
        }
        boolean movedDuringScan = abortReason == null && versionAfter != versionBefore;
        String readNote = abortReason != null ? "not run"
                : "pull read, WDC.requests().chunk fetched in the same tick right before the scan (mid-tick data); chunk version before " + versionBefore
                + ", after " + versionAfter + (movedDuringScan ? ", CHANGED during the scan" : ", unchanged");
        Path file = reportPath(server, dim, cx, cz, LocalDateTime.now().format(STAMP));
        try {
            writeReport(file, server.getWorldData().getLevelName(), dim, cx, cz, sectionOnly, yMin, yMax, coreStatus, readNote,
                    abortReason, total, byMc, byCore, positions, diff);
        } catch (IOException e) {
            LOGGER.warn("Counts report could not be written to {}", file.toAbsolutePath(), e);
            source.sendFailure(Component.literal("Counts compare ran but the report could not be written"));
            return 0;
        }
        LOGGER.info("Counts report: {}", file.toAbsolutePath());
        String mode = sectionOnly ? "section" : "chunk";
        if (diff == null) {
            String aborted = String.format(Locale.ROOT, "Counts %s dim=%s chunk=%d,%d y=%d..%d: NOT_RUN (%s) core=%s",
                    mode, dim, cx, cz, yMin, yMax, abortReason, coreStatus);
            source.sendFailure(Component.literal(aborted));
            return 0;
        }
        boolean pass = diff.countsPass() && positions.mismatch == 0;
        double pct = total == 0 ? 100.0 : 100.0 * positions.match / total;
        String summary = String.format(Locale.ROOT,
                "Counts %s dim=%s chunk=%d,%d y=%d..%d: positions=%d match=%d (%.4f %%) pos_mismatch=%d core_errors=%d | mc_ids=%d core_ids=%d mismatched_ids=%d (only_mc=%d only_core=%d count_diff=%d) location_only_ids=%d | %s core=%s",
                mode, dim, cx, cz, yMin, yMax, total, positions.match, pct, positions.mismatch, positions.coreErrors,
                byMc.size(), byCore.size(), diff.mismatches(), diff.onlyInMc().size(), diff.onlyInCore().size(),
                diff.countMismatch().size(), diff.locationOnly().size(), pass ? "PASS" : "FAIL", coreStatus + (movedDuringScan ? " core_changed_during_scan=" + versionBefore + "->" + versionAfter : ""));
        if (pass) {
            source.sendSuccess(() -> Component.literal(summary), false);
        } else {
            source.sendFailure(Component.literal(summary));
        }
        return pass ? 1 : 0;
    }

    private static long pullVersion(String dim, int cx, int cz) {
        try {
            return WDC.requests().chunk(dim, cx, cz).version();
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    private static String coreKey(WDCChunkResult core, int x, int y, int z) {
        WDCBlockInfo info;
        try {
            info = core.blockAt(x, y, z);
        } catch (RuntimeException e) {
            return CORE_ERROR_KEY;
        }
        if (!info.present()) return AIR_KEY;
        return info.blockId() == null ? CORE_NULL_ID_KEY : info.blockId();
    }

    private static String props(BlockState state) {
        Map<Property<?>, Comparable<?>> values = state.getValues();
        if (values.isEmpty()) return "";
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<Property<?>, Comparable<?>> e : values.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append(e.getKey().getName()).append('=').append(e.getValue());
        }
        return b.append(']').toString();
    }

    static Diff compare(Map<String, Integer> byMc, Map<String, Integer> byCore,
                        Map<String, Integer> posByMc, Map<String, Integer> posByCore) {
        TreeSet<String> ids = new TreeSet<>(byMc.keySet());
        ids.addAll(byCore.keySet());
        List<String> onlyInMc = new ArrayList<>();
        List<String> onlyInCore = new ArrayList<>();
        List<String> countMismatch = new ArrayList<>();
        List<String> locationOnly = new ArrayList<>();
        List<String> table = new ArrayList<>();
        for (String id : ids) {
            boolean inMc = byMc.containsKey(id);
            boolean inCore = byCore.containsKey(id);
            int mc = inMc ? byMc.get(id) : 0;
            int core = inCore ? byCore.get(id) : 0;
            int delta = core - mc;
            table.add(id + " | " + mc + " | " + core + " | " + delta);
            if (inMc && !inCore) {
                onlyInMc.add(id + " | " + mc);
            } else if (!inMc) {
                onlyInCore.add(id + " | " + core);
            } else if (delta != 0) {
                countMismatch.add(id + " | " + mc + " | " + core + " | " + delta);
            } else {
                int asMc = posByMc.getOrDefault(id, 0);
                int asCore = posByCore.getOrDefault(id, 0);
                if (asMc > 0 || asCore > 0) locationOnly.add(id + " | " + mc + " | " + asMc + " | " + asCore);
            }
        }
        return new Diff(onlyInMc, onlyInCore, countMismatch, locationOnly, table);
    }

    private static long sum(Map<String, Integer> counts) {
        long total = 0;
        for (int v : counts.values()) total += v;
        return total;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static Path reportPath(MinecraftServer server, String dim, int cx, int cz, String stamp) {
        String world = sanitize(server.getWorldData().getLevelName());
        Path dir = FabricLoader.getInstance().getGameDir()
                .resolve("worlddatatest").resolve("counts").resolve(world).resolve(sanitize(dim));
        String base = "c." + cx + "." + cz + "_" + stamp;
        Path file = dir.resolve(base + ".txt");
        int n = 1;
        while (Files.exists(file)) {
            file = dir.resolve(base + "_" + n + ".txt");
            n++;
        }
        return file;
    }

    static void writeReport(Path file, String worldName, String dim, int cx, int cz, boolean sectionOnly,
                            int yMin, int yMax, String coreStatus, String readNote, String abortReason, long total,
                            Map<String, Integer> byMc, Map<String, Integer> byCore, Positions positions, Diff diff) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# WorldData Core block counts and parity report");
        lines.add("timestamp: " + LocalDateTime.now());
        lines.add("world: " + worldName);
        lines.add("dimension: " + dim);
        lines.add("chunk: " + cx + ", " + cz);
        lines.add("mode: " + (sectionOnly ? "section" : "chunk"));
        lines.add("y range: " + yMin + ".." + yMax);
        lines.add("core status: " + coreStatus);
        lines.add("core read: " + readNote);
        if (diff == null) {
            lines.add("comparison: not run, " + abortReason);
            lines.add("");
            lines.add("RESULT: NOT_RUN");
        } else {
            double pct = total == 0 ? 100.0 : 100.0 * positions.match / total;
            lines.add("comparison: block registry id per position and per id count; Minecraft air states (air, cave_air, void_air) and Core absence are both counted as " + AIR_KEY + "; Core exposes block ids only, so block state properties are shown for Minecraft but not compared; Core state ids are internal and not compared");
            lines.add("");
            lines.add("positions: " + total);
            lines.add("match: " + positions.match + " (" + String.format(Locale.ROOT, "%.4f", pct) + " %)");
            lines.add("position mismatch: " + positions.mismatch);
            lines.add("core errors: " + positions.coreErrors);
            lines.add("mc distinct ids: " + byMc.size());
            lines.add("core distinct ids: " + byCore.size());
            lines.add("mc total: " + sum(byMc));
            lines.add("core total: " + sum(byCore));
            lines.add("mismatched ids: " + diff.mismatches());
            lines.add("");
            lines.add("id | mcCount | coreCount | delta (coreCount - mcCount)");
            lines.addAll(diff.table());
            lines.add("");
            lines.add("ONLY_IN_MC (" + diff.onlyInMc().size() + "):");
            lines.addAll(diff.onlyInMc());
            lines.add("");
            lines.add("ONLY_IN_CORE (" + diff.onlyInCore().size() + "):");
            lines.addAll(diff.onlyInCore());
            lines.add("");
            lines.add("COUNT_MISMATCH (" + diff.countMismatch().size() + "):");
            lines.addAll(diff.countMismatch());
            lines.add("");
            lines.add("LOCATION_ONLY, same count but wrong positions (" + diff.locationOnly().size() + "), id | count | mismatched as mc | mismatched as core:");
            lines.addAll(diff.locationOnly());
            lines.add("");
            lines.add("POSITION_MISMATCH_BY_PAIR (" + positions.pairs.size() + "), mc id -> core id | positions:");
            positions.pairs.forEach((k, v) -> lines.add(k + " | " + v));
            lines.add("");
            lines.add("POSITION_MISMATCH (" + positions.mismatch + "), x y z | mc | core:");
            lines.addAll(positions.lines);
            lines.add("");
            lines.add("RESULT: " + (diff.countsPass() && positions.mismatch == 0 ? "PASS" : "FAIL"));
        }
        Files.createDirectories(file.getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
