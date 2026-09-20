package co.voik.agesandtheart.mixin.client;

import co.voik.agesandtheart.client.Storms;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops vanilla's rain splashes and ambient flecks while a blizzard is blowing.
 *
 * <p>This is the other half of {@link WeatherEffectRendererMixin} and exists only because the two halves
 * are no longer on the same class. Through 26.1 this was
 * {@code WeatherEffectRenderer.tickRainParticles(level, camera, ticks, particleStatus, radius)}; <b>26.2
 * moved it to {@code ClientLevel.tickWeatherEffects()}</b>, which takes nothing and reads the camera, the
 * particle setting and the weather radius off {@code Minecraft} itself. A tick belongs to the level rather
 * than to a renderer, so the move is a sensible one — but it splits a suppression that reads as one idea,
 * and the reasoning for it lives next door.
 *
 * <p><b>Suppressing the whole method is wider than the old injection was</b>, because vanilla folded the
 * rain <i>sound</i> in with the particles. That is wanted: a blizzard that pattered with rain audio was
 * always wrong, and {@link Storms} plays its own.
 */
@Mixin(ClientLevel.class)
public abstract class WeatherParticlesMixin {

    @Inject(method = "tickWeatherEffects", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$noGentleFlecks(CallbackInfo callback) {
        if (Storms.drawingItsOwn()) callback.cancel();
    }
}
