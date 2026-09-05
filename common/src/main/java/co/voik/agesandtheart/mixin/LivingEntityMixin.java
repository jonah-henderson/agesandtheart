package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.content.Toolbox;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets a toolbox hand you a spare when a tool breaks in your hands.
 *
 * <p><b>Why a Mixin.</b> There is no shared event. NeoForge has {@code PlayerDestroyItemEvent}; Fabric API
 * has no item-break event of any kind, so half the mod would have a seam and half would not — which is the
 * case the SPI exists for, except that a service with one method firing on one loader is a worse answer
 * than one method both loaders already call.
 *
 * <p>{@link LivingEntity#onEquippedItemBroken} is that method: public, called from
 * {@code ItemStack.hurtAndBreak} for exactly this, and already carrying both the item that broke and the
 * slot it broke out of. {@link Toolbox} decides whether any of it applies.
 *
 * <p><b>At {@code TAIL}, so vanilla has finished with the slot.</b> The method stops the broken item's
 * location-based effects and broadcasts the break; filling the slot before that would have vanilla tidying
 * up around the replacement.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "onEquippedItemBroken", at = @At("TAIL"))
    private void agesandtheart$reachForASpare(Item broken, EquipmentSlot slot, CallbackInfo callback) {
        Toolbox.replaceBroken((LivingEntity) (Object) this, broken, slot);
    }
}
