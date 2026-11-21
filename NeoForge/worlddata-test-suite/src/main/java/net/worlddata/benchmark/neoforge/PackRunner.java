package net.worlddata.benchmark.neoforge;

import net.worlddata.benchmark.common.mode.ArmChoice;
import net.worlddata.benchmark.common.mode.ArmResolver;
import net.worlddata.benchmark.common.mode.ModeRegistry;
import net.worlddata.benchmark.common.pack.Arm;
import net.worlddata.benchmark.common.pack.CallRecorder;
import net.worlddata.benchmark.common.pack.PackConfig;
import net.worlddata.benchmark.common.pack.PackReportPublisher;
import net.worlddata.benchmark.common.pack.PackSession;
import net.worlddata.benchmark.common.pack.PackSessionRegistry;
import net.worlddata.benchmark.common.pack.PackSetup;
import net.worlddata.benchmark.common.pack.WorldReader;
import net.worlddata.benchmark.coreintegration.CoreFactory;
import net.worlddata.benchmark.neoforge.environment.NeoForgeEnvironmentCollector;
import net.worlddata.benchmark.neoforge.reader.VanillaWorldReader;
import net.worlddata.benchmark.neoforge.reader.VanillaWorldWriter;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class PackRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger("worlddata_core_test");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private PackRunner() {}

    public static Path reportsRoot() {
        return FMLPaths.GAMEDIR.get().resolve("worlddata-core-test-reports");
    }

    public static boolean coreInstalled() {
        return ModList.get().isLoaded(CoreFactory.CORE_MOD_ID) && CoreFactory.ready();
    }

    public static String start(MinecraftServer server, ServerLevel level, double x, double z, long totalOps, long opsPerSecond, long seed, long tickBudgetNanos, int thrashWorkers) {
        return start(server, level, x, z, totalOps, opsPerSecond, seed, tickBudgetNanos, thrashWorkers, ArmChoice.AUTO);
    }

    public static String start(MinecraftServer server, ServerLevel level, double x, double z, long totalOps, long opsPerSecond, long seed, long tickBudgetNanos, int thrashWorkers, ArmChoice choice) {
        if (PackSessionRegistry.active() != null || ModeRegistry.active() != null) return "a run is already active; use /worlddatatest stop first";
        if (level == null) return "that dimension is not loaded";
        String dimension = level.dimension().location().toString();
        ResourceKey<Level> dimensionKey = level.dimension();
        CallRecorder recorder = new CallRecorder();
        boolean installed = coreInstalled();
        ArmResolver.Resolution resolution = ArmResolver.resolve(choice, installed);
        if (!resolution.ok()) return resolution.error();
        boolean core = resolution.arm() == Arm.CORE;
        VanillaWorldReader minecraft = new VanillaWorldReader(() -> server.getLevel(dimensionKey), recorder, PackConfig.FORCE_LOAD_CALL_CAP, PackConfig.UNIQUE_CHUNK_CAP);
        WorldReader primary = core ? CoreFactory.create(dimension, recorder) : minecraft;
        WorldReader baseline = core ? minecraft : null;
        boolean lightOnClient = core && !server.isDedicatedServer();
        String runId = LocalDateTime.now().format(STAMP) + "-" + primary.arm().fileId();
        Path sinkFile = reportsRoot().resolve(runId).resolve("sink.bin");
        PackSetup setup = new PackSetup(totalOps, seed, runId, dimension, Mth.floor(x) >> 4, Mth.floor(z) >> 4, primary, baseline, minecraft,
                new VanillaWorldWriter(() -> server.getLevel(dimensionKey), recorder), recorder, lightOnClient, sinkFile,
                NeoForgeEnvironmentCollector.collect(dimension, server.getPlayerList().getViewDistance(), server.getPlayerList().getSimulationDistance(), null),
                server.isDedicatedServer() ? "dedicated server" : "integrated server", tickBudgetNanos,
                opsPerSecond, level.getMinBuildHeight(), level.getMaxBuildHeight(), thrashWorkers, choice == null ? ArmChoice.AUTO : choice, installed);
        PackSession session = new PackSession(setup);
        if (!PackSessionRegistry.trySet(session)) return "a run is already active; use /worlddatatest stop first";
        LOGGER.info("Run {} started in {}: {} ops, {}, arm {} (source requested {}, WorldData Core installed {}), seed {}, tick budget {} ms, {} thrash workers", runId, dimension, totalOps,
                opsPerSecond > 0L ? opsPerSecond + " ops per second at most" : "no ops per second cap", primary.arm().displayName(), (choice == null ? ArmChoice.AUTO : choice).text(), installed, seed, tickBudgetNanos / 1_000_000.0, thrashWorkers);
        return null;
    }

    public static void finish(MinecraftServer server, PackSession session) {
        PackReportPublisher.Result result = PackReportPublisher.publish(session, reportsRoot());
        PackSessionRegistry.clear(session);
        LOGGER.info(result.logLine());
        server.getPlayerList().broadcastSystemMessage(Component.literal(result.summaryLine()), false);
    }

    public static void abortActive(MinecraftServer server) {
        PackSession session = PackSessionRegistry.active();
        if (session == null) return;
        session.abort();
        PackReportPublisher.Result result = PackReportPublisher.publish(session, reportsRoot());
        PackSessionRegistry.clear(session);
        LOGGER.info(result.logLine());
    }

    public static Arm currentArm() {
        return coreInstalled() ? Arm.CORE : Arm.VANILLA;
    }
}
