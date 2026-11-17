package co.voik.agesandtheart.imaging.mixin.client;

import co.voik.agesandtheart.imaging.client.Capture;
import co.voik.agesandtheart.imaging.client.ViewfinderView;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No hand and no block outline through a viewfinder. 26.3's F1 hides neither the hand nor anything a loader could ask
 * about, and Fabric has no hand-rendering event. And the frame a picture is taken from, read back once the level and
 * its post effects are drawn and before the GUI is, which no loader event falls between on both sides.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void agesandtheart_imaging$noHandThroughAViewfinder(CallbackInfo callback) {
        if (ViewfinderView.isLooking()) callback.cancel();
    }

    @Inject(method = "shouldRenderBlockOutline", at = @At("HEAD"), cancellable = true)
    private void agesandtheart_imaging$noOutlineThroughAViewfinder(CallbackInfoReturnable<Boolean> callback) {
        if (ViewfinderView.isLooking()) callback.setReturnValue(false);
    }

    @Inject(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER)
    )
    private void agesandtheart_imaging$takeThePicture(CallbackInfo callback) {
        Capture.afterThePostEffects();
    }
}
