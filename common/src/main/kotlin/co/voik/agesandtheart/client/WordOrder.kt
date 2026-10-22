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
     * and under what applies to each word with a word listed under every one it applies to. Under what applies, the
     * words [names] calls members — `zombie` under `inhabitants` — are folded beneath the rest under what [names] calls
     * them, and the words [isAMaterial] answers for under [Names.materials]. The words applying to nothing come last:
     * the parts of the world, the base worlds, and the grammar.
     *
     * [searchedFor] keeps a whole run whose heading it is found in, and otherwise the rows it is found in by name.
     */
    fun <T : OrderedWord> grouped(
        rows: List<T>,
        order: WordOrder,
        searchedFor: String = "",
        rarityOf: (Identifier) -> WordHeadings.Rarity,
        appliesTo: (Identifier) -> List<Identifier>,
        isAMaterial: (Identifier) -> Boolean = { false },
        names: Names,
    ): List<ListGroup<T>> {
        val byName = rows.sortedBy { it.readable.lowercase() }
        val groups = when (order) {
            WordOrder.ALPHABETICAL -> listOf(ListGroup(null, byName))
            WordOrder.STOCKED -> listOf(ListGroup(null, byName.sortedByDescending { it.stocked }))
            WordOrder.RARITY -> WordHeadings.Rarity.entries.map { rarity ->
                ListGroup(names.rarity(rarity), byName.filter { rarityOf(it.word) == rarity })
            }.filter { it.values.isNotEmpty() }
            WordOrder.APPLIES_TO -> underWhatEachAppliesTo(byName, appliesTo, isAMaterial, names)
        }
        return searched(groups, searchedFor.trim().lowercase())
    }

    /** What a word list's headings and folds are called. */
    class Names(
        val rarity: (WordHeadings.Rarity) -> String,
        val of: (Identifier) -> String,
        val parts: String,
        val baseWorlds: String,
        val grammar: String,
        val materials: String = "materials",
        /** Whether a word names members of a part of the world. */
        val namesMembers: (Identifier) -> Boolean = { false },
        /** Whether a word names a whole part of the world. */
        val namesAPart: (Identifier) -> Boolean = { false },
        /** Whether a word is one of the grammar's own — `and`, `in`, `no`. */
        val isGrammar: (Identifier) -> Boolean = { false },
        /** What the members of the part a heading names are called — creatures, under `inhabitants`. */
        val membersOf: (Identifier) -> String = { "kinds" },
    )

    private fun <T : OrderedWord> underWhatEachAppliesTo(
        byName: List<T>,
        appliesTo: (Identifier) -> List<Identifier>,
        isAMaterial: (Identifier) -> Boolean,
        names: Names,
    ): List<ListGroup<T>> {
        fun fold(heading: String, values: List<T>) = ListGroup(heading, values).takeIf { values.isNotEmpty() }
        val under = byName.flatMap { row -> appliesTo(row.word).map { heading -> heading to row } }
            .groupBy({ it.first }, { it.second })
            // Named once a heading rather than once a comparison.
            .map { (heading, words) -> Triple(heading, names.of(heading), words) }
            .sortedBy { (_, name, _) -> name.lowercase() }
            .map { (heading, name, words) ->
                val (members, notMembers) = words.partition { names.namesMembers(it.word) }
                val (madeOf, rest) = notMembers.partition { isAMaterial(it.word) }
                ListGroup(name, rest, listOfNotNull(fold(names.membersOf(heading), members), fold(names.materials, madeOf)))
            }
        val applyingToNothing = byName.filter { appliesTo(it.word).isEmpty() }
        val (grammar, words) = applyingToNothing.partition { names.isGrammar(it.word) }
        val (parts, baseWorlds) = words.partition { names.namesAPart(it.word) }
        return under + listOfNotNull(fold(names.parts, parts), fold(names.baseWorlds, baseWorlds), fold(names.grammar, grammar))
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
