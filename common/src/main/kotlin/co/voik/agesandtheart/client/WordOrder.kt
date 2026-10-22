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
     * nothing at all last, under [naming] — the naming words, which take others rather than apply to them. Under what
     * applies, the words [isAMaterial] answers for are folded beneath the rest under [materials].
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
        isAMaterial: (Identifier) -> Boolean = { false },
        materials: String = "materials",
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
                    .map { (heading, words) -> nameOf(heading) to words }
                    .sortedBy { (name, _) -> name.lowercase() }
                    .map { (name, words) ->
                        val (madeOf, rest) = words.partition { isAMaterial(it.word) }
                        ListGroup(name, rest, listOfNotNull(ListGroup(materials, madeOf).takeIf { madeOf.isNotEmpty() }))
                    }
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
            if (headingIsFound) return@mapNotNull group
            val found = group.copy(values = group.values.filter(::isFoundIn), subgroups = searched(group.subgroups, needle))
            found.takeIf { it.size > 0 }
        }
    }
}
