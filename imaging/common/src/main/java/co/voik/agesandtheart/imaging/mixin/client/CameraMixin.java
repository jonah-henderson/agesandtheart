package co.voik.agesandtheart.imaging.mixin.client;

import co.voik.agesandtheart.imaging.Sketches;
import co.voik.agesandtheart.imaging.client.RecorderPreview;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Looking through a set-down image recorder, the field of view it takes pictures at rather than the player's own. The
 * view is then not the player's, so the field-of-view modifier the viewfinder's zoom rides is never asked; NeoForge has
 * an event for any view's field of view and Fabric none.
 */
@Mixin(Camera.class)
public class CameraMixin {

    @Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
    private void agesandtheart_imaging$recorderFieldOfView(float partialTicks, CallbackInfoReturnable<Float> callback) {
        if (RecorderPreview.isLooking()) callback.setReturnValue(Sketches.ORDINARY_FIELD_OF_VIEW);
    }
}
