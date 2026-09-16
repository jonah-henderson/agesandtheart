package co.voik.agesandtheart.mixin.client;

import co.voik.agesandtheart.client.StarFissureVeil;
import co.voik.agesandtheart.client.WoundField;
import co.voik.agesandtheart.client.light.DeepLights;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the Age's wounds, in one submission rather than one per wound.
 *
 * <p>A Mixin because the alternatives are per-loader and this is not: Fabric's world-render events and
 * NeoForge's {@code RenderLevelStageEvent} are different objects with different stages, where the seam
 * itself is vanilla and identical on both. {@code submitBlockEntities} is chosen rather than a stage
 * because it is exactly where the block-entity renderer used to run — same point in the frame, same pass,
 * same pose — so the picture is unchanged and only the number of draws is.
 *
 * <p>Injected at {@code RETURN}, where the pose stack is back at the world transform: the method pushes
 * and pops around each entity, so what is left is camera-relative and untranslated, which is what
 * {@link WoundField} writes its vertices against.
 *
 * <p>{@code SubmitNodeStorage} implements {@code SubmitNodeCollector}, so it is handed straight on.
 */
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Inject(method = "submitBlockEntities", at = @At("RETURN"))
    private void agesandtheart$drawTheWounds(
            PoseStack poseStack,
            LevelRenderState levelRenderState,
            SubmitNodeStorage submitNodeStorage,
            CallbackInfo callback) {
        WoundField.INSTANCE.submit(poseStack, submitNodeStorage, levelRenderState.cameraRenderState.pos);
        // And every light in the deep, in one submission for the same reason the wounds are.
        DeepLights.INSTANCE.submit(poseStack, submitNodeStorage, levelRenderState.cameraRenderState.pos);
        // And the starfield over everything, for whoever is falling out of the Age through a tear.
        StarFissureVeil.INSTANCE.submit(poseStack, submitNodeStorage, levelRenderState.cameraRenderState.pos);
    }
}
