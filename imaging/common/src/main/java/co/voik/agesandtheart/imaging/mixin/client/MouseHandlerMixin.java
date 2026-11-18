package co.voik.agesandtheart.imaging.mixin.client;

import co.voik.agesandtheart.imaging.client.RecorderPreview;
import co.voik.agesandtheart.imaging.client.ViewfinderView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The wheel zooms a viewfinder instead of turning the hotbar, and the mouse turns the view more slowly the further it is
 * zoomed, as a spyglass's does, so the picture moves across the screen at the same pace at any zoom; looking through a
 * recorder on a tripod, it turns the recorder's view instead of the player. Fabric has no scroll event outside screens,
 * and neither loader an event for what the mouse turns.
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

    /**
     * The mouse's turn, slowed by the zoom; and while a recorder on a tripod is looked through, given to its view rather
     * than the player, who stays as they stand, as they do looking through one on the ground.
     */
    @Redirect(
        method = "turnPlayer",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V")
    )
    private void agesandtheart_imaging$turnByTheZoom(LocalPlayer player, double across, double upAndDown) {
        double share = ViewfinderView.turningShare();
        if (RecorderPreview.aimsByTheMouse()) {
            RecorderPreview.turn(across * share, upAndDown * share);
        } else {
            player.turn(across * share, upAndDown * share);
        }
    }
}
