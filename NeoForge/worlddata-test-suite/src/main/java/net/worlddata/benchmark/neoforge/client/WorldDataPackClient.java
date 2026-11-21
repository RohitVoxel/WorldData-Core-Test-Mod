package net.worlddata.benchmark.neoforge.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.worlddata.benchmark.common.pack.PackSession;
import net.worlddata.benchmark.common.pack.PackSessionRegistry;
import net.worlddata.benchmark.testsuite.WorldDataPack;

@Mod(value = WorldDataPack.MOD_ID, dist = Dist.CLIENT)
public final class WorldDataPackClient {

    public WorldDataPackClient() {
        NeoForge.EVENT_BUS.addListener(WorldDataPackClient::onClientTickPost);
    }

    private static void onClientTickPost(ClientTickEvent.Post event) {
        PackSession session = PackSessionRegistry.active();
        if (session != null) session.onClientTick();
    }
}
