package co.voik.agesandtheart.client

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.location
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/**
 * Where a word applies, listed by aiming page, and for a material the shapes it can be made into — as the
 * desk's word list shows it on hover with the grammar guide in the room, laid out as a smithing template's
 * tooltip is.
 */
object WordReachTooltip {

    /**
     * "Applies to:" and the aiming pages [word] answers to, then "Modifies:" and the known shapes a material
     * can be made into; nothing for an aiming page, or a word the server never described.
     */
    fun linesFor(word: Identifier): List<Component> {
        val reach = KnownWords.reachOf(word) ?: return emptyList()
        val isAnAimingPage = word.namespace == Constants.MOD_ID && Aspect.byPage(word.path) != null
        if (isAnAimingPage) return emptyList()
        val pages = reach.map { it.page }.distinct()
        val appliesTo = when {
            pages.isEmpty() -> listOf(Component.translatable(APPLIES_ANYWHERE).string)
            // A mood or a colour reaches nearly every part there is, and a list of nineteen says less than a count.
            pages.size > MOST_LISTED -> listOf(Component.translatable(APPLIES_WIDELY, pages.size).string)
            else -> wrapped(pages.sorted().map { WordNames.readable(it.location()).string })
        }
        val shapes = KnownWords.shapesMadeOf(word).map { WordNames.readable(it).string }.sorted()
        val modifies = if (shapes.isEmpty()) emptyList() else section(MODIFIES, wrapped(shapes))
        return section(APPLIES_TO, appliesTo) + modifies
    }

    private fun section(heading: String, lines: List<String>): List<Component> =
        listOf(Component.translatable(heading).withStyle(ChatFormatting.GRAY)) +
            lines.map { CommonComponents.space().append(it).withStyle(ChatFormatting.BLUE) }

    /** [names] a few to a line, so a word reaching eight parts stays narrow. */
    private fun wrapped(names: List<String>): List<String> {
        val lines = mutableListOf<String>()
        for (name in names) {
            val last = lines.lastOrNull()
            val fitsOnTheLastLine = last != null && last.length + LIST_SEPARATOR.length + name.length <= LINE_LENGTH
            if (fitsOnTheLastLine) lines[lines.lastIndex] = last + LIST_SEPARATOR + name else lines += name
        }
        return lines
    }

    private const val APPLIES_TO = "item.agesandtheart.page.applies_to"
    private const val APPLIES_ANYWHERE = "item.agesandtheart.page.applies_anywhere"
    private const val APPLIES_WIDELY = "item.agesandtheart.page.applies_widely"

    /** The most parts listed by name before they are counted instead. */
    private const val MOST_LISTED = 8
    private const val MODIFIES = "item.agesandtheart.page.modifies"
    private const val LIST_SEPARATOR = ", "

    /** The longest "Applies to" line, in characters. */
    private const val LINE_LENGTH = 26
}
