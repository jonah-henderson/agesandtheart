package co.voik.agesandtheart.mixin.client;

import co.voik.agesandtheart.client.panel.LecternPanels;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders a lectern's linking panel once a frame, at the point the book screen renders its own (design
 * §7.8.2).
 *
 * <p><b>Why here.</b> {@code GameRenderer.extract} runs the camera, then the level's extraction, then the
 * GUI's — and {@code BookScreen} renders its panel inside that last one, which is the one place in the frame
 * a second level render is already known to be safe. The seam between the two extractions is that same
 * point with no screen needed: after the block entities are extracted, so the lectern's renderer has said
 * whether the shown book is in view, and before the world is drawn, so the book samples this frame's
 * picture rather than the last one's.
 *
 * <p><b>26.2 inlined {@code extractGui}</b>, so the seam is named by the call before it rather than by a
 * method of its own. Landing after {@code LevelExtractor.extract} also carries the old {@code
 * shouldRenderLevel} guard for free: that call sits inside vanilla's own {@code readyForLevelRendering}
 * branch, so arriving here at all is the condition the parameter used to state.
 *
 * <p><b>Why a Mixin, and what was checked first.</b> The loaders' world-render events — Fabric's callbacks
 * and NeoForge's {@code RenderLevelStageEvent} — fire inside {@code renderLevel}, in the render phase, where
 * a second level render would be nested inside the first; what is wanted is a point in extraction. And the
 * seam is vanilla's and the same on both loaders, which is {@link LevelRendererMixin}'s argument too.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(
        method = "extract",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;"
                + "extract(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V",
            shift = At.Shift.AFTER
        )
    )
    private void agesandtheart$drawTheLecternsPanel(
            DeltaTracker deltaTracker,
            boolean advanceGameTime,
            CallbackInfo callback) {
        LecternPanels.drawFrame(deltaTracker);
    }
}
