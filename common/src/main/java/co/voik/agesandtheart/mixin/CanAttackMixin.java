package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.age.consequence.Dispositions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.dolphin.Dolphin;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A friendly creature attacks nothing, and a passive one only what hit it ({@link Dispositions#forbids}).
 *
 * <p>{@code canAttack} is the question every way of taking a target asks first: {@code setTarget} through
 * {@code asValidTarget}, a target goal's search and its keeping on through {@code TargetingConditions}, and a brain's
 * {@code StartAttacking}. Vanilla's own answer differs by kind, so each class that answers it for itself is listed
 * beside {@code LivingEntity}. Fabric has no targeting event, and NeoForge's {@code LivingChangeTargetEvent} sees a
 * target being set but not a search choosing one. The warden's own gate is {@code canTargetEntity}, which its
 * {@code canAttack} calls, so it is {@code WardenTargetingMixin}'s.
 */
@Mixin({
    LivingEntity.class, Mob.class, TamableAnimal.class, Dolphin.class, IronGolem.class, Breeze.class,
    AbstractIllager.class, EnderDragon.class,
})
public abstract class CanAttackMixin {

    @Inject(method = "canAttack(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$asItsAgeWroteIt(LivingEntity target, CallbackInfoReturnable<Boolean> may) {
        if (Dispositions.forbids((LivingEntity) (Object) this, target)) {
            may.setReturnValue(false);
        }
    }
}
