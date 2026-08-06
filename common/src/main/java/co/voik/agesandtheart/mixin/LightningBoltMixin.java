package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.age.phenomena.Tempest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LightningBolt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets a bolt landing in a tempest hit like one ({@link Tempest#struck}).
 *
 * <p><b>Why a mixin, when the entity-join event would carry the bolt.</b> Two things it would not carry.
 * A bolt is added to the level from inside {@code tickThunder}, so an explosion fired from that event
 * would spawn its item drops re-entrantly inside {@code addFreshEntity}; and {@code visualOnly} — the flag
 * that marks the skeleton-horse trap's harmless bolt — is private with only a setter, so telling a trap
 * apart from a strike would need the field widened in both loaders. This seam has neither problem: it is
 * the instant vanilla lights its own fire and powers a rod, so the world is already being mutated here.
 *
 * <p><b>Why after {@code powerLightningRod}.</b> The strike happens in one branch of {@code tick} —
 * {@code life == 2}, server side — and {@code life} never returns to 2, so this runs <i>once</i> per bolt
 * however many times it flashes. Landing after the rod has been powered means a rod that caught the strike
 * has already done its work before {@link Tempest#struck} decides to spare it, and a mod that cancels
 * {@code tick} outright is still obeyed.
 *
 * <p><b>Every bolt is offered, and almost every one is declined.</b> The gate is in {@code common}, and
 * costs one string comparison for lightning outside an Age.
 */
@Mixin(LightningBolt.class)
public abstract class LightningBoltMixin {

    @Shadow
    private boolean visualOnly;

    /**
     * The block the bolt came down on — one below where it stands, and private in vanilla.
     *
     * <p>Shadowed rather than recomputed: it is the position {@code powerLightningRod} uses to find its
     * rod, and a copy of the expression here would be free to drift away from the one that matters.
     */
    @Shadow
    private BlockPos getStrikePosition() {
        throw new AssertionError();
    }

    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LightningBolt;powerLightningRod()V",
            shift = At.Shift.AFTER
        )
    )
    private void agesandtheart$strikeHard(CallbackInfo callback) {
        if (this.visualOnly) {
            return;
        }
        LightningBolt bolt = (LightningBolt) (Object) this;
        if (bolt.level() instanceof ServerLevel level) {
            Tempest.struck(level, bolt, this.getStrikePosition());
        }
    }
}
