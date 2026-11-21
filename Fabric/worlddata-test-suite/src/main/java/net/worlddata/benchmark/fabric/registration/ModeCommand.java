package net.worlddata.benchmark.fabric.registration;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.worlddata.benchmark.common.mode.ArmChoice;
import net.worlddata.benchmark.common.mode.EfficiencyConfig;
import net.worlddata.benchmark.common.mode.RampConfig;
import net.worlddata.benchmark.common.pack.Arm;
import net.worlddata.benchmark.fabric.ModeRunner;

import java.util.Locale;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class ModeCommand {

    private static final String[] ARMS = ArmChoice.LITERALS.toArray(new String[0]);

    private ModeCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> ramp(String name) {
        LiteralArgumentBuilder<CommandSourceStack> root = rampNode(literal(name), ArmChoice.AUTO);
        for (String source : ArmChoice.LITERALS) root.then(rampNode(literal(source), ArmChoice.parse(source)));
        return root;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> rampNode(LiteralArgumentBuilder<CommandSourceStack> node, ArmChoice choice) {
        return node.then(argument("targetMs", DoubleArgumentType.doubleArg(1.0, 1000.0))
                .executes(ctx -> ramp(ctx, choice, ""))
                .then(argument("options", StringArgumentType.greedyString())
                        .executes(ctx -> ramp(ctx, choice, StringArgumentType.getString(ctx, "options")))));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> efficiency() {
        return literal("efficiency")
                .executes(ctx -> efficiency(ctx, "auto", ""))
                .then(argument("arm", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ARMS, builder))
                        .executes(ctx -> efficiency(ctx, StringArgumentType.getString(ctx, "arm"), ""))
                        .then(argument("options", StringArgumentType.greedyString())
                                .executes(ctx -> efficiency(ctx, StringArgumentType.getString(ctx, "arm"), StringArgumentType.getString(ctx, "options")))));
    }

    private static int ramp(CommandContext<CommandSourceStack> ctx, ArmChoice choice, String options) {
        CommandSourceStack source = ctx.getSource();
        RampConfig config;
        try {
            String effective = choice == ArmChoice.AUTO ? options : (options + " arm=" + choice.text()).trim();
            config = RampConfig.parse(DoubleArgumentType.getDouble(ctx, "targetMs"), effective);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        ModeRunner.Start start = ModeRunner.startRamp(source.getServer(), source.getLevel(), player != null ? player.getX() : 0.0, player != null ? player.getZ() : 0.0, config);
        if (start.error() != null) {
            source.sendFailure(Component.literal(start.error()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "ATM_RAMP started on %s in %s: %d ops/s, +%d every %.2f s, stop when MSPT stays above %.1f ms for %d ticks in a row%s, seed %d. Run it in a loaded, unchanging area; it only reads. Stop it early with /worlddatatest stop.",
                start.arm().displayName(), source.getLevel().dimension().location(), config.startOps(), config.stepOps(), config.stepSeconds(), config.limitMs(), config.holdTicks(),
                (config.tickIssueMs() > 0.0 ? String.format(Locale.ROOT, ", at most %.2f ms of issuing per tick", config.tickIssueMs()) : "") + (config.thrashWorkers() > 0 ? ", thrash " + config.thrashWorkers() + " threads" : ""), config.seed())), true);
        return 1;
    }

    private static int efficiency(CommandContext<CommandSourceStack> ctx, String arm, String options) {
        CommandSourceStack source = ctx.getSource();
        EfficiencyConfig config;
        try {
            config = EfficiencyConfig.parse(arm, options);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        ModeRunner.Start start = ModeRunner.startEfficiency(source.getServer(), source.getLevel(), player != null ? player.getX() : 0.0, player != null ? player.getZ() : 0.0, config);
        if (start.error() != null) {
            source.sendFailure(Component.literal(start.error()));
            return 0;
        }
        String budget = start.arm() == Arm.CORE
                ? String.format(Locale.ROOT, ", Core budget %.1f ms (%s; the test mod only records it, set the real one with /worlddata budget fixed <ms>)", config.coreBudgetMs(), config.coreBudgetGiven() ? "as given" : "default")
                : "";
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "ATM_EFFICIENCY started on %s in %s: %.2f ms window per tick, %d ticks after a warm-up%s, seed %d. Run it in a loaded, unchanging area; it only reads. Stop it early with /worlddatatest stop.",
                start.arm().displayName(), source.getLevel().dimension().location(), config.windowMs(), config.ticks(), budget + (config.thrashWorkers() > 0 ? ", thrash " + config.thrashWorkers() + " threads" : ""), config.seed())), true);
        return 1;
    }
}
