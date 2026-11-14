package co.voik.agesandtheart.book

import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.reading.Fate
import co.voik.agesandtheart.age.reading.StructureWord
import co.voik.agesandtheart.location
import co.voik.agesandtheart.age.word.Vocabulary
import kotlin.random.Random

/**
 * A book a found-book grammar wrote, and what the Art made of it — the facts a check judges it by, drawn
 * the same way offline and on a server so that both judge the same thing.
 *
 * [cost] and [instability] are null where resolving it threw, which [failure] then says.
 */
data class FoundBookDraft(
    val kind: FoundBookKind,
    val seed: Long,
    val pages: List<String>,
    /** Pages the reading could not place — no word, or a coherent book's page left with nowhere to land. */
    val unplaced: List<String>,
    /** The structure words it uses other than `age`, and its quantifiers. */
    val modifiers: List<String>,
    val hasAnAge: Boolean,
    val cost: Int?,
    val instability: Instability?,
    val failure: String?,
) {
    override fun toString(): String = "the ${kind.key} book at seed $seed wrote '${pages.joinToString(" ")}'"

    companion object {
        /** The [kind] of book at [seed], or null where the pack ships no grammar for it. */
        fun drawn(vocabulary: Vocabulary, kind: FoundBookKind, seed: Long): FoundBookDraft? {
            val grammar = vocabulary.generation.grammar(kind.grammar) ?: return null
            val pages = grammar.expand(Random(seed))
            fun behaviourOf(page: String) = vocabulary.word(page)?.defaultSense?.behaviour
            fun isAQuantifier(page: String): Boolean {
                val offers = vocabulary.word(page)?.senses.orEmpty().flatMap { it.offers }
                return offers.isNotEmpty() && offers.all { it.concept == AMOUNT }
            }
            fun isAModifier(page: String): Boolean {
                val behaviour = behaviourOf(page)
                return (behaviour != null && behaviour != StructureWord.Age.id) || isAQuantifier(page)
            }
            val written = runCatching { vocabulary.written(pages, seed) }
            val read = written.getOrNull()
            val unread = read?.reading?.pages.orEmpty().filter { it.fate == Fate.Unknown }.map { it.written }
            // A page of an unstable book overruled by the one it contradicts dangles, which is what an unstable book shows.
            val dangling = if (kind.isCoherent) read?.drawn?.dangling.orEmpty().map { it.written } else emptyList()
            return FoundBookDraft(
                kind = kind,
                seed = seed,
                pages = pages,
                unplaced = unread + dangling,
                modifiers = pages.filter(::isAModifier),
                hasAnAge = pages.any { behaviourOf(it) == StructureWord.Age.id },
                cost = read?.resolution?.cost,
                instability = read?.resolution?.instability,
                failure = written.exceptionOrNull()?.toString(),
            )
        }

        private val AMOUNT = "amount".location()
    }
}
