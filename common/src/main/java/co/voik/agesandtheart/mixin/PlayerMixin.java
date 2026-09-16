package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.worldgen.fissure.StarFissureFall;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a star fissure take a player through the ground under it.
 *
 * <p><b>Why a Mixin.</b> A tear is one block deep and a player is nearly two, so the eyes can only be
 * inside one if the body is somewhere the world would not otherwise allow. The permission for that is
 * {@code Entity.noPhysics}, and {@link Player#tick()} assigns it from {@code isSpectator()} on the way in —
 * every tick, on both sides. There is nothing to subscribe to: neither loader has a per-player physics
 * hook, and the field is reset before any event either of them fires would run.
 *
 * <p><b>Both sides, deliberately.</b> {@code Player} is common, so this binds on the server and on the
 * client's own {@code LocalPlayer}. That is what makes the fall smooth: the two simulate the same rule from
 * the same blocks and arrive at the same place, so nothing has to be corrected over the wire.
 *
 * <p><b>The first injection is only a reading, and it has to be taken before vanilla's own assignment.</b>
 * Whether a fall is already running is the difference between "a tear is against the body" and "a tear is
 * still overhead" — see {@link StarFissureFall#takesHold}. Vanilla overwrites the flag at the head of the
 * method, so the answer is gone by the time anything else could ask; {@link #agesandtheart$falling} holds it
 * for the length of one tick and is scratch, not state.
 *
 * <p>The second lands on vanilla's assignment rather than at {@code HEAD}, because the flag has to be true
 * before {@code aiStep} reads it later in the same tick — {@code Player.tick} writes {@code noPhysics}
 * exactly once and nothing else in the method touches it. The third runs at {@code TAIL}, once movement has
 * been resolved, which is the only point at which taking the sideways part of it away means "and the tear
 * is still overhead".
 *
 * @see StarFissureFall
 */
@Mixin(Player.class)
public abstract class PlayerMixin {

    @Unique
    private boolean agesandtheart$falling;

    @Inject(method = "tick", at = @At("HEAD"))
    private void agesandtheart$rememberingTheFall(CallbackInfo callback) {
        Player player = (Player) (Object) this;
        this.agesandtheart$falling = player.noPhysics && !player.isSpectator();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/world/entity/player/Player;noPhysics:Z",
            shift = At.Shift.AFTER
        )
    )
    private void agesandtheart$fallingIntoATear(CallbackInfo callback) {
        Player player = (Player) (Object) this;
        if (StarFissureFall.takesHold(player, this.agesandtheart$falling)) {
            player.noPhysics = true;
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void agesandtheart$heldUnderTheTear(CallbackInfo callback) {
        StarFissureFall.carry((Player) (Object) this);
    }

    /**
     * Nothing may be broken while falling through a tear.
     *
     * <p>The fall passes through solid ground, so everything within reach is something the player is
     * *inside* rather than in front of — and in creative, where reach is not the limit, that is the whole
     * hillside going past. Vanilla's own gate for "you may not break this one" is the honest place to say
     * so: {@code ServerPlayerGameMode} consults it before both the slow break and creative's instant one.
     */
    @Inject(method = "blockActionRestricted", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$breakingNothingOnTheWayDown(
            Level level, BlockPos pos, GameType gameType, CallbackInfoReturnable<Boolean> callback) {
        if (StarFissureFall.isFalling((Player) (Object) this)) {
            callback.setReturnValue(true);
        }
    }

    /** And nothing may be placed or used against a block, for the same reason. */
    @Inject(method = "mayUseItemAt", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$buildingNothingOnTheWayDown(
            BlockPos pos, Direction face, ItemStack held, CallbackInfoReturnable<Boolean> callback) {
        if (StarFissureFall.isFalling((Player) (Object) this)) {
            callback.setReturnValue(false);
        }
    }
}
