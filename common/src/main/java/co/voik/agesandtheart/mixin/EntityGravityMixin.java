package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.age.consequence.StrangeGravity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scales everything's gravity in an Age that pulls harder or softer ({@link StrangeGravity}).
 *
 * <p><b>Why a mixin.</b> {@code getGravity} is the one method every falling thing asks — a living thing's
 * attribute, an item's, an arrow's and a falling block's hard-coded defaults all arrive through it — and it is
 * final. The {@code GRAVITY} attribute reaches only living things, and neither loader has a gravity event.
 * Outside an Age that pulls differently it costs one empty-map check.
 */
@Mixin(Entity.class)
public abstract class EntityGravityMixin {

    @Inject(method = "getGravity", at = @At("RETURN"), cancellable = true)
    private void agesandtheart$pulledByTheAge(CallbackInfoReturnable<Double> gravity) {
        double factor = StrangeGravity.of(((Entity) (Object) this).level());
        if (factor != StrangeGravity.ORDINARY) gravity.setReturnValue(gravity.getReturnValue() * factor);
    }
}
