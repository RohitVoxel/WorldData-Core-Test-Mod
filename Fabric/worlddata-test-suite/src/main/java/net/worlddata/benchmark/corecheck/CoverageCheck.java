package net.worlddata.benchmark.corecheck;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.wdc.core.api.WDC;
import net.wdc.core.api.request.WDCAvailability;
import net.worlddata.benchmark.common.check.CheckReport;
import net.worlddata.benchmark.common.check.CoverageState;
import net.worlddata.benchmark.common.check.CoverageTally;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CoverageCheck {

    public static final int FIRST_RECHECK_TICKS = 40;
    public static final int SECOND_RECHECK_TICKS = 200;

    private CoverageCheck() {}

    public static int start(CommandSourceStack source, int radius, Path root) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            CheckOutput.fail(source, "Needs a player: run it in-game on a server or integrated server");
            return 0;
        }
        ServerLevel level = source.getLevel();
        MinecraftServer server = source.getServer();
        String dimension = level.dimension().location().toString();
        BlockPos origin = player.blockPosition();
        int cx = origin.getX() >> 4;
        int cz = origin.getZ() >> 4;
        List<String> labels = new ArrayList<>();
        List<CoverageTally> tallies = new ArrayList<>();
        labels.add("first look");
        tallies.add(scan(level, dimension, cx, cz, radius));
        if (tallies.get(0).consistent()) {
            finish(source, radius, cx, cz, labels, tallies, root);
            return 1;
        }
        CheckOutput.send(source, "§eCore and Minecraft disagree on some chunks; looking again in 2 s and 10 s to tell a delay from a real gap.");
        CoreCheckScheduler.schedule(server, FIRST_RECHECK_TICKS, s -> {
            labels.add("after 2 s");
            tallies.add(scan(level, dimension, cx, cz, radius));
            if (tallies.get(tallies.size() - 1).consistent()) finish(source, radius, cx, cz, labels, tallies, root);
        });
        CoreCheckScheduler.schedule(server, SECOND_RECHECK_TICKS, s -> {
            if (tallies.size() >= 2 && tallies.get(tallies.size() - 1).consistent()) return;
            labels.add("after 10 s");
            tallies.add(scan(level, dimension, cx, cz, radius));
            finish(source, radius, cx, cz, labels, tallies, root);
        });
        return 1;
    }

    static CoverageTally scan(ServerLevel level, String dimension, int cx, int cz, int radius) {
        CoverageTally tally = new CoverageTally();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                boolean loaded = level.getChunkSource().getChunkNow(x, z) != null;
                tally.record(x, z, loaded, stateOf(WDC.requests().availability(dimension, x, z)));
            }
        }
        return tally;
    }

    static CoverageState stateOf(WDCAvailability availability) {
        return switch (availability) {
            case LIVE -> CoverageState.LIVE;
            case STALE -> CoverageState.STALE;
            case ACQUIRING -> CoverageState.ACQUIRING;
            default -> CoverageState.UNAVAILABLE;
        };
    }

    private static void finish(CommandSourceStack source, int radius, int cx, int cz, List<String> labels, List<CoverageTally> tallies, Path root) {
        CheckReport report = CoverageTally.report("WorldData Core chunk coverage against Minecraft", radius, cx, cz, labels, tallies);
        CheckOutput.publish(source, report, "coverage", root);
    }
}
