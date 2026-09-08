package co.voik.agesandtheart.mixin.fabric.client;

import co.voik.agesandtheart.client.light.TintingRenderer;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.impl.client.renderer.RendererManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands out a decorated renderer, so coloured light reaches Fabric's chunk mesher — see
 * {@link TintingRenderer}, which carries the argument, and {@code notes/coloured-light-research.md}.
 *
 * <p><b>The only Mixin outside {@code common}</b>, and the only one that targets another mod. It exists
 * because Fabric API's renderer registry takes exactly one plug-in for the whole game and its own Indigo
 * takes it; decorating what is handed out is the one place a second party can get in.
 *
 * <p><b>{@code RendererManager} rather than {@code Renderer.get()}</b>, which is the public face of the
 * same value. The API declares it as a static method on an interface, which Mixin can target only
 * awkwardly, where the manager is an ordinary class with an ordinary static getter; and since the API
 * method does nothing but call this one, both callers are covered either way. The class is twenty lines
 * that have not changed shape in the lifetime of the module.
 *
 * <p><b>Alternatives checked.</b> Registering our own renderer is refused outright — the registry throws
 * on a second plug-in, and the manifest flag that makes Indigo stand down also disables Indigo's own
 * Mixins, so the renderer we would delegate to would be broken. Wrapping models through
 * {@code ModelLoadingPlugin} puts our quad transform on the emitter *after* Indigo's, which runs it before
 * shading rather than after, so there is no lighting to read. Nothing in the rendering events covers a
 * quad mid-build.
 */
@Mixin(RendererManager.class)
public class RendererManagerMixin {

    @Inject(method = "getRenderer", at = @At("RETURN"), cancellable = true)
    private static void agesandtheart$tintTheLightItDraws(CallbackInfoReturnable<Renderer> callback) {
        callback.setReturnValue(TintingRenderer.Companion.around(callback.getReturnValue()));
    }
}
