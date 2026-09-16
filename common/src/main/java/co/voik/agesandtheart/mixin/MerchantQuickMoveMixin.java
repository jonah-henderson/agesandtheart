package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.page.StockedItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Writes a shift-clicked page or notebook, which {@link MerchantResultSlotMixin} cannot reach.
 *
 * <p><b>{@code onTake} is too late on this path, and its own stack is the wrong object besides.</b>
 * {@code MerchantMenu.quickMoveStack} calls {@code moveItemStackTo(stack, 3, 39, true)} <i>first</i> —
 * which delivers the goods to the inventory by {@code split} or by merging into a matching stack, both of
 * which produce <i>different</i> {@code ItemStack} objects — and only then calls
 * {@code slot.onTake(player, stack)}, by which point that stack is the shrunken remainder. So the other
 * mixin sees a stack with no components left to recognise, declines, and the page that actually reached
 * the player keeps its stock and never gets a word.
 *
 * <p>Shift-clicking is how most players take a trade, so this was the usual outcome rather than the corner.
 *
 * <p><b>Written before the move, not after.</b> At the head of {@code quickMoveStack} the result slot still
 * holds the single stack the trade produced, and it is the very object {@code moveItemStackTo} is about to
 * split — so writing it here means every copy delivered carries the word. {@link MerchantResultSlotMixin}
 * then declines when it runs, the stack no longer being unwritten, and each purchase is still written
 * exactly once.
 *
 * <p><b>Rolling when the result is assembled instead would have been simpler and is wrong.</b> The word
 * would be visible before buying, and a player could take the payment in and out of the trade slots until
 * a word they wanted came up — which is a reroll, and it empties the draw of meaning.
 */
@Mixin(MerchantMenu.class)
public abstract class MerchantQuickMoveMixin {

    /** {@code MerchantMenu.RESULT_SLOT}, which is protected and so cannot be read from here. */
    private static final int AGESANDTHEART$RESULT_SLOT = 2;

    @Inject(method = "quickMoveStack", at = @At("HEAD"))
    private void agesandtheart$writeBeforeItMerges(
            Player player, int slotIndex, CallbackInfoReturnable<ItemStack> callback) {
        if (slotIndex != AGESANDTHEART$RESULT_SLOT) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        ItemStack bought = ((AbstractContainerMenu) (Object) this).getSlot(AGESANDTHEART$RESULT_SLOT).getItem();
        if (!StockedItems.isUnwritten(bought)) return;
        StockedItems.write(bought, level, player.getRandom());
    }
}
