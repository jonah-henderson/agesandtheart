package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.content.RimeSkates;
import co.voik.agesandtheart.content.Toolbox;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
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
 *
 * <p>It also carries the skates' grip on the ground, which is a second thing with no event on either side.
 * See {@link #agesandtheart$skatingOverIt}.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "onEquippedItemBroken", at = @At("TAIL"))
    private void agesandtheart$reachForASpare(Item broken, EquipmentSlot slot, CallbackInfo callback) {
        Toolbox.replaceBroken((LivingEntity) (Object) this, broken, slot);
    }

    /**
     * Lets rime skates take the ground's grip off their wearer.
     *
     * <p><b>The local rather than the call, and that is load-bearing.</b> NeoForge patches an entity-aware
     * {@code BlockBehaviour#getFriction(level, pos, entity)} into the game and Fabric has vanilla's no-arg
     * {@code Block#getFriction()}, so the two loaders do not share a call site to redirect — a
     * {@code @Redirect} would bind on one and silently miss on the other. What they *do* share is the local
     * {@code blockFriction} in {@code travelInAir} that either answer lands in, which is what this modifies.
     *
     * <p>Friction is only half of what a skate is; the other half is a movement-speed modifier on the boots
     * themselves, because {@code getFrictionInfluencedSpeed} divides acceleration by the cube of friction
     * and so makes a slicker floor accelerate you *worse*. {@link RimeSkates} carries the reasoning.
     */
    @ModifyVariable(method = "travelInAir", at = @At("STORE"), ordinal = 0)
    private float agesandtheart$skatingOverIt(float blockFriction) {
        Float skated = RimeSkates.underfoot((LivingEntity) (Object) this);
        return skated == null ? blockFriction : skated;
    }
}
