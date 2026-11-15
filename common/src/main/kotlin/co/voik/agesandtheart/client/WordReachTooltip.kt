package co.voik.agesandtheart.client

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.WordNames
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier

/**
 * What a word applies to, as the desk's word list shows it on hover with the grammar guide in the room: the first few,
 * and with Shift held every one, sorted by the part of the world each belongs to (Jonah). A material's shapes are
 * among them, since a shape takes its material as anything takes what it applies to.
 */
object WordReachTooltip {

    /**
     * "Applies to:" and the first [FIRST_SHOWN] of what [word] applies to, with how many more and a hint to hold Shift;
     * every one, a line a part, where [expanded]. Nothing for an aiming page, or a word the server never described.
     */
    fun linesFor(word: Identifier, expanded: Boolean): List<Component> {
        val reach = KnownWords.reachOf(word) ?: return emptyList()
        val isAnAimingPage = word.namespace == Constants.MOD_ID && Aspect.byPage(word.path) != null
        if (isAnAimingPage) return emptyList()
        val heading = Component.translatable(APPLIES_TO).withStyle(ChatFormatting.GRAY)
        val parts = partsOf(word, reach)
        val entries = parts.flatMap { it.entries }
        return when {
            entries.isEmpty() -> listOf(heading, entry(Component.translatable(APPLIES_ANYWHERE).string))
            entries.size <= FIRST_SHOWN -> listOf(heading) + entries.map(::entry)
            expanded -> listOf(heading) + parts.flatMap(::linesOf)
            else -> listOf(heading) + entries.take(FIRST_SHOWN).map(::entry) + listOf(
                Component.translatable(APPLIES_MORE, entries.size - FIRST_SHOWN).withStyle(ChatFormatting.GRAY),
                Component.translatable(EXPAND_HINT).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC),
            )
        }
    }

    /** What [word] applies to within one part of the world, [name]d by that part's page, or unnamed where unknown. */
    private data class Part(val name: String?, val entries: List<String>)

    /**
     * What [word] applies to, a [Part] for each part of the world in the order the world is built: the part's own page
     * first, then every one of the game's there where it reaches them all, then the rest by name. Where the server
     * sent only the parts [reach]ed, each part's page.
     */
    private fun partsOf(word: Identifier, reach: Set<Aspect>): List<Part> {
        val named = KnownWords.appliesTo(word).groupBy(KnownWords::partOf)
        val wholes = KnownWords.wholesOf(word)
        if (named.isEmpty() && wholes.isEmpty()) {
            return reach.sortedBy { it.ordinal }.map { part -> nameOf(KnownWords.pageOf(part)).let { Part(it, listOf(it)) } }
        }
        val inOrder = (named.keys.filterNotNull() + wholes).distinct().sortedBy { it.ordinal }
        val placed = inOrder.map { part ->
            val page = KnownWords.pageOf(part)
            val words = named[part].orEmpty()
            val ownPage = listOfNotNull(page.takeIf { it in words }?.let(::nameOf))
            val whole = listOfNotNull(part.takeIf { it in wholes }?.let(::everyOf))
            val rest = words.filter { it != page }.map(::nameOf).sorted()
            Part(nameOf(page), ownPage + whole + rest)
        }
        val unplaced = named[null].orEmpty().map(::nameOf).sorted()
        return placed + listOfNotNull(Part(null, unplaced).takeIf { unplaced.isNotEmpty() })
    }

    /** "every creature (98)", or as near as the part has words for it. */
    private fun everyOf(part: Aspect): String =
        Component.translatableWithFallback("$EVERY.${part.key}", "all %s", KnownWords.ofTheGameIn(part)).string

    /** [part] as lines: its name, then what it holds, wrapped a few to a line beneath it. */
    private fun linesOf(part: Part): List<Component> {
        val label = part.name?.let { listOf(Component.literal(it).withStyle(ChatFormatting.GRAY)) }.orEmpty()
        return label + wrapped(part.entries).map(::entry)
    }

    private fun entry(text: String): MutableComponent =
        CommonComponents.space().append(text).withStyle(ChatFormatting.BLUE)

    private fun nameOf(word: Identifier): String = WordNames.readable(word).string

    /** [names] a few to a line, so a part with forty in it stays a block rather than a column. */
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
    private const val APPLIES_MORE = "item.agesandtheart.page.applies_more"
    private const val EXPAND_HINT = "item.agesandtheart.page.applies_expand"
    private const val EVERY = "item.agesandtheart.page.applies_every"

    /** How many are listed before Shift is held. */
    private const val FIRST_SHOWN = 5
    private const val LIST_SEPARATOR = ", "

    /** The longest line of a part's words, in characters. */
    private const val LINE_LENGTH = 44
}
