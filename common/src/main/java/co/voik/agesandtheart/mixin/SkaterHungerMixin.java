package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.content.RimeSkates;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Charges a skater a walker's hunger, at any speed ({@link RimeSkates#hungerForAStride}).
 *
 * <p><b>Neither loader has a hook for food exhaustion</b>: NeoForge's food events cover eating, and Fabric
 * API has nothing here. Movement is charged in one method and jumping in another, and both hand
 * {@code causeFoodExhaustion} a number worked out inline, so the number is what is modified.
 *
 * <p><b>The sprint charge is found by the stat beside it, not by position.</b> {@code checkMovementStatistics}
 * calls {@code causeFoodExhaustion} six times, once per way of moving, and an ordinal would land on whichever
 * charge moved into its place. The slice is the stretch between awarding sprint distance and awarding crouch
 * distance, which holds the sprint's charge alone; the stat itself is left as it was, since the player did
 * sprint that far.
 */
@Mixin(ServerPlayer.class)
public abstract class SkaterHungerMixin {

    @ModifyArg(
        method = "checkMovementStatistics",
        slice = @Slice(
            from = @At(value = "FIELD", target = "Lnet/minecraft/stats/Stats;SPRINT_ONE_CM:Lnet/minecraft/resources/Identifier;"),
            to = @At(value = "FIELD", target = "Lnet/minecraft/stats/Stats;CROUCH_ONE_CM:Lnet/minecraft/resources/Identifier;")
        ),
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;causeFoodExhaustion(F)V")
    )
    private float agesandtheart$skatingIsNotSprinting(float exhaustion) {
        return RimeSkates.hungerForAStride((ServerPlayer) (Object) this, exhaustion);
    }

    @ModifyArg(
        method = "jumpFromGround",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;causeFoodExhaustion(F)V")
    )
    private float agesandtheart$aSkatersJump(float exhaustion) {
        return RimeSkates.hungerForAJump((ServerPlayer) (Object) this, exhaustion);
    }
}
