package co.voik.agesandtheart.book

import co.voik.agesandtheart.page.PageLearning
import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.age.word.grammar.Readout
import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.book.panel.PanelWarming
import co.voik.agesandtheart.client.BookScreenOpener
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.ChatFormatting
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import java.util.function.Consumer
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/** A Mystcraft-style Descriptive Book, bound to the Age named by its [AgeComponents.AGE_ID] component. */
class DescriptiveBookItem(properties: Properties) : Item(properties) {

    /**
     * A book is called by the Age it describes.
     *
     * Overridden rather than set as a component when the book is bound, so renaming an Age later renames
     * every book of it — and so an anvil rename still wins, since a custom name takes priority over this.
     */
    override fun getName(itemStack: ItemStack): Component {
        val title = itemStack.get(AgeComponents.BOOK_TITLE) ?: return super.getName(itemStack)
        return Component.translatable("item.agesandtheart.descriptive_book.named", title)
    }

    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        builder: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        // What it says, run together, so a shelf of books is readable without opening any of them. The row
        // of pages is the fallback for a book bound before the Art read one.
        val reading = stack.get(AgeComponents.BOOK_READING)?.let(Readout::asProse)
        val said = reading ?: pagesOf(stack) ?: return
        builder.accept(said.copy().withStyle(ChatFormatting.DARK_GRAY))
    }

    private fun pagesOf(stack: ItemStack): Component? {
        val words = stack.get(AgeComponents.BOOK_WORDS).orEmpty()
        if (words.isEmpty()) return null
        return Component.literal(words.joinToString(" ") { WordNames.readable(it).string })
    }

    /**
     * **A book with no words in it is not something the game makes.** The desk always writes one, and no
     * recipe produces a blank — so one turning up in an inventory came from `/give` or the creative menu,
     * and the Art writes it here rather than leaving a book that opens on nothing.
     *
     * On the tick rather than on use, because opening it is a client act: the screen would already be up,
     * reading the stack as it was, by the time the server had written anything into it.
     */
    override fun inventoryTick(stack: ItemStack, level: ServerLevel, holder: Entity, slot: EquipmentSlot?) {
        if (writeIfBlank(stack, level)) return
        PanelWarming.consider(level.server, stack, inHand = slot != null)
    }

    /**
     * Opens the book rather than linking outright.
     *
     * Linking is now a click on the panel inside (see [co.voik.agesandtheart.book.Linking]) — it spends
     * the book and can strand you, so it should not be one misclick away from a hotbar slot.
     */
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        // Guarded so the screen class is never loaded on a dedicated server.
        if (level.isClientSide) BookScreenOpener.open(stack, hand)
        // Reading it is how the grammar is learned (§4.5): `and`, `only`, `except` and the rungs are pages
        // nobody is handed, so a book somebody wrote well is where a writer meets them. Server-side, since
        // the learned set is the player's own save data.
        if (player is ServerPlayer) PageLearning.study(player, stack)
        return InteractionResult.SUCCESS
    }

    companion object {
        /**
         * Writes [stack] if nothing has, answering whether it did — on the book's inventory tick, and as it is
         * opened on a lectern, which runs none (design §7.8.2).
         */
        @JvmStatic
        fun writeIfBlank(stack: ItemStack, level: ServerLevel): Boolean {
            if (stack.has(AgeComponents.BOOK_WORDS)) return false
            FoundBook.write(stack, level.server, level.random.nextLong())
            // Writing itself is a found book's binding — nothing else ever binds one — so this is the
            // moment its Age is decided, and the earliest it can be made ready.
            PanelWarming.whenBound(level.server, stack)
            return true
        }
    }
}
