package co.voik.agesandtheart.imaging.mixin.client;

import co.voik.agesandtheart.imaging.client.Capture;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The plate's flash: the frame a picture is taken from is lit as night vision lights it, and the frame after is lit as
 * it was. The lightmap is only redrawn when asked, so both frames ask. Neither loader has a lightmap event.
 */
@Mixin(LightmapRenderStateExtractor.class)
public class LightmapRenderStateExtractorMixin {

    @Inject(method = "extract", at = @At("RETURN"))
    private void agesandtheart_imaging$flash(LightmapRenderState state, float partialTick, CallbackInfo callback) {
        Capture.flashTheLightmap(state);
    }
}
