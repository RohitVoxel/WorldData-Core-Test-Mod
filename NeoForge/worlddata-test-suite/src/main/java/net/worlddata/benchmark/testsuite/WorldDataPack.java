package net.worlddata.benchmark.testsuite;

import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.worlddata.benchmark.common.mode.ModeRegistry;
import net.worlddata.benchmark.common.mode.ModeRun;
import net.worlddata.benchmark.common.pack.PackSession;
import net.worlddata.benchmark.common.pack.PackSessionRegistry;
import net.worlddata.benchmark.corecheck.CoreCheckScheduler;
import net.worlddata.benchmark.neoforge.ModeRunner;
import net.worlddata.benchmark.neoforge.PackRunner;
import net.worlddata.benchmark.neoforge.registration.PackCommand;

@Mod(WorldDataPack.MOD_ID)
public final class WorldDataPack {

    public static final String MOD_ID = "worlddata_core_test";

    public WorldDataPack() {
        NeoForge.EVENT_BUS.addListener(WorldDataPack::onServerTickPre);
        NeoForge.EVENT_BUS.addListener(WorldDataPack::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(WorldDataPack::onServerStopping);
        PackCommand.register();
    }

    private static void onServerTickPre(ServerTickEvent.Pre event) {
        PackSession session = PackSessionRegistry.active();
        if (session != null) session.onServerTickStart(event.getServer().getTickCount());
        ModeRun mode = ModeRegistry.active();
        if (mode != null) mode.onServerTickStart(event.getServer().getTickCount());
    }

    private static void onServerTickPost(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
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
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        PackRunner.abortActive(event.getServer());
        ModeRunner.abortActive(event.getServer());
    }
}
