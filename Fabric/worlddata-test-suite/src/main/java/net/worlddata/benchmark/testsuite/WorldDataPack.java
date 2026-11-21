package net.worlddata.benchmark.testsuite;

import net.worlddata.benchmark.common.mode.ModeRegistry;
import net.worlddata.benchmark.common.mode.ModeRun;
import net.worlddata.benchmark.common.pack.PackSession;
import net.worlddata.benchmark.common.pack.PackSessionRegistry;
import net.worlddata.benchmark.corecheck.CoreCheckScheduler;
import net.worlddata.benchmark.fabric.ModeRunner;
import net.worlddata.benchmark.fabric.PackRunner;
import net.worlddata.benchmark.fabric.registration.PackCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public final class WorldDataPack implements ModInitializer {

    @Override
    public void onInitialize() {
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            PackSession session = PackSessionRegistry.active();
            if (session != null) session.onServerTickStart(server.getTickCount());
            ModeRun mode = ModeRegistry.active();
            if (mode != null) mode.onServerTickStart(server.getTickCount());
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            CoreCheckScheduler.tick(server);
            PackSession session = PackSessionRegistry.active();
            if (session != null) {
                session.onServerTickEnd(server.getTickCount());
                if (session.isFinished()) PackRunner.finish(server, session);
            }
            ModeRun mode = ModeRegistry.active();
            if (mode != null) {
                mode.onServerTickEnd(server.getTickCount());
                if (mode.isFinished()) ModeRunner.finish(server, mode);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            PackRunner.abortActive(server);
            ModeRunner.abortActive(server);
        });
        PackCommand.register();
    }
}
