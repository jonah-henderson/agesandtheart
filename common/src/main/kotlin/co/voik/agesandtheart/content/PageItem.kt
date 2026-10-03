package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.Word
import net.minecraft.core.component.DataComponents
import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.client.KnownWords
import co.voik.agesandtheart.client.PageScreen
import co.voik.agesandtheart.location
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import net.minecraft.world.level.Level
import java.util.function.Consumer

/**
 * A page of the Art with one word written on it.
 *
 * The word is rolled when the page is generated, not when it is read, so a page is a *specific* find —
 * two players opening the same chest see the same word, and a page can be traded for what it says.
 * Learning happens on pickup (see `PageLearning`); reading it is flavour.
 */
class PageItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        if (stack.get(AgeComponents.PAGE_WORD) == null) return InteractionResult.PASS
        // Guarded so the screen class is never loaded on a dedicated server.
        if (level.isClientSide) PageScreen.open(stack)
        return InteractionResult.SUCCESS
    }

    /** The word, where one is written — so it is the word that takes the rarity colour. */
    override fun getName(stack: ItemStack): Component =
        stack.get(AgeComponents.PAGE_WORD)?.let(WordNames::readable) ?: super.getName(stack)

    /**
     * Laid out as a smithing template's is: what kind of thing this is, then where it applies, listed by
     * aiming page. Only ever built on the client, which is where [KnownWords] is filled.
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        builder: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        val word = stack.get(AgeComponents.PAGE_WORD) ?: return
        builder.accept(Component.translatable(SUBTITLE).withStyle(ChatFormatting.GRAY))
        val reach = KnownWords.reachOf(word) ?: return
        val isAnAimingPage = reach.singleOrNull()?.page?.location() == word
        if (isAnAimingPage) return
        builder.accept(CommonComponents.EMPTY)
        builder.accept(Component.translatable(APPLIES_TO).withStyle(ChatFormatting.GRAY))
        appliesToLines(reach).forEach { line ->
            builder.accept(CommonComponents.space().append(line).withStyle(ChatFormatting.BLUE))
        }
    }

    /** The aiming pages [reach] answers to, a few to a line so a word reaching eight parts stays narrow. */
    private fun appliesToLines(reach: Set<Aspect>): List<Component> {
        if (reach.isEmpty()) return listOf(Component.translatable(APPLIES_ANYWHERE))
        val names = reach.sortedBy { it.page }.map { WordNames.readable(it.page.location()).string }
        val lines = mutableListOf<String>()
        for (name in names) {
            val last = lines.lastOrNull()
            val fitsOnTheLastLine = last != null && last.length + LIST_SEPARATOR.length + name.length <= LINE_LENGTH
            if (fitsOnTheLastLine) lines[lines.lastIndex] = last + LIST_SEPARATOR + name else lines += name
        }
        return lines.map(Component::literal)
    }

    companion object {
        private const val SUBTITLE = "item.agesandtheart.page.subtitle"
        private const val APPLIES_TO = "item.agesandtheart.page.applies_to"
        private const val APPLIES_ANYWHERE = "item.agesandtheart.page.applies_anywhere"
        private const val LIST_SEPARATOR = ", "

        /** The longest "Applies to" line, in characters. */
        private const val LINE_LENGTH = 26

        /**
         * A page with [word] written on it — **the one way to make one.**
         *
         * A written page is a stack plus a data component, and spelling that out at each of the eight
         * places that hand one over meant anything a page must carry besides its word would have to be
         * added at all eight. The loot functions, the desk, the instruments and the toast all come through
         * here now.
         */
        fun writtenWith(word: Identifier): ItemStack =
            ItemStack(AgeContent.PAGE).also { it.set(AgeComponents.PAGE_WORD, word) }

        /** The same, with the name coloured by the rarity of [word]'s bucket — what every server path makes. */
        fun writtenWith(word: Word, vocabulary: Vocabulary): ItemStack =
            ItemStack(AgeContent.PAGE).also { write(it, word, vocabulary) }

        /**
         * [word] written on [stack] in place, as a loot function writes the page it is handed.
         *
         * **Every server path must stamp the rarity**, or two pages of one word carry different components
         * and will not stack.
         */
        fun write(stack: ItemStack, word: Word, vocabulary: Vocabulary) {
            stack.set(AgeComponents.PAGE_WORD, word.id)
            val bucket = vocabulary.rarity.bucketOf(word) ?: return
            stack.set(DataComponents.RARITY, bucket.itemRarity)
        }
    }
}
