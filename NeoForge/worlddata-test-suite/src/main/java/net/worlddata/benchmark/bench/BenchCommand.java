package net.worlddata.benchmark.bench;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.worlddata.benchmark.common.bench.BenchApi;
import net.worlddata.benchmark.common.bench.BenchMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class BenchCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger("worlddata_core_test");

    private BenchCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> tree(BooleanSupplier coreInstalled, Supplier<Path> root) {
        return Commands.literal("bench")
                .executes(ctx -> help(ctx.getSource()))
                .then(Commands.literal("compare").then(api(true, coreInstalled, root)))
                .then(api(false, coreInstalled, root));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> api(boolean compare, BooleanSupplier coreInstalled, Supplier<Path> root) {
        return Commands.argument("api", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(BenchApi.names(), builder))
                .executes(ctx -> start(ctx, compare, null, null, coreInstalled, root))
                .then(Commands.argument("count", LongArgumentType.longArg(1))
                        .executes(ctx -> start(ctx, compare, LongArgumentType.getLong(ctx, "count"), null, coreInstalled, root))
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(BenchMode.names(), builder))
                                .executes(ctx -> start(ctx, compare, LongArgumentType.getLong(ctx, "count"),
                                        StringArgumentType.getString(ctx, "mode"), coreInstalled, root))));
    }

    private static int start(CommandContext<CommandSourceStack> ctx, boolean compare, Long count, String mode,
                             BooleanSupplier coreInstalled, Supplier<Path> root) {
        CommandSourceStack source = ctx.getSource();
        if (!coreInstalled.getAsBoolean()) {
            source.sendFailure(Component.literal("WorldData Core is not installed or not ready, so there is nothing to bench"));
            return 0;
        }
        try {
            return BenchExecutor.run(source, StringArgumentType.getString(ctx, "api"), count, mode, compare, root.get());
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn("bench failed", failure);
            source.sendFailure(Component.literal("The bench failed: " + failure.getClass().getSimpleName() + ": " + failure.getMessage()));
            return 0;
        }
    }

    private static int help(CommandSourceStack source) {
        send(source, "\u00a76WorldData Core Test bench: /worlddatatest bench <api> [count] [mode], /worlddatatest bench compare <api> [count] [mode]");
        for (BenchApi api : BenchApi.values()) {
            send(source, "\u00a77" + api.commandName() + ": default " + api.defaultCount() + ", max " + api.maxCount()
                    + (api.scan() ? ", scans one whole chunk per call" : ""));
        }
        send(source, "\u00a77Modes: fixed (default, 64 positions in your chunk), random (positions around you, chunk resolved once), "
                + "acquire (random, and WorldData Core chunk lookup on every call)");
        return 1;
    }

    private static void send(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }
}
