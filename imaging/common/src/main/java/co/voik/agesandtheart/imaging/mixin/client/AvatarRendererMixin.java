package co.voik.agesandtheart.imaging.mixin.client;

import co.voik.agesandtheart.imaging.Viewfinder;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import co.voik.agesandtheart.imaging.client.RecorderRaised;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player looking through a viewfinder is drawn crouched, the recorder up before the face and both arms raised to it
 * ({@link PlayerModelMixin} reaches them to its handle and crank). The recorder goes where vanilla puts a spyglass in use: the player is drawn as using
 * the hand that holds it, with the recorder as the item held to the eye, which draws it at the head in the model's
 * {@code head} pose, centred by {@link PlayerItemInHandLayerMixin}. Only the drawing changes: their eye and bounds stay standing, since sneaking would lower the
 * recorder, and whether they are looking rides the viewfinder's slowing, which vanilla sends to every client that
 * sees them. Neither loader has an event for a player's arm pose or held item that the other has too.
 */
@Mixin(AvatarRenderer.class)
public class AvatarRendererMixin {

    @Inject(
        method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
        at = @At("RETURN")
    )
    private void agesandtheart_imaging$poseBehindTheViewfinder(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo callback) {
        RecorderRaised raised = (RecorderRaised) state;
        raised.agesandtheart_imaging$setRecorderRaised(false);
        if (!(avatar instanceof Player player) || !Viewfinder.INSTANCE.isLooking(player)) return;
        InteractionHand hand = Viewfinder.INSTANCE.handHoldingTheRecorder(player);
        if (hand == null) return;
        raised.agesandtheart_imaging$setRecorderRaised(true);
        state.isCrouching = true;
        state.rightArmPose = HumanoidModel.ArmPose.CROSSBOW_HOLD;
        state.leftArmPose = HumanoidModel.ArmPose.CROSSBOW_HOLD;
        state.isUsingItem = true;
        state.useItemHand = hand;
        ((LivingEntityRendererAccessor) this).agesandtheart_imaging$itemModelResolver()
            .updateForLiving(state.heldOnHead, player.getItemInHand(hand), ItemDisplayContext.HEAD, player);
    }
}
