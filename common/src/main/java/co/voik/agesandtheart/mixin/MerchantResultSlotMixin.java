package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.age.word.StockedItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Writes a page or a notebook bought <i>unwritten</i>, at the instant it is bought.
 *
 * <p><b>Why the item is unwritten at all.</b> A villager's offer is built once and bought many times —
 * {@code MerchantOffer} holds a finished {@code ItemStack} and {@code assemble()} hands out copies of it.
 * So a trade's {@code given_item_modifiers}, which is where a word would otherwise be rolled, run exactly
 * once: a writer would sell the same word for ever. The offer carries the *stock* instead, and the draw
 * happens here.
 *
 * <p><b>Why this seam and not a trade event.</b> On the ordinary click, {@code onTake} is the moment a
 * purchase exists as a single stack that has not yet merged with anything in the inventory — which matters,
 * because two unwritten pages of the same stock are identical and would stack, and then one roll would
 * write both. Resolving on an inventory tick has that problem for anyone shift-clicking a trade;
 * {@code Villager.notifyTrade} is server-side and well-timed but is handed the offer rather than the stack
 * the player received, which is the thing that has to be written.
 *
 * <p><b>It does not cover shift-clicking, and {@link MerchantQuickMoveMixin} is why.</b> This class once
 * claimed {@code onTake} was <i>the</i> moment a purchase is a single unmerged stack; on the quick-move
 * path it is not, because vanilla moves the goods first and calls {@code onTake} with what is left. The
 * pair covers both, and this one declines harmlessly when the other has already written the stack.
 *
 * <p><b>The loader alternatives, checked.</b> NeoForge has {@code TradeWithVillagerEvent} and would do;
 * Fabric API has no trade event at all since {@code TradeOfferHelper} left with the datapack trades in
 * 26.1. One vanilla seam that is identical on both sides beats one event and one mixin.
 *
 * <p>Every trade in the game passes through here and almost all of them decline in one component lookup.
 */
@Mixin(MerchantResultSlot.class)
public abstract class MerchantResultSlotMixin {

    @Inject(method = "onTake", at = @At("HEAD"))
    private void agesandtheart$writeWhatWasBought(Player player, ItemStack stack, CallbackInfo info) {
        if (!(player.level() instanceof ServerLevel level)) return;
        if (!StockedItems.isUnwritten(stack)) return;
        StockedItems.write(stack, level, player.getRandom());
    }
}
