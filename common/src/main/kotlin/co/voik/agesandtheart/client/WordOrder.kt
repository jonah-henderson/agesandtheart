package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.WordHeadings
import co.voik.agesandtheart.client.ui.ListGroup
import net.minecraft.resources.Identifier

/**
 * How a long list of words is ordered, cycled by the small button above it (Jonah, 2026-10-21): by name, by how rare
 * each page is, by how many are stocked, or under each word it applies to. All but stocked carry headings, since a
 * count reads off the row itself.
 */
enum class WordOrder(val key: String, val glyph: String) {
    ALPHABETICAL("alphabetical", "A"),
    RARITY("rarity", "✦"),
    STOCKED("stocked", "#"),
    APPLIES_TO("applies_to", "¶"),
}

/** What ordering a row of a word list needs to know of it. */
interface OrderedWord {
    val word: Identifier
    val readable: String

    /** How many are stocked, for [WordOrder.STOCKED]. */
    val stocked: Int get() = 0
}

object WordOrdering {

    /**
     * [rows] under [order]'s headings: one unheaded group for the orders that need none, the rarest first by rarity,
     * and under what applies to each word with a word listed under every one it applies to and those applying to
     * nothing at all last, under [naming] — the naming words, which take others rather than apply to them.
     *
     * [searchedFor] keeps a whole run whose heading it is found in, and otherwise the rows it is found in by name.
     */
    fun <T : OrderedWord> grouped(
        rows: List<T>,
        order: WordOrder,
        searchedFor: String = "",
        rarityOf: (Identifier) -> WordHeadings.Rarity,
        rarityName: (WordHeadings.Rarity) -> String,
        appliesTo: (Identifier) -> List<Identifier>,
        nameOf: (Identifier) -> String,
        naming: String,
    ): List<ListGroup<T>> {
        val byName = rows.sortedBy { it.readable.lowercase() }
        val groups = when (order) {
            WordOrder.ALPHABETICAL -> listOf(ListGroup(null, byName))
            WordOrder.STOCKED -> listOf(ListGroup(null, byName.sortedByDescending { it.stocked }))
            WordOrder.RARITY -> WordHeadings.Rarity.entries.map { rarity ->
                ListGroup(rarityName(rarity), byName.filter { rarityOf(it.word) == rarity })
            }.filter { it.values.isNotEmpty() }
            WordOrder.APPLIES_TO -> {
                // Named once a heading rather than once a comparison.
                val under = byName.flatMap { row -> appliesTo(row.word).map { heading -> heading to row } }
                    .groupBy({ it.first }, { it.second })
                    .map { (heading, words) -> ListGroup(nameOf(heading), words) }
                    .sortedBy { it.heading.orEmpty().lowercase() }
                val applyingToNothing = byName.filter { appliesTo(it.word).isEmpty() }
                under + listOfNotNull(ListGroup(naming, applyingToNothing).takeIf { applyingToNothing.isNotEmpty() })
            }
        }
        return searched(groups, searchedFor.trim().lowercase())
    }

    private fun <T : OrderedWord> searched(groups: List<ListGroup<T>>, needle: String): List<ListGroup<T>> {
        if (needle.isEmpty()) return groups
        fun isFoundIn(row: T) = needle in row.readable.lowercase() || needle in row.word.toString()
        return groups.mapNotNull { group ->
            val headingIsFound = group.heading?.lowercase()?.contains(needle) == true
            if (headingIsFound) group else group.copy(values = group.values.filter(::isFoundIn)).takeIf { it.values.isNotEmpty() }
        }
    }
}
