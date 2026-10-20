package co.voik.agesandtheart.book

import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.language.Structure
import co.voik.agesandtheart.age.word.Vocabulary
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
    /** Pages with nowhere to land, which the Art laid on the Age — never needed by a book that reads as written. */
    val supplied: List<String>,
    /** The structure pages it uses other than `age`, and its quantifiers. */
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
            fun structureOf(page: String) = vocabulary.structurePage(page)?.structure
            fun isAModifier(page: String): Boolean {
                val isStructure = structureOf(page).let { it != null && it != Structure.AGE }
                return isStructure || vocabulary.word(page)?.isAQuantifier == true
            }
            val modifiers = pages.filter(::isAModifier)
            val hasAnAge = pages.any { page -> structureOf(page) == Structure.AGE }
            val read = vocabulary.read(pages, seed)
            val resolved = read?.let { runCatching { vocabulary.resolve(it, seed) } }
            return FoundBookDraft(
                kind = kind,
                seed = seed,
                pages = pages,
                unplaced = (read?.unread.orEmpty() + read?.impossible.orEmpty()).map { it.written },
                supplied = read?.moved.orEmpty().map { it.written },
                modifiers = modifiers,
                hasAnAge = hasAnAge,
                cost = resolved?.getOrNull()?.cost,
                instability = resolved?.getOrNull()?.instability,
                failure = resolved?.exceptionOrNull()?.toString(),
            )
        }
    }
}
