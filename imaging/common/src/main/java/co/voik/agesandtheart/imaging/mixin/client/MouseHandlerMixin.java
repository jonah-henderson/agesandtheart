package co.voik.agesandtheart.imaging.mixin.client;

import co.voik.agesandtheart.imaging.client.ViewfinderView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The wheel zooms a viewfinder instead of turning the hotbar. Fabric has no scroll event outside screens. */
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
}
