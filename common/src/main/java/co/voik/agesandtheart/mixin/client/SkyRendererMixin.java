package co.voik.agesandtheart.mixin.client;

import co.voik.agesandtheart.client.AgeSky;
import co.voik.agesandtheart.client.Blaze3dSkyCanvas;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.world.level.MoonPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws an Age's own suns, moons and stars instead of vanilla's one of each.
 *
 * A Mixin because on 26.1 there is nothing else: {@code DimensionSpecialEffects} no longer exists,
 * Fabric API dropped {@code DimensionRenderingRegistry}, and a per-Age {@code DimensionType} still cannot
 * reach the client. The alternatives are enumerated in {@code notes/version-upgrade.md}.
 *
 * <p>The seam is one method wide and it is the method named for exactly what we take over. When the level
 * is not an Age, or its sky is one vanilla can draw anyway, {@link AgeSky#draw} declines and this does not
 * cancel — so an ordinary world runs vanilla's own code rather than an imitation of it.
 *
 * <p>The {@code poseStack} is not forwarded: vanilla is handed a fresh one here and pushes our transform
 * onto {@code RenderSystem.getModelViewStack()} anyway, which is where the canvas writes.
 */
@Mixin(SkyRenderer.class)
public class SkyRendererMixin {

    @Inject(method = "renderSunMoonAndStars", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$drawTheAgesSky(
        PoseStack poseStack,
        float sunAngle,
        float moonAngle,
        float starAngle,
        MoonPhase moonPhase,
        float rainBrightness,
        float starBrightness,
        CallbackInfo callback
    ) {
        boolean drewTheAgesOwnSky = AgeSky.INSTANCE.draw(
            Blaze3dSkyCanvas.INSTANCE,
            sunAngle,
            moonAngle,
            starAngle,
            moonPhase,
            rainBrightness,
            starBrightness
        );
        if (drewTheAgesOwnSky) {
            callback.cancel();
        }
    }
}
