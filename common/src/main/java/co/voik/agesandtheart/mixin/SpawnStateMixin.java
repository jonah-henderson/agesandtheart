package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.generation.SpawnCaps;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** The whole level's cap on each spawn pass, as its Age's book moved it — see {@link SpawnCapsMixin}. */
@Mixin(NaturalSpawner.SpawnState.class)
public abstract class SpawnStateMixin {

    @Redirect(
        method = "canSpawnForCategoryGlobal",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/MobCategory;getMaxInstancesPerChunk()I")
    )
    private int agesandtheart$capped(MobCategory category) {
        return SpawnCaps.capOf(category, category.getMaxInstancesPerChunk());
    }
}
