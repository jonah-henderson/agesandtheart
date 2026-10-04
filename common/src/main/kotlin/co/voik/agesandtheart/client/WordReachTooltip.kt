package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.location
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/**
 * Where a word applies, listed by aiming page, as the desk's word list shows it on hover with the grammar
 * guide in the room — laid out as a smithing template's tooltip is.
 */
object WordReachTooltip {

    /** "Applies to:" and the aiming pages [word] answers to; nothing for an aiming page, or a word the server never described. */
    fun linesFor(word: Identifier): List<Component> {
        val reach = KnownWords.reachOf(word) ?: return emptyList()
        val isAnAimingPage = reach.singleOrNull()?.page?.location() == word
        if (isAnAimingPage) return emptyList()
        val header = Component.translatable(APPLIES_TO).withStyle(ChatFormatting.GRAY)
        val listed = appliesToLines(reach).map { CommonComponents.space().append(it).withStyle(ChatFormatting.BLUE) }
        return listOf(header) + listed
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

    private const val APPLIES_TO = "item.agesandtheart.page.applies_to"
    private const val APPLIES_ANYWHERE = "item.agesandtheart.page.applies_anywhere"
    private const val LIST_SEPARATOR = ", "

    /** The longest "Applies to" line, in characters. */
    private const val LINE_LENGTH = 26
}
