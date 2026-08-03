package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.word.PageLearning
import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.age.word.grammar.Readout
import co.voik.agesandtheart.client.BookScreenOpener
import net.minecraft.server.level.ServerPlayer
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

/**
 * A Mystcraft-style Descriptive Book. On use it authors (or re-enters) the Age it's bound to
 * and teleports the holder there.
 *
 * The bound Age is stored in the stack's [AgeContent.AGE_ID] component and assigned lazily on
 * first use from a persistent counter — so every fresh book writes a distinct new Age, while
 * the same book always links back to its own.
 */
class DescriptiveBookItem(properties: Properties) : Item(properties) {

    /**
     * A book is called by the Age it describes.
     *
     * Overridden rather than set as a component when the book is bound, so renaming an Age later renames
     * every book of it — and so an anvil rename still wins, since a custom name takes priority over this.
     */
    override fun getName(itemStack: ItemStack): Component {
        val title = itemStack.get(AgeContent.BOOK_TITLE) ?: return super.getName(itemStack)
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
        val reading = stack.get(AgeContent.BOOK_READING)?.let(Readout::asProse)
        val said = reading ?: pagesOf(stack) ?: return
        builder.accept(said.copy().withStyle(ChatFormatting.DARK_GRAY))
    }

    private fun pagesOf(stack: ItemStack): Component? {
        val words = stack.get(AgeContent.BOOK_WORDS).orEmpty()
        if (words.isEmpty()) return null
        return Component.literal(words.joinToString(" ") { WordNames.readable(it).string })
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

}
