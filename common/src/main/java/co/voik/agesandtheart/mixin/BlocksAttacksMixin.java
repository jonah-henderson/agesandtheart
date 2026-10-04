package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.content.CrushingResistance;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps an axe from knocking the shield out of the hands of someone under {@link CrushingResistance}.
 *
 * <p><b>Why a Mixin.</b> Neither loader has an event for a shield being disabled. Since 26.1 the disabling
 * is data — a weapon's {@code disable_blocking_for_seconds} — and {@link BlocksAttacks#disable} is the one
 * method every such weapon goes through, with the blocker in hand, so declining it here covers axes and
 * anything a pack gives the same component.
 */
@Mixin(BlocksAttacks.class)
public abstract class BlocksAttacksMixin {

    @Inject(method = "disable", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$holdTheShield(
        ServerLevel level, LivingEntity user, float baseSeconds, ItemStack blockingWith, CallbackInfo callback
    ) {
        if (CrushingResistance.isResisting(user)) callback.cancel();
    }
}
