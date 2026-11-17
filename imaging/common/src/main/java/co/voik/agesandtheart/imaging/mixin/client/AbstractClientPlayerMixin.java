package co.voik.agesandtheart.imaging.mixin.client;

import co.voik.agesandtheart.imaging.client.ViewfinderView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A viewfinder's zoom, in place of whatever else would narrow or widen the view (the viewfinder's own slowing among
 * them). This is the method a spyglass zooms through; NeoForge has an event for it and Fabric none.
 */
@Mixin(AbstractClientPlayer.class)
public class AbstractClientPlayerMixin {

    @Inject(method = "getFieldOfViewModifier", at = @At("RETURN"), cancellable = true)
    private void agesandtheart_imaging$viewfinderZoom(boolean firstPerson, float effectScale, CallbackInfoReturnable<Float> callback) {
        boolean isTheLocalPlayer = (Object) this == Minecraft.getInstance().player;
        if (isTheLocalPlayer && ViewfinderView.isLooking()) callback.setReturnValue(ViewfinderView.fieldOfViewModifier());
    }
}
