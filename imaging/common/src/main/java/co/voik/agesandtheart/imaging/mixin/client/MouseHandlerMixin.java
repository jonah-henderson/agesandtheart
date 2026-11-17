package co.voik.agesandtheart.imaging.mixin.client;

import co.voik.agesandtheart.imaging.client.ViewfinderView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The wheel zooms a viewfinder instead of turning the hotbar, and the mouse turns the view more slowly the further it is
 * zoomed, as a spyglass's does, so the picture moves across the screen at the same pace at any zoom. Fabric has no
 * scroll event outside screens, and no event for how far the mouse turns the player.
 */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void agesandtheart_imaging$zoomTheViewfinder(long window, double horizontal, double vertical, CallbackInfo callback) {
        boolean noScreenIsOpen = Minecraft.getInstance().gui.screen() == null;
        if (noScreenIsOpen && ViewfinderView.isLooking()) {
            ViewfinderView.scrolled(vertical);
            callback.cancel();
        }
    }

    @ModifyArg(
        method = "turnPlayer",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"),
        index = 0
    )
    private double agesandtheart_imaging$turnAcrossByTheZoom(double across) {
        return across * ViewfinderView.turningShare();
    }

    @ModifyArg(
        method = "turnPlayer",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"),
        index = 1
    )
    private double agesandtheart_imaging$turnUpAndDownByTheZoom(double upAndDown) {
        return upAndDown * ViewfinderView.turningShare();
    }
}
