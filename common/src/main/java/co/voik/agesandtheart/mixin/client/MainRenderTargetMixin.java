package co.voik.agesandtheart.mixin.client;

import co.voik.agesandtheart.client.panel.PanelTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the linking panel's world render land somewhere other than the window (design §7.8.1).
 *
 * <p><b>A Mixin because {@code LevelRenderer.renderLevel} hard-codes the main target and there is no way
 * to ask it not to.</b> It opens with
 * {@code this.targets.main = frame.importExternal("main", this.minecraft.getMainRenderTarget())} and sizes
 * every internal target in its frame graph from that target's width and height. There is no parameter, no
 * event on either loader, and {@code Minecraft.mainRenderTarget} is {@code private final}, so swapping the
 * field is not open either.
 *
 * <p><b>It pays for itself twice over.</b> Because the frame graph sizes its internal targets from whatever
 * this returns, a panel-sized target makes the whole pass a fraction of a second full-screen world render
 * rather than a second full-screen world render.
 *
 * <p>The redirection is live only while {@link PanelTarget} says a panel is being drawn — one field, set
 * and cleared around a single call on the render thread — so every other caller in the game, and there are
 * many, sees exactly what it saw before.
 */
@Mixin(Minecraft.class)
public class MainRenderTargetMixin {

    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$drawIntoThePanelInstead(CallbackInfoReturnable<RenderTarget> callback) {
        RenderTarget panel = PanelTarget.beingDrawnOnto();
        if (panel != null) {
            callback.setReturnValue(panel);
        }
    }
}
