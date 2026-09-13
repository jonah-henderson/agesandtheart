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
 * a second level render is already known to be safe. The head of {@code extractGui} is that same point with
 * no screen needed: after the block entities are extracted, so the lectern's renderer has said whether the
 * shown book is in view, and before the world is drawn, so the book samples this frame's picture rather
 * than the last one's.
 *
 * <p><b>Why a Mixin, and what was checked first.</b> The loaders' world-render events — Fabric's callbacks
 * and NeoForge's {@code RenderLevelStageEvent} — fire inside {@code renderLevel}, in the render phase, where
 * a second level render would be nested inside the first; what is wanted is a point in extraction. And the
 * seam is vanilla's and the same on both loaders, which is {@link LevelRendererMixin}'s argument too.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "extractGui", at = @At("HEAD"))
    private void agesandtheart$drawTheLecternsPanel(
            DeltaTracker deltaTracker,
            boolean shouldRenderLevel,
            boolean resourcesLoaded,
            CallbackInfo callback) {
        if (shouldRenderLevel) {
            LecternPanels.drawFrame(deltaTracker);
        }
    }
}
