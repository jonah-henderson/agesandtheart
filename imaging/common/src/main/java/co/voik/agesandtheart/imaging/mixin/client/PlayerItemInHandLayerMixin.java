package co.voik.agesandtheart.imaging.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.HumanoidArm;
import co.voik.agesandtheart.imaging.client.RecorderRaised;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A raised recorder is held before the middle of the face. Vanilla moves an item held to the eye toward the holding
 * hand's side, as a spyglass goes to one eye; this drops that sideways step for the recorder alone and keeps the rest.
 */
@Mixin(PlayerItemInHandLayer.class)
public class PlayerItemInHandLayerMixin {

    @Redirect(
        method = "renderItemHeldToEye",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V")
    )
    private void agesandtheart_imaging$centreTheRecorder(
        PoseStack poseStack, float sideways, float down, float forward,
        AvatarRenderState state, HumanoidArm arm, PoseStack sameStack, SubmitNodeCollector collector, int light
    ) {
        boolean isTheRecorder = ((RecorderRaised) state).agesandtheart_imaging$isRecorderRaised();
        poseStack.translate(isTheRecorder ? 0.0f : sideways, down, forward);
    }
}
