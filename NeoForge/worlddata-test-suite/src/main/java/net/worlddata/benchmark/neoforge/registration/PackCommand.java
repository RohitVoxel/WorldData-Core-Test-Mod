package net.worlddata.benchmark.neoforge.registration;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.worlddata.benchmark.bench.BenchCommand;
import net.worlddata.benchmark.common.mode.ArmChoice;
import net.worlddata.benchmark.common.mode.ArmResolver;
import net.worlddata.benchmark.common.mode.ModePublisher;
import net.worlddata.benchmark.corecheck.CoreCheckCommands;
import net.worlddata.benchmark.common.mode.ModeRegistry;
import net.worlddata.benchmark.common.mode.ModeRun;
import net.worlddata.benchmark.common.pack.Arm;
import net.worlddata.benchmark.common.pack.PackReport;
import net.worlddata.benchmark.common.pack.ArmRunStore;
import net.worlddata.benchmark.common.pack.MutationEngine;
import net.worlddata.benchmark.common.pack.PackConfig;
import net.worlddata.benchmark.common.pack.PackLiveStatus;
import net.worlddata.benchmark.common.pack.PackReportPublisher;
import net.worlddata.benchmark.common.pack.PackSession;
import net.worlddata.benchmark.common.pack.PackSessionRegistry;
import net.worlddata.benchmark.coreintegration.CountsCommand;
import net.worlddata.benchmark.neoforge.PackRunner;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.LongArgumentType.longArg;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class PackCommand {

    private static final long MIN_OPS = 1_000L;
    private static final long MAX_OPS = 1_000_000_000L;

    private PackCommand() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(PackCommand::onRegisterCommands);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(tree("worlddatatest"));
        event.getDispatcher().register(tree("wdtest"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tree(String root) {
        return literal(root)
                .requires(source -> source.hasPermission(2))
                .then(runTree())
                .then(CoreCheckCommands.coverage(PackRunner::coreInstalled, PackRunner::reportsRoot))
                .then(CoreCheckCommands.verify(PackRunner::coreInstalled, PackRunner::reportsRoot))
                .then(CoreCheckCommands.monitorCheck(PackRunner::coreInstalled, PackRunner::reportsRoot))
                .then(BenchCommand.tree(PackRunner::coreInstalled, PackRunner::reportsRoot))
                .then(literal("counts")
                        .executes(ctx -> CountsCommand.run(ctx, false))
                        .then(literal("chunk").executes(ctx -> CountsCommand.run(ctx, false)))
                        .then(literal("section").executes(ctx -> CountsCommand.run(ctx, true))))
                .then(ModeCommand.ramp("ramp"))
                .then(ModeCommand.ramp("throttle"))
                .then(ModeCommand.efficiency())
                .then(literal("stop").executes(PackCommand::stop))
                .then(literal("status").executes(PackCommand::status))
                .then(literal("times").executes(PackCommand::times))
                .then(literal("latency").executes(PackCommand::latency))
                .then(literal("apicoverage").executes(PackCommand::coverage))
                .then(literal("captureaudit").executes(PackCommand::captureAudit))
                .then(literal("sources").executes(PackCommand::sources));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> runTree() {
        LiteralArgumentBuilder<CommandSourceStack> run = runNode("run", ArmChoice.AUTO);
        for (String source : ArmChoice.LITERALS) run.then(runNode(source, ArmChoice.parse(source)));
        return run;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> runNode(String name, ArmChoice choice) {
        return literal(name)
                .executes(ctx -> run(ctx, PackConfig.DEFAULT_TOTAL_OPS, PackConfig.OPS_PER_SECOND_UNLIMITED, PackConfig.TICK_BUDGET_DEFAULT_MS, PackConfig.DEFAULT_SEED, PackConfig.THRASH_WORKERS, choice))
                .then(argument("ops", longArg(MIN_OPS, MAX_OPS))
                        .executes(ctx -> run(ctx, LongArgumentType.getLong(ctx, "ops"), PackConfig.OPS_PER_SECOND_UNLIMITED, PackConfig.TICK_BUDGET_DEFAULT_MS, PackConfig.DEFAULT_SEED, PackConfig.THRASH_WORKERS, choice))
                        .then(argument("opsPerSec", longArg(PackConfig.OPS_PER_SECOND_UNLIMITED, PackConfig.OPS_PER_SECOND_MAX))
                                .executes(ctx -> run(ctx, LongArgumentType.getLong(ctx, "ops"), LongArgumentType.getLong(ctx, "opsPerSec"), PackConfig.TICK_BUDGET_DEFAULT_MS, PackConfig.DEFAULT_SEED, PackConfig.THRASH_WORKERS, choice))
                                .then(argument("tickMs", integer(PackConfig.TICK_BUDGET_MIN_MS, PackConfig.TICK_BUDGET_MAX_MS))
                                        .executes(ctx -> run(ctx, LongArgumentType.getLong(ctx, "ops"), LongArgumentType.getLong(ctx, "opsPerSec"), IntegerArgumentType.getInteger(ctx, "tickMs"), PackConfig.DEFAULT_SEED, PackConfig.THRASH_WORKERS, choice))
                                        .then(argument("seed", longArg())
                                                .executes(ctx -> run(ctx, LongArgumentType.getLong(ctx, "ops"), LongArgumentType.getLong(ctx, "opsPerSec"), IntegerArgumentType.getInteger(ctx, "tickMs"), LongArgumentType.getLong(ctx, "seed"), PackConfig.THRASH_WORKERS, choice))
                                                .then(argument("thrash", integer(PackConfig.THRASH_WORKERS_MIN, PackConfig.THRASH_WORKERS_MAX))
                                                        .executes(ctx -> run(ctx, LongArgumentType.getLong(ctx, "ops"), LongArgumentType.getLong(ctx, "opsPerSec"), IntegerArgumentType.getInteger(ctx, "tickMs"), LongArgumentType.getLong(ctx, "seed"), IntegerArgumentType.getInteger(ctx, "thrash"), choice)))))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, long ops, long opsPerSecond, int tickMs, long seed, int thrashWorkers, ArmChoice choice) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        double x = player != null ? player.getX() : 0.0;
        double z = player != null ? player.getZ() : 0.0;
        String error = PackRunner.start(source.getServer(), source.getLevel(), x, z, ops, opsPerSecond, seed, PackConfig.tickBudgetNanosFromMillis(tickMs), thrashWorkers, choice);
        if (error != null) {
            source.sendFailure(Component.literal(error));
            return 0;
        }
        PackSession started = PackSessionRegistry.active();
        Arm arm = started != null ? started.setup().primary().arm() : PackRunner.currentArm();
        String sourceNote = ArmResolver.sourceText(arm, choice, PackRunner.coreInstalled());
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "ATM_PACK V1 started on %s in %s: %d ops in batches of at most %d, %s, seed %d, %.1f ms of server time per tick for the test mod, %d thrash workers. It ends when all ops are done (no time limit). Source: %s. Run it in a throwaway world: it writes blocks in a sky arena and restores them at the end.",
                arm.displayName(), source.getLevel().dimension().location(), ops, PackConfig.BATCH_MAX_OPS,
                opsPerSecond > 0L ? "at most " + opsPerSecond + " ops per second" : "no ops per second cap (as fast as the time budget allows)", seed, (double) tickMs, thrashWorkers, sourceNote)), true);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ModeRun mode = ModeRegistry.active();
        if (mode != null) {
            mode.stop();
            source.sendSuccess(() -> Component.literal("Stopping: the " + mode.mode() + " run ends at the end of this tick and its report is written"), true);
            return 1;
        }
        PackSession session = PackSessionRegistry.active();
        if (session == null) {
            source.sendFailure(Component.literal("No run is active"));
            return 0;
        }
        session.stop();
        source.sendSuccess(() -> Component.literal("Stopping: no new ops will be issued, the drain settles what is in flight and the report is written when it ends"), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        PackSession session = PackSessionRegistry.active();
        ModeRun mode = ModeRegistry.active();
        if (session == null && mode != null) {
            source.sendSuccess(() -> Component.literal(mode.statusLine()), false);
            return 1;
        }
        if (session == null) {
            PackReportPublisher.Result last = PackReportPublisher.last();
            ModePublisher.Result lastMode = ModePublisher.last();
            source.sendSuccess(() -> Component.literal(last == null && lastMode == null ? "No run is active" : "No run is active."
                    + (last == null ? "" : " Last pack run: " + last.summaryLine()) + (lastMode == null ? "" : " Last " + lastMode.report().mode() + " run: " + lastMode.summaryLine())), false);
            return 1;
        }
        String line = PackLiveStatus.from(session).statusLine();
        source.sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int times(CommandContext<CommandSourceStack> ctx) {
        send(ctx.getSource(), ArmRunStore.describe(PackRunner.reportsRoot()));
        return 1;
    }

    private static int latency(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        PackSession session = PackSessionRegistry.active();
        if (session == null) {
            PackReportPublisher.Result last = PackReportPublisher.last();
            if (last == null) {
                source.sendFailure(Component.literal("No run is active and no run has finished yet"));
                return 0;
            }
            send(source, last.latencyLines());
            return 1;
        }
        send(source, PackLiveStatus.latencyLines(session));
        return 1;
    }

    private static int coverage(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        PackSession session = PackSessionRegistry.active();
        if (session == null) {
            PackReportPublisher.Result last = PackReportPublisher.last();
            if (last == null) {
                source.sendFailure(Component.literal("No run is active and no run has finished yet"));
                return 0;
            }
            send(source, last.coverageLines());
            return 1;
        }
        send(source, PackLiveStatus.coverageLines(session));
        return 1;
    }

    private static String lastAuditText(PackReport report) {
        if (!(report.json().get("mutation") instanceof Map<?, ?> mutation) || !(mutation.get("audit") instanceof Map<?, ?> audit) || !(audit.get("positionsChecked") instanceof Number checked)) {
            return "Last run: no block-write check was recorded";
        }
        return String.format(Locale.ROOT, "Last run block-write check: %d written positions, %d compared, %d different between Minecraft and WorldData Core, %d unreadable (examples in report.json)",
                checked.longValue(), count(audit, "compared"), count(audit, "mismatches"), count(audit, "unreadable"));
    }

    private static long count(Map<?, ?> map, String key) {
        return map.get(key) instanceof Number n ? n.longValue() : 0L;
    }

    private static int captureAudit(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        PackSession session = PackSessionRegistry.active();
        if (session == null) {
            PackReportPublisher.Result last = PackReportPublisher.last();
            String text = last == null ? "No run is active and no run has finished yet" : lastAuditText(last.report());
            source.sendSuccess(() -> Component.literal(text), false);
            return 1;
        }
        if (session.setup().primary().arm() != Arm.CORE) {
            source.sendFailure(Component.literal("The block-write check compares WorldData Core with Minecraft, so it needs a run with WorldData Core installed"));
            return 0;
        }
        MutationEngine.Audit audit = session.audit();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Block-write check now: %d written positions, %d compared, %d different between Minecraft and WorldData Core, %d unreadable. A block written in the last tick or two can differ until Core has caught up. Lag probes resolved %d, not reflected %d, worst lag %d ticks",
                audit.sampled(), audit.compared(), audit.mismatches(), audit.unreadable(), session.mutation().lagResolved(), session.mutation().lagTimeouts(), session.mutation().lagMaxTicks())), false);
        return 1;
    }

    private static int sources(CommandContext<CommandSourceStack> ctx) {
        send(ctx.getSource(), ArmResolver.sourceLines(PackRunner.coreInstalled(), PackRunner.currentArm()));
        return 1;
    }

    private static void send(CommandSourceStack source, List<String> lines) {
        for (String line : lines) source.sendSuccess(() -> Component.literal(line), false);
    }
}
