package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.age.phenomena.Deluge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A deluge's rain falling on biomes that never see rain, on the server: what {@code isRainingAt} answers, so
 * the rain puts out fires, fills cauldrons and soaks whoever stands in it there too ({@link Deluge#precipitationIn}).
 *
 * <p>Precipitation is a fixed property of each biome and neither loader has an event for it. Redirecting the
 * biome's own answer inside {@code precipitationAt} keeps vanilla's earlier refusals — not raining, no sky, under
 * cover — exactly as they are.
 */
@Mixin(Level.class)
public abstract class LevelRainMixin {

    @Redirect(
        method = "precipitationAt",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;I)Lnet/minecraft/world/level/biome/Biome$Precipitation;"
        )
    )
    private Biome.Precipitation agesandtheart$rainInADeluge(Biome biome, BlockPos at, int seaLevel) {
        return Deluge.precipitationIn((Level) (Object) this, biome.getPrecipitationAt(at, seaLevel));
    }
}
