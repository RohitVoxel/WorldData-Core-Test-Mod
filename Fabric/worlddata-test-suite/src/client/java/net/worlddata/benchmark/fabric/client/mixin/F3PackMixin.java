package net.worlddata.benchmark.fabric.client.mixin;

import net.worlddata.benchmark.fabric.client.PackDebugLines;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(DebugScreenOverlay.class)
public abstract class F3PackMixin {

    private static final int VANILLA_TRAILING_ROWS = 4;

    @Inject(method = "getGameInformation", at = @At("RETURN"))
    private void worlddatapack$addPackLines(CallbackInfoReturnable<List<String>> cir) {
        Minecraft minecraft = Minecraft.getInstance();
        List<String> lines = cir.getReturnValue();
        int rowsOnScreen = (minecraft.getWindow().getGuiScaledHeight() - 2) / minecraft.font.lineHeight;
        int freeRows = rowsOnScreen - (lines.size() + VANILLA_TRAILING_ROWS);
        lines.addAll(0, PackDebugLines.build(freeRows));
    }
}
