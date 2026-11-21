package net.worlddata.benchmark.corecheck;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.worlddata.benchmark.common.mode.ModeRegistry;
import net.worlddata.benchmark.common.pack.PackSessionRegistry;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class CoreCheckCommands {

    public static final int COVERAGE_MAX_RADIUS = 32;
    public static final int COVERAGE_DEFAULT_RADIUS = 8;
    public static final int VERIFY_MAX_RADIUS = 3;
    public static final int VERIFY_DEFAULT_RADIUS = 1;

    private CoreCheckCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> coverage(BooleanSupplier coreInstalled, Supplier<Path> root) {
        return Commands.literal("coverage")
                .executes(ctx -> guarded(ctx, coreInstalled, () -> CoverageCheck.start(ctx.getSource(), COVERAGE_DEFAULT_RADIUS, root.get())))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, COVERAGE_MAX_RADIUS))
                        .executes(ctx -> guarded(ctx, coreInstalled,
                                () -> CoverageCheck.start(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"), root.get()))));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> verify(BooleanSupplier coreInstalled, Supplier<Path> root) {
        return Commands.literal("verify")
                .executes(ctx -> guarded(ctx, coreInstalled, () -> VerifyCheck.start(ctx.getSource(), VERIFY_DEFAULT_RADIUS, root.get())))
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, VERIFY_MAX_RADIUS))
                        .executes(ctx -> guarded(ctx, coreInstalled,
                                () -> VerifyCheck.start(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"), root.get()))));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> monitorCheck(BooleanSupplier coreInstalled, Supplier<Path> root) {
        return Commands.literal("monitorcheck")
                .executes(ctx -> guarded(ctx, coreInstalled, () -> MonitorCheck.start(ctx.getSource(), root.get())));
    }

    static int guarded(CommandContext<CommandSourceStack> ctx, BooleanSupplier coreInstalled, java.util.function.IntSupplier body) {
        CommandSourceStack source = ctx.getSource();
        if (PackSessionRegistry.active() != null || ModeRegistry.active() != null) {
            source.sendFailure(Component.literal("A run is active; use /worlddatatest stop first so the check is not disturbed"));
            return 0;
        }
        if (!coreInstalled.getAsBoolean()) {
            source.sendFailure(Component.literal("WorldData Core is not installed or not ready, so there is nothing to check"));
            return 0;
        }
        try {
            return body.getAsInt();
        } catch (RuntimeException | LinkageError failure) {
            source.sendFailure(Component.literal("The check failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage()));
            return 0;
        }
    }
}
