package co.voik.agesandtheart.mixin.client;

import co.voik.agesandtheart.client.AgeClouds;
import co.voik.agesandtheart.client.Blaze3dSkyCanvas;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws an Age's own overcast instead of vanilla's one drifting sheet.
 *
 * The second of the two seams, same shape as {@link SkyRendererMixin}: one method, at head, cancelling
 * only when the Age has decks of its own.
 */
@Mixin(CloudRenderer.class)
public class CloudRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$drawTheAgesDecks(
        int color,
        CloudStatus cloudStatus,
        float bottomY,
        int range,
        Vec3 cameraPosition,
        long gameTime,
        float partialTicks,
        CallbackInfo callback
    ) {
        boolean drewTheAgesOwnDecks = AgeClouds.INSTANCE.draw(
            Blaze3dSkyCanvas.INSTANCE,
            cameraPosition,
            gameTime + partialTicks
        );
        if (drewTheAgesOwnDecks) {
            callback.cancel();
        }
    }
}
