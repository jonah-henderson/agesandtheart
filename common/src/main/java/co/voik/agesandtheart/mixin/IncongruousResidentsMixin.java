package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.generation.IncongruousSpawns;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts something else in place of half the creatures a structure is built with in an {@code incongruous inhabitants}
 * Age ({@link IncongruousSpawns#inPlaceOf}): a witch hut's witch and cat, a mansion's illagers.
 *
 * <p><b>Why a Mixin.</b> A structure's pieces place their creatures into the region its chunk is generated in, which
 * neither loader's entity events see. {@code addFreshEntity} on the region is the one call they all go through.
 */
@Mixin(WorldGenRegion.class)
public abstract class IncongruousResidentsMixin {

    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$somethingElseInItsPlace(Entity entity, CallbackInfoReturnable<Boolean> callback) {
        WorldGenRegion region = (WorldGenRegion) (Object) this;
        Mob instead = IncongruousSpawns.inPlaceOf(region, entity);
        if (instead != null) {
            callback.setReturnValue(IncongruousSpawns.placeInstead(region, instead));
        }
    }
}
