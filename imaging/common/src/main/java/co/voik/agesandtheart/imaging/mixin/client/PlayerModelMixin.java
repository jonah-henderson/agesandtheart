package co.voik.agesandtheart.imaging.mixin.client;

import co.voik.agesandtheart.imaging.client.RecorderRaised;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A raised recorder's arms reach for it: each straight ahead of its shoulder and up toward eye level, where the
 * recorder's handle and crank stand a little out from either side of the face, turning and tilting with the head.
 * Set after everything else has posed the arms, the crouch's lowering included. No vanilla arm pose reaches there,
 * and neither loader has an event for posing a player's arms.
 */
@Mixin(PlayerModel.class)
public class PlayerModelMixin {

    /** How far above straight ahead each arm reaches: the recorder at eye level, about twelve pixels out. */
    @Unique
    private static final float RAISED_ABOVE_LEVEL = 0.49f;

    @Unique
    private static final float STRAIGHT_AHEAD = (float) (-Math.PI / 2);

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("RETURN"))
    private void agesandtheart_imaging$armsToTheRecorder(AvatarRenderState state, CallbackInfo callback) {
        if (!((RecorderRaised) state).agesandtheart_imaging$isRecorderRaised()) return;
        HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
        float reach = STRAIGHT_AHEAD - RAISED_ABOVE_LEVEL + model.head.xRot;
        model.rightArm.xRot = reach;
        model.leftArm.xRot = reach;
        model.rightArm.yRot = model.head.yRot;
        model.leftArm.yRot = model.head.yRot;
        model.rightArm.zRot = 0.0f;
        model.leftArm.zRot = 0.0f;
    }
}
