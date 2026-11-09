package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.age.consequence.Dispositions;
import co.voik.agesandtheart.age.consequence.Taming;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A warden as its Age wrote it ({@link Dispositions#forbids}): a tamed one never angered by its owner, a tamable one
 * never by whoever holds what it wants.
 *
 * <p>{@code canTargetEntity} is the warden's one gate, where every other creature's is {@code canAttack}: a vibration,
 * a scent and a projectile raise its anger through it, its anger keeps only who passes it, and its fight ends on it,
 * and its own {@code canAttack} only calls it. So this one method answers for the warden what {@code CanAttackMixin}
 * answers for the rest. A tamed warden also lays no darkness, which would fall on its owner every six seconds.
 * Neither loader has an event for a warden's anger or its darkness.
 */
@Mixin(Warden.class)
public abstract class WardenTargetingMixin {

    @Inject(method = "canTargetEntity", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$asItsAgeWroteIt(@Nullable Entity entity, CallbackInfoReturnable<Boolean> may) {
        if (entity instanceof LivingEntity living && Dispositions.forbids((Warden) (Object) this, living)) {
            may.setReturnValue(false);
        }
    }

    @Inject(method = "applyDarknessAround", at = @At("HEAD"), cancellable = true)
    private static void agesandtheart$noDarknessFromATamedOne(
        ServerLevel level, Vec3 position, @Nullable Entity source, int radius, CallbackInfo darkening
    ) {
        if (source instanceof Warden warden && Taming.isTamed(warden)) {
            darkening.cancel();
        }
    }
}
