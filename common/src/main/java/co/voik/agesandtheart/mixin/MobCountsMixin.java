package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.generation.SpawnCaps;
import net.minecraft.world.entity.MobCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Each player's cap on each spawn pass, as the Age's book moved it — see {@link SpawnCapsMixin}. */
@Mixin(targets = "net.minecraft.world.level.LocalMobCapCalculator$MobCounts")
public abstract class MobCountsMixin {

    @Redirect(
        method = "canSpawn",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/MobCategory;getMaxInstancesPerChunk()I")
    )
    private int agesandtheart$capped(MobCategory category) {
        return SpawnCaps.capOf(category, category.getMaxInstancesPerChunk());
    }
}
