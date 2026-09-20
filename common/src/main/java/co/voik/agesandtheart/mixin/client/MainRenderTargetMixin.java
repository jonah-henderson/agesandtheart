package co.voik.agesandtheart.mixin.client;

import co.voik.agesandtheart.client.panel.PanelTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the linking panel's world render land somewhere other than the window (design §7.8.1).
 *
 * <p><b>A Mixin because {@code LevelRenderer.renderLevel} hard-codes the main target and there is no way
 * to ask it not to.</b> It opens with
 * {@code this.targets.main = frame.importExternal("main", this.gameRenderer.mainRenderTarget())} and sizes
 * every internal target in its frame graph from that target's width and height. There is no parameter, no
 * event on either loader, and the field behind it is {@code private final}, so swapping it is not open
 * either.
 *
 * <p><b>It pays for itself twice over.</b> Because the frame graph sizes its internal targets from whatever
 * this returns, a panel-sized target makes the whole pass a fraction of a second full-screen world render
 * rather than a second full-screen world render.
 *
 * <p>The redirection is live only while {@link PanelTarget} says a panel is being drawn — one field, set
 * and cleared around a single call on the render thread — so every other caller in the game, and there are
 * many, sees exactly what it saw before.
 *
 * <p><b>26.2 moved the accessor from {@code Minecraft} to {@link GameRenderer}</b>, which owns the target
 * now. The seam is the same one and every reader went with it, {@code LevelRenderer} included — but note
 * that {@code LevelRenderer} builds its {@code SkyRenderer} with whatever this answers and <i>keeps</i> it
 * until the sky is reset, where it used to ask per frame. Each renderer therefore caches the target it was
 * built under, which is the right one per instance; a panel target replaced rather than resized would
 * leave a stale one behind.
 */
@Mixin(GameRenderer.class)
public class MainRenderTargetMixin {

    @Inject(method = "mainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$drawIntoThePanelInstead(CallbackInfoReturnable<RenderTarget> callback) {
        RenderTarget panel = PanelTarget.beingDrawnOnto();
        if (panel != null) {
            callback.setReturnValue(panel);
        }
    }
}
