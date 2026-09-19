package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.worldgen.fissure.StarFissureFall;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps water and lava from touching a player falling through a star fissure.
 *
 * <p>A fall passes through whatever is under the tear, and {@code noPhysics} stops collisions and block
 * effects but not the fluid scan in {@code Entity.baseTick}. Water anywhere in the column set
 * {@code isInWater}, and {@code LivingEntity.travel} then swapped the fall for water's drag: the player sank
 * instead of falling.
 *
 * <p>{@code EntityFluidInteraction.update} resets every tracker before it scans and scans nothing when this
 * box is null, so a null here leaves the whole fluid state empty. That covers every reader — travel, the
 * swimming pose, drowning, lava's physics — without touching any of them. Neither loader has an event for
 * it; this is the one method every fluid question goes through.
 */
@Mixin(Entity.class)
public abstract class EntityFluidInteractionMixin {

    @Inject(method = "getFluidInteractionBox", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$noFluidOnTheWayDown(CallbackInfoReturnable<AABB> box) {
        if ((Object) this instanceof Player player && StarFissureFall.isFalling(player)) {
            box.setReturnValue(null);
        }
    }
}
