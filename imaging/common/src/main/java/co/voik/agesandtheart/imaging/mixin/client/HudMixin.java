package co.voik.agesandtheart.imaging.mixin.client;

import co.voik.agesandtheart.imaging.client.ShutterIris;
import co.voik.agesandtheart.imaging.client.ViewfinderHint;
import co.voik.agesandtheart.imaging.client.ViewfinderView;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No HUD through a viewfinder but the action bar, how to lower it and zoom said where the action bar stands
 * ({@link ViewfinderHint}), as a boat says how to get out, and the shutter's iris closing and opening as a picture is taken. F1's
 * own flag is the player's, and toggling it would have to be put back exactly; and neither loader has an event that
 * hides the whole HUD.
 */
@Mixin(Hud.class)
public abstract class HudMixin {

    @Shadow
    protected abstract void extractOverlayMessage(GuiGraphicsExtractor graphics, DeltaTracker delta);

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void agesandtheart_imaging$noHudThroughAViewfinder(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo callback) {
        if (!ViewfinderView.isLooking()) return;
        extractOverlayMessage(graphics, delta);
        ViewfinderHint.draw(graphics);
        ShutterIris.draw(graphics);
        callback.cancel();
    }
}
