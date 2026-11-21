package net.worlddata.benchmark.neoforge;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.worlddata.benchmark.common.mode.ArmChoice;
import net.worlddata.benchmark.common.mode.ArmResolver;
import net.worlddata.benchmark.common.mode.EfficiencyConfig;
import net.worlddata.benchmark.common.mode.EfficiencySession;
import net.worlddata.benchmark.common.mode.ModePublisher;
import net.worlddata.benchmark.common.mode.ModeRegistry;
import net.worlddata.benchmark.common.mode.ModeRun;
import net.worlddata.benchmark.common.mode.ModeSetup;
import net.worlddata.benchmark.common.mode.ModeStore;
import net.worlddata.benchmark.common.mode.RampConfig;
import net.worlddata.benchmark.common.mode.RampSession;
import net.worlddata.benchmark.common.pack.Arm;
import net.worlddata.benchmark.common.pack.CallRecorder;
import net.worlddata.benchmark.common.pack.PackConfig;
import net.worlddata.benchmark.common.pack.PackSessionRegistry;
import net.worlddata.benchmark.common.pack.WorldReader;
import net.worlddata.benchmark.coreintegration.CoreFactory;
import net.worlddata.benchmark.neoforge.environment.NeoForgeEnvironmentCollector;
import net.worlddata.benchmark.neoforge.reader.VanillaWorldReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

public final class ModeRunner {

    public record Start(String error, Arm arm) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("worlddata_core_test");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private ModeRunner() {}

    public static Start startRamp(MinecraftServer server, ServerLevel level, double x, double z, RampConfig config) {
        return start(server, level, x, z, "ramp", config.seed(), config.arm(), setup -> new RampSession(setup, config));
    }

    public static Start startEfficiency(MinecraftServer server, ServerLevel level, double x, double z, EfficiencyConfig config) {
        return start(server, level, x, z, "efficiency", config.seed(), config.arm(), setup -> new EfficiencySession(setup, config));
    }

    private static Start start(MinecraftServer server, ServerLevel level, double x, double z, String mode, long seed, ArmChoice choice, Function<ModeSetup, ModeRun> factory) {
        if (PackSessionRegistry.active() != null || ModeRegistry.active() != null) return new Start("a run is already active; use /worlddatatest stop first", null);
        if (level == null) return new Start("that dimension is not loaded", null);
        boolean installed = PackRunner.coreInstalled();
        ArmResolver.Resolution resolution = ArmResolver.resolve(choice, installed);
        if (!resolution.ok()) return new Start(resolution.error(), null);
        Arm arm = resolution.arm();
        String dimension = level.dimension().location().toString();
        ResourceKey<Level> dimensionKey = level.dimension();
        CallRecorder recorder = new CallRecorder();
        VanillaWorldReader minecraft = new VanillaWorldReader(() -> server.getLevel(dimensionKey), recorder, PackConfig.FORCE_LOAD_CALL_CAP, PackConfig.UNIQUE_CHUNK_CAP);
        WorldReader primary = arm == Arm.CORE ? CoreFactory.create(dimension, recorder) : minecraft;
        String runId = LocalDateTime.now().format(STAMP) + "-" + mode + "-" + primary.arm().fileId();
        ModeSetup setup = new ModeSetup(runId, seed, dimension, Mth.floor(x) >> 4, Mth.floor(z) >> 4, primary, minecraft, recorder,
                NeoForgeEnvironmentCollector.collect(dimension, server.getPlayerList().getViewDistance(), server.getPlayerList().getSimulationDistance(), null),
                server.isDedicatedServer() ? "dedicated server" : "integrated server", level.getMinBuildHeight(), level.getMaxBuildHeight(), choice, installed);
        ModeRun run = factory.apply(setup);
        if (!ModeRegistry.trySet(run)) return new Start("a run is already active; use /worlddatatest stop first", null);
        LOGGER.info("{} run {} started in {}: arm {}, seed {}", mode, runId, dimension, arm.displayName(), seed);
        return new Start(null, arm);
    }

    public static void finish(MinecraftServer server, ModeRun run) {
        ModePublisher.Result result = publish(run);
        ModeRegistry.clear(run);
        LOGGER.info(result.logLine());
        server.getPlayerList().broadcastSystemMessage(Component.literal(result.summaryLine()), false);
    }

    public static void abortActive(MinecraftServer server) {
        ModeRun run = ModeRegistry.active();
        if (run == null) return;
        run.abort();
        ModePublisher.Result result = publish(run);
        ModeRegistry.clear(run);
        LOGGER.info(result.logLine());
    }

    private static ModePublisher.Result publish(ModeRun run) {
        Path root = PackRunner.reportsRoot();
        Arm arm = run.report().arm();
        Arm otherArm = arm == Arm.CORE ? Arm.VANILLA : Arm.CORE;
        run.otherArm(ModeStore.load(root, run.mode(), otherArm));
        ModePublisher.Result result = ModePublisher.publish(run, root);
        ModeStore.save(root, run.mode(), arm, run.storeValues());
        return result;
    }
}
