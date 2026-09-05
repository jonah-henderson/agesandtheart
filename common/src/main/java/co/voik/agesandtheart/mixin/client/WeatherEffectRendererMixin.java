package co.voik.agesandtheart.mixin.client;

import co.voik.agesandtheart.client.Storms;
import net.minecraft.client.Camera;
import net.minecraft.server.level.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Takes vanilla's own precipitation off the screen while a blizzard is blowing.
 *
 * <p>Vanilla draws rain and snow as gently falling columns, which is right for weather and reads as a lie
 * in a storm that is filling the ground in front of you — the two were on screen together and the gentle
 * one stood out. {@link Storms} throws its own snow along the wind instead, so this only has to stop the
 * drawing that contradicts it.
 *
 * <p><b>Why a Mixin.</b> Neither loader offers a way to suppress precipitation. Fabric's
 * {@code WorldRenderEvents} are additive — they let you draw more, never less — and NeoForge's
 * {@code RenderLevelStageEvent} is the same shape; nothing on either side cancels vanilla's own weather
 * pass. The alternative would be a weather renderer of ours replacing vanilla's outright, which is a great
 * deal more code to end up drawing nothing.
 *
 * <p><b>Both entry points, because they are two different things.</b> {@code render} draws the falling
 * columns and {@code tickRainParticles} spawns the splashes and the ambient flecks; suppressing only the
 * first leaves the second pattering away in a whiteout.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {

    @Inject(method = "render(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/state/level/WeatherRenderState;)V", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$ourOwnSnowInstead(Vec3 cameraPos, WeatherRenderState state, CallbackInfo callback) {
        if (Storms.drawingItsOwn()) callback.cancel();
    }

    @Inject(method = "tickRainParticles", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$noGentleFlecks(
            ClientLevel level,
            Camera camera,
            int ticks,
            ParticleStatus particleStatus,
            int weatherRadius,
            CallbackInfo callback) {
        if (Storms.drawingItsOwn()) callback.cancel();
    }
}
