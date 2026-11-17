package co.voik.agesandtheart.imaging.mixin.client;

import co.voik.agesandtheart.imaging.client.ViewfinderView;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No HUD through a viewfinder. F1's own flag is the player's, and toggling it would have to be put back exactly; and
 * neither loader has an event that hides the whole HUD.
 */
@Mixin(Hud.class)
public class HudMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void agesandtheart_imaging$noHudThroughAViewfinder(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo callback) {
        if (ViewfinderView.isLooking()) callback.cancel();
    }
}
