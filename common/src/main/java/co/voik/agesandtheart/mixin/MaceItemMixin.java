package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.content.CrushingResistance;
import java.util.function.Predicate;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.MaceItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a mace's smash land as an ordinary blow on someone under {@link CrushingResistance}, and leaves them
 * standing when it lands beside them.
 *
 * <p><b>Why a Mixin.</b> Both halves live in {@link MaceItem} and neither loader has an event for either.
 * The fall-distance bonus — Density's share included — is all of {@code getAttackDamageBonus}, so
 * answering nothing there leaves the mace's own damage. The shockwave pushes everyone its private
 * {@code knockbackPredicate} accepts, so narrowing what it returns is the whole of leaving them out.
 */
@Mixin(MaceItem.class)
public abstract class MaceItemMixin {

    @Inject(method = "getAttackDamageBonus", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$noSmashBonus(
        Entity victim, float ignoredDamage, DamageSource damageSource, CallbackInfoReturnable<Float> callback
    ) {
        if (CrushingResistance.isResisting(victim)) callback.setReturnValue(0.0F);
    }

    @Inject(method = "knockbackPredicate", at = @At("RETURN"), cancellable = true)
    private static void agesandtheart$standThroughTheShockwave(
        Entity attacker, Entity entity, CallbackInfoReturnable<Predicate<LivingEntity>> callback
    ) {
        Predicate<LivingEntity> pushed = callback.getReturnValue();
        callback.setReturnValue(nearby -> pushed.test(nearby) && !CrushingResistance.isResisting(nearby));
    }
}
