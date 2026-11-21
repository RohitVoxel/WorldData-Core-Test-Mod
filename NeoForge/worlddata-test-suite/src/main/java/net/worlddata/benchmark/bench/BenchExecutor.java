package net.worlddata.benchmark.bench;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.worlddata.benchmark.common.bench.BenchClock;
import net.worlddata.benchmark.common.bench.BenchConfig;
import net.worlddata.benchmark.common.bench.BenchFile;
import net.worlddata.benchmark.common.bench.BenchPositions;
import net.worlddata.benchmark.common.bench.BenchReport;
import net.worlddata.benchmark.common.bench.BenchRun;
import net.worlddata.benchmark.common.bench.BenchSideResult;
import net.worlddata.benchmark.common.bench.BenchTarget;
import net.worlddata.benchmark.common.bench.ChunkProbe;
import net.worlddata.benchmark.common.mode.ModeRegistry;
import net.worlddata.benchmark.common.pack.PackSessionRegistry;
import net.worlddata.benchmark.coreintegration.CoreFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

final class BenchExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger("worlddata_core_test");

    private BenchExecutor() {}

    static int run(CommandSourceStack source, String apiText, Long count, String modeText, boolean compare, Path root) {
        if (PackSessionRegistry.active() != null || ModeRegistry.active() != null) {
            return fail(source, "A run is active; use /worlddatatest stop first so the bench is not disturbed");
        }
        if (!source.getServer().isSameThread()) {
            return fail(source, "The bench has to run on the server thread");
        }
        BenchConfig config;
        try {
            config = BenchConfig.parse(apiText, count, modeText, compare);
        } catch (IllegalArgumentException invalid) {
            return fail(source, invalid.getMessage());
        }

        ServerLevel level = source.getLevel();
        String dimension = level.dimension().location().toString();
        Vec3 at = source.getPosition();
        ChunkProbe probe = compare
                ? both(CoreFactory.benchProbe(dimension), VanillaBenchTarget.probe(level))
                : CoreFactory.benchProbe(dimension);
        BenchPositions.Result placed = BenchPositions.build(config.mode(), (int) Math.floor(at.x()), (int) Math.floor(at.y()),
                (int) Math.floor(at.z()), level.getMinBuildHeight(), level.getMaxBuildHeight(), probe);
        if (!placed.ok()) return fail(source, placed.error());
        BenchPositions positions = placed.positions();

        List<BenchTarget> targets = new ArrayList<>();
        targets.add(CoreFactory.benchTarget(dimension, positions, config.mode()));
        if (compare) targets.add(VanillaBenchTarget.create(level, positions));

        BenchRun.Result result = BenchRun.execute(config, dimension, positions, targets, BenchClock.SYSTEM);
        publish(source, result, root);
        return 1;
    }

    private static void publish(CommandSourceStack source, BenchRun.Result result, Path root) {
        LocalDateTime now = LocalDateTime.now();
        BenchConfig config = result.config();
        List<String> chat = BenchReport.chatLines(result);
        send(source, "\u00a76" + chat.get(0));
        for (int i = 1; i < chat.size(); i++) send(source, "\u00a77" + chat.get(i));
        String where;
        try {
            Path file = BenchFile.write(root, config.api(), config.compare(), now, BenchReport.fileLines(result, BenchFile.stamp(now)));
            where = file.toString();
            send(source, "\u00a77Report written to the bench folder of the test reports, the file location is in the server log");
        } catch (IOException | RuntimeException failure) {
            where = "not written (" + failure.getMessage() + ")";
            send(source, "\u00a7eThe report file could not be written, see the server log");
        }
        LOGGER.info("bench {} {} {} ops: {} report {}", config.api().commandName(), config.mode().commandName(), config.count(),
                summary(result), where);
    }

    private static String summary(BenchRun.Result result) {
        StringBuilder text = new StringBuilder();
        for (BenchSideResult side : result.sides()) {
            if (text.length() > 0) text.append(", ");
            text.append(side.label()).append(' ').append(BenchReport.rate(side.stats().opsPerSecond()));
        }
        return text.toString();
    }

    private static ChunkProbe both(ChunkProbe first, ChunkProbe second) {
        return (chunkX, chunkZ) -> {
            String problem = first.problem(chunkX, chunkZ);
            return problem != null ? problem : second.problem(chunkX, chunkZ);
        };
    }

    private static int fail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal(text));
        return 0;
    }

    private static void send(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }
}
