package co.voik.agesandtheart.mixin;

import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeping a dragon with no fight out of the one phase it can never leave.
 *
 * <p>{@code DragonHoldingPatternPhase.findNewTarget} rolls {@code nextInt(crystals + 3) == 0} for
 * {@code LANDING_APPROACH}, and a dragon with no {@code EnderDragonFight} counts no crystals — so the roll
 * is one in three, and the first time it lands the dragon leaves the holding pattern and does not come
 * back. <b>It circles for ever</b>, which is exactly what a walk sees.
 *
 * <p><b>The approach cannot complete away from the world origin, and not merely by an offset.</b> It reads
 * {@code new Vec3(player.getX(), 0.0, player.getZ()).normalize()} — the player's <i>absolute</i> position
 * as a direction from {@code (0, 0)} — and looks for a node forty blocks along it. Near the End's own
 * origin that is a bearing; three thousand blocks out it is noise. Correcting it would mean rewriting the
 * phase rather than relocating it, and it would buy a landing sequence that exists to sit on a podium
 * between crystal phases.
 *
 * <p><b>So this refuses the roll rather than fixing the phase, because there is nothing to land for.</b> A
 * dragon written into an Age has no podium, no crystals and no ritual — `Ages.lendTheDragon` gives a fight
 * only where the template carries one, and there it is the End and this changes nothing. What is left is
 * {@code HOLDING_PATTERN} and {@code STRAFE_PLAYER}, and the strafe is fully relative: it aims by
 * {@code target - dragon} and finds its nodes near the player, so it is correct wherever the dragon lives.
 *
 * <p><b>Ordinal 0 is the landing roll</b> and the three that follow it are the two strafe rolls and the
 * direction flip, which must keep their own randomness. Redirecting the call rather than the phase change
 * leaves every other route into {@code LANDING_APPROACH} alone — a crystal being destroyed still sends a
 * real fight's dragon down.
 */
@Mixin(net.minecraft.world.entity.boss.enderdragon.phases.DragonHoldingPatternPhase.class)
public abstract class DragonHoldingPatternMixin {

    @Redirect(
        method = "findNewTarget",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/RandomSource;nextInt(I)I",
            ordinal = 0
        )
    )
    private int agesandtheart$neverLandWithNothingToLandFor(RandomSource random, int bound) {
        int rolled = random.nextInt(bound);
        // Anything but zero, and the roll is still drawn so the sequence is untouched for everyone else.
        // Through the accessor rather than a shadow: `dragon` is declared on the superclass, and a
        // shadow of it fails at apply time — see [DragonPhaseAccessor].
        boolean hasAFight = ((DragonPhaseAccessor) this).agesandtheart$dragon().getDragonFight() != null;
        return hasAFight ? rolled : rolled + 1;
    }
}
