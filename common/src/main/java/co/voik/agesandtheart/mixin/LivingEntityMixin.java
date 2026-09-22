package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.content.RimeSkates;
import co.voik.agesandtheart.content.Temperstone;
import co.voik.agesandtheart.content.Toolbox;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    /**
     * <p><b>The broken stack, and only its item passed on.</b> 26.3 widened this parameter from
     * {@code Item} to {@code ItemStack} — a change nothing in the build can catch, because an
     * {@code @Inject} callback's parameters are checked when the mixin is applied and not when it is
     * compiled. It failed at the first server boot after the port, which is the only place it could.
     * {@link Toolbox} wants the kind of thing that broke rather than the husk of it, so the conversion
     * belongs here at the seam.
     */
    @Inject(method = "onEquippedItemBroken", at = @At("TAIL"))
    private void agesandtheart$reachForASpare(ItemStack broken, EquipmentSlot slot, CallbackInfo callback) {
        Toolbox.replaceBroken((LivingEntity) (Object) this, broken.getItem(), slot);
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
        Float skated = RimeSkates.gripUnderfoot((LivingEntity) (Object) this);
        return skated == null ? blockFriction : skated;
    }

    /**
     * Lets rime skates decide how much of their wearer's speed survives the tick.
     *
     * <p><b>The second float stored in {@code travelInAir}, and it has to be its own injection.</b> Vanilla
     * works this out as {@code blockFriction * 0.91} from the very local above, so the one number sets both
     * how fast a skater accelerates and how long they keep it — and sets them against each other, the
     * acceleration going as the inverse cube. Modifying the product separately is what takes the two dials
     * apart, and it is also the only place that can say "and nothing at all is lost in the air".
     *
     * <p>{@link RimeSkates} carries the reasoning and the governor that airborne freedom needs.
     */
    @ModifyVariable(method = "travelInAir", at = @At("STORE"), ordinal = 1)
    private float agesandtheart$keepingItsSpeed(float damping) {
        return RimeSkates.dampingOn((LivingEntity) (Object) this, damping);
    }

    /**
     * Spends rime skates by the distance their wearer actually covers.
     *
     * <p><b>After the move rather than during it</b>, which is the whole reason it hangs off the tick and
     * not off {@code travelInAir}: what is being charged for is ground gained, and only once movement has
     * been resolved does {@code deltaMovement} mean that — a skater held against a wall has a velocity and
     * has gone nowhere, and should not pay.
     *
     * <p>NeoForge has {@code PlayerTickEvent} and Fabric has no per-player tick event at all, so the seam
     * they share is vanilla's own. {@link RimeSkates} carries what is charged and why walking is free.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void agesandtheart$wearingTheBlades(CallbackInfo callback) {
        RimeSkates.wearFromSkating((LivingEntity) (Object) this);
    }

    /**
     * Lets temperstone climbers make any face of the stuff a ladder.
     *
     * <p>Climbability is normally the block's own business through {@code BlockTags.CLIMBABLE}, and a tag
     * cannot say "only for the entity wearing these". NeoForge has an entity-aware {@code isLadder} hook
     * where Fabric has nothing equivalent, so {@link LivingEntity#onClimbable} is the seam both share.
     *
     * <p>It only ever widens the answer: the return is set solely to {@code true}, so vanilla decides every
     * case this does not claim.
     *
     * @see co.voik.agesandtheart.content.Temperstone
     */
    @Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$climbingIt(CallbackInfoReturnable<Boolean> callback) {
        if (Temperstone.climbing((LivingEntity) (Object) this)) {
            callback.setReturnValue(true);
        }
    }

    /**
     * Lets a climber hold station on temperstone instead of sliding as a ladder does.
     *
     * <p>{@code handleOnClimbable} is private, and identical on both loaders — NeoForge patches only the
     * scaffolding test beside it. Modifying the returned vector rather than the {@code yd} local keeps this
     * off the shape of a method body that has already been patched once.
     *
     * @see co.voik.agesandtheart.content.Temperstone#heldOn
     */
    @Inject(method = "handleOnClimbable", at = @At("RETURN"), cancellable = true)
    private void agesandtheart$holdingOn(Vec3 delta, CallbackInfoReturnable<Vec3> callback) {
        Vec3 climbed = callback.getReturnValue();
        Double held = Temperstone.heldOn((LivingEntity) (Object) this, climbed.y);
        if (held != null) {
            callback.setReturnValue(new Vec3(climbed.x, held, climbed.z));
        }
    }
}
