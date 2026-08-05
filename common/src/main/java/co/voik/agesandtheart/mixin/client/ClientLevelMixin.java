package co.voik.agesandtheart.mixin.client;

import co.voik.agesandtheart.client.AgeAir;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Painting an Age's air on the client.
 *
 * <p>The one thing a server cannot do for itself. {@code ServerLevel.setEnvironmentAttributes} is public,
 * so every gameplay attribute is settled server-side; the visual half is read from {@code ClientLevel}'s
 * own {@code private final} system, built in its constructor from the dimension type and the biomes. There
 * is no setter and no event, and the value it builds is what every renderer asks.
 *
 * <p>So this joins the one seam there is: the private method that assembles the layers. Injecting at its
 * return leaves vanilla's whole stack intact underneath and adds ours on top, which is exactly what an
 * Age's air is — a layer over the world it is standing in.
 *
 * <p>Ordered against the payload rather than hoped about: {@code Skies.tellAbout} is sent before the
 * dimension change, both go down one stream in write order, so the look is known before this runs.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    @Inject(method = "addEnvironmentAttributeLayers", at = @At("RETURN"), cancellable = true)
    private void agesandtheart$paintTheAir(
            EnvironmentAttributeSystem.Builder builder,
            CallbackInfoReturnable<EnvironmentAttributeSystem.Builder> layers) {
        layers.setReturnValue(AgeAir.paint((ClientLevel) (Object) this, layers.getReturnValue()));
    }
}
