package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.content.OreColliders;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets an arrow pass through the gaps in a drifting body ({@link OreColliders#passesThrough}).
 *
 * <p><b>Why a mixin.</b> The raycast is {@code ProjectileUtil.getEntityHitResult}, which tests
 * {@code entity.getBoundingBox().inflate(getPickRadius())} for every candidate inline — a cube, with no
 * way for an entity to offer a shape instead. The loaders' projectile events both fire on <i>impact</i>,
 * after the hit has been decided and the arrow has already stopped, so cancelling there leaves a shot
 * hanging in the air rather than continuing through the gap.
 *
 * <p><b>Why {@code canHitEntity} rather than the raycast itself.</b> It is far the smaller seam: vanilla
 * already asks this question once per candidate entity, so the answer only has to be refined, where
 * targeting the raycast would mean rewriting a hot loop to change one entity's test inside it.
 */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {

    @Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$missTheGaps(Entity target, CallbackInfoReturnable<Boolean> hit) {
        Projectile projectile = (Projectile) (Object) this;
        if (OreColliders.INSTANCE.passesThrough(projectile, target)) {
            hit.setReturnValue(false);
        }
    }
}
