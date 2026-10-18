package co.voik.agesandtheart.mixin.client;

import co.voik.agesandtheart.client.Downpours;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Rain in a deluge falling on biomes that never see rain — a savanna, a desert — which is how a deluge shows that
 * something is wrong ({@link Downpours#precipitationUnder}).
 *
 * <p>Precipitation is a fixed property of each biome, not an environment attribute an Age can layer over, and
 * neither loader has an event for it. {@code getPrecipitationAt} is what the weather renderer's columns and
 * {@code tickWeatherEffects}' splashes both ask, so one redirect covers what is seen and heard.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelRainMixin {

    @Redirect(
        method = "getPrecipitationAt",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/biome/Biome$Precipitation;"
        )
    )
    private Biome.Precipitation agesandtheart$rainInADeluge(Biome biome, BlockPos at, int seaLevel) {
        return Downpours.precipitationUnder((ClientLevel) (Object) this, biome.getPrecipitationAt(at, seaLevel));
    }
}
