package co.voik.agesandtheart.imaging.mixin.client;

import co.voik.agesandtheart.imaging.client.ViewfinderView;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No hand and no block outline through a viewfinder. 26.3's F1 hides neither the hand nor anything a loader could ask
 * about, and Fabric has no hand-rendering event.
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
}
