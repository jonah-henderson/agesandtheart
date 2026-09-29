package co.voik.agesandtheart.book

import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.age.word.grammar.Production
import kotlin.random.Random

/**
 * A book a found-book grammar wrote, and what the Art made of it — the facts a check judges it by, drawn
 * the same way offline and on a server so that both judge the same thing.
 *
 * [cost] and [instability] are null where the book never resolved: because it has no `age` page, or
 * because resolving it threw, which [failure] then says.
 */
data class FoundBookDraft(
    val kind: FoundBookKind,
    val seed: Long,
    val pages: List<String>,
    /** Pages the reading could not place — unreadable, or with nowhere to go. */
    val unplaced: List<String>,
    /** Pages repair had to supply, which a book that parses as written never needs. */
    val supplied: List<String>,
    /** The grammatical pages it uses other than `age`. */
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
            fun productionOf(page: String) = vocabulary.grammarWord(page)?.production
            val modifiers = pages.filter { page -> productionOf(page).let { it != null && it != Production.NUCLEUS } }
            val hasAnAge = pages.any { page -> productionOf(page) == Production.NUCLEUS }
            val read = Grammar.read(vocabulary, pages)
            val resolved = read?.let { runCatching { Resolver.resolve(vocabulary, it, seed) } }
            return FoundBookDraft(
                kind = kind,
                seed = seed,
                pages = pages,
                unplaced = read?.dropped.orEmpty(),
                supplied = read?.constraints.orEmpty().filter { it.latent }.map { it.word.name },
                modifiers = modifiers,
                hasAnAge = hasAnAge,
                cost = resolved?.getOrNull()?.cost,
                instability = resolved?.getOrNull()?.instability,
                failure = resolved?.exceptionOrNull()?.toString(),
            )
        }
    }
}
