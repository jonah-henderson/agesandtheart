package co.voik.agesandtheart.mixin.client;

import co.voik.agesandtheart.client.Downpours;
import co.voik.agesandtheart.client.Storms;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Takes vanilla's own precipitation off the screen while a blizzard is blowing, and makes it heavier in a
 * deluge.
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
 * <p><b>Both entry points, because they are two different things.</b> The drawing puts up the falling
 * columns and the particle tick spawns the splashes and the ambient flecks; suppressing only the first
 * leaves the second pattering away in a whiteout. Since 26.2 the second one is no longer on this class at
 * all — it is {@code ClientLevel.tickWeatherEffects}, and it is suppressed by
 * {@link WeatherParticlesMixin}.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {

    /**
     * A blizzard's own snow instead of vanilla's, and a deluge's rain made heavier — both decided on the
     * frame's extracted state.
     *
     * <p><b>Emptying the state rather than cancelling a draw, which 26.3 forced and then improved on.</b>
     * The old seam was {@code render(Vec3, WeatherRenderState)}, one method that laid the columns out and
     * drew them. 26.3 split it in two: {@code prepare(Vec3, WeatherRenderState)} builds the geometry into
     * the vertex buffer, and {@code render(WeatherRenderState, RenderPass)} issues the draw — so the old
     * descriptor matches nothing and the mixin could not be applied at all.
     *
     * <p>Cancelling the half that kept the old parameters would have been the natural port and would have
     * been wrong: {@code prepare} is the *builder*, and the draw guards on the render state's column lists
     * rather than on anything prepare sets, so a suppressed prepare leaves the draw putting the previous
     * frame's geometry back on screen — a whiteout frozen in place. There are two public draws as well
     * ({@code render} and {@code renderOit}), and both funnel into one private method guarded by those
     * same lists.
     *
     * <p>So the columns are what to take away. An empty state is vanilla's own "no weather this frame":
     * prepare builds nothing, both draws skip themselves, and no descriptor anybody may split again is
     * named. It also costs less than cancelling did — the geometry is never built.
     *
     * <p>One injection for both jobs because they meet here: thickening a list we are about to empty would
     * be work thrown away, and the order of two injections at the same point is not ours to rely on.
     */
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void agesandtheart$ourOwnSnowInstead(
            ClientLevel level,
            float partialTicks,
            Vec3 cameraPos,
            WeatherRenderState renderState,
            CallbackInfo callback) {
        if (Storms.drawingItsOwn()) {
            renderState.rainColumns.clear();
            renderState.snowColumns.clear();
            return;
        }
        Downpours.thicken(renderState.rainColumns);
    }
}
