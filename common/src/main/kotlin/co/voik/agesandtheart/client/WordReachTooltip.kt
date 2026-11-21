package co.voik.agesandtheart.client

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.client.ui.ColumnedTooltip
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/**
 * What a word applies to, as the desk's word list shows it on hover with the grammar guide in the room: the first few,
 * and with Shift held every one, sorted by the part of the world each belongs to (Jonah). A material's shapes are
 * among them, since a shape takes its material as anything takes what it applies to.
 */
object WordReachTooltip {

    /**
     * "Applies to:" and the first [FIRST_SHOWN] of what [word] applies to, with how many more and a hint to hold Shift;
     * every one, under its part, where [expanded] — laid out in columns to fit the screen ([ColumnedTooltip]). Nothing
     * for an aiming page, or a word the server never described.
     */
    fun tooltipFor(word: Identifier, expanded: Boolean): ColumnedTooltip? {
        val reach = KnownWords.reachOf(word) ?: return null
        val isAnAimingPage = word.namespace == Constants.MOD_ID && Aspect.byPage(word.path) != null
        if (isAnAimingPage) return null
        val title = listOf(Component.translatable(APPLIES_TO).withStyle(ChatFormatting.GRAY))
        val parts = partsOf(word, reach)
        val entries = parts.flatMap { it.entries }
        fun unheaded(shown: List<String>) = listOf(ColumnedTooltip.Section(null, shown))
        return when {
            entries.isEmpty() -> ColumnedTooltip(title, unheaded(listOf(Component.translatable(APPLIES_ANYWHERE).string)))
            entries.size <= FIRST_SHOWN -> ColumnedTooltip(title, unheaded(entries))
            expanded -> ColumnedTooltip(title, parts.map { ColumnedTooltip.Section(it.name?.let(Component::literal), it.entries) })
            else -> ColumnedTooltip(
                title,
                unheaded(entries.take(FIRST_SHOWN)),
                footer = listOf(
                    Component.translatable(APPLIES_MORE, entries.size - FIRST_SHOWN).withStyle(ChatFormatting.GRAY),
                    Component.translatable(EXPAND_HINT).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC),
                ),
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
            Part(nameOf(page), ownPage + whole + clustered(words.filter { it != page }))
        }
        val unplaced = named[null].orEmpty().map(::nameOf).sorted()
        return placed + listOfNotNull(Part(null, unplaced).takeIf { unplaced.isNotEmpty() })
    }

    /**
     * [words] by name, but the game's own members of one group counted under it where two or more share it — "Ores (24)"
     * in place of every ore, and the group's own word with them.
     */
    private fun clustered(words: List<Identifier>): List<String> {
        val byCluster = words.groupBy(KnownWords::clusterOf)
        val counted = byCluster.filterKeys { it != null }.filterValues { it.size >= SMALLEST_CLUSTER }
        val named = words.filter { it !in counted.keys && KnownWords.clusterOf(it) !in counted.keys }
        val counts = counted.map { (group, members) -> Component.translatable(CLUSTER, nameOf(requireNotNull(group)), members.size).string }
        return (named.map(::nameOf) + counts).sortedBy { it.lowercase() }
    }

    /** "every creature (98)", or as near as the part has words for it. */
    private fun everyOf(part: Aspect): String =
        Component.translatableWithFallback("$EVERY.${part.key}", "all %s", KnownWords.ofTheGameIn(part)).string

    private fun nameOf(word: Identifier): String = WordNames.readable(word).string

    private const val APPLIES_TO = "item.agesandtheart.page.applies_to"
    private const val APPLIES_ANYWHERE = "item.agesandtheart.page.applies_anywhere"
    private const val APPLIES_MORE = "item.agesandtheart.page.applies_more"
    private const val EXPAND_HINT = "item.agesandtheart.page.applies_expand"
    private const val EVERY = "item.agesandtheart.page.applies_every"

    private const val CLUSTER = "item.agesandtheart.page.applies_cluster"

    /** The fewest of a group's members counted under it rather than named. */
    private const val SMALLEST_CLUSTER = 2

    /** How many are listed before Shift is held. */
    private const val FIRST_SHOWN = 5
}
