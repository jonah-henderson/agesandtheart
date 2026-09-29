package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.book.FoundBookDraft
import co.voik.agesandtheart.book.FoundBookKind

/**
 * What a found book must be, as facts a check can hold it to — read from a [FoundBookDraft] offline, or
 * from `/age books json` on a server, so that both halves of the check judge by one rule set.
 */
data class JudgedBook(
    val kind: FoundBookKind,
    val seed: Long,
    val pages: List<String>,
    val unplaced: List<String>,
    val supplied: List<String>,
    val modifiers: List<String>,
    val hasAnAge: Boolean,
    val cost: Int?,
    val instability: Int?,
    val flaws: List<String>,
    val failure: String?,
) {
    override fun toString(): String = "the ${kind.key} book at seed $seed wrote '${pages.joinToString(" ")}'"

    companion object {
        fun of(draft: FoundBookDraft) = JudgedBook(
            kind = draft.kind,
            seed = draft.seed,
            pages = draft.pages,
            unplaced = draft.unplaced,
            supplied = draft.supplied,
            modifiers = draft.modifiers,
            hasAnAge = draft.hasAnAge,
            cost = draft.cost,
            instability = draft.instability?.index,
            flaws = draft.instability?.flaws.orEmpty().map { it.toString() },
            failure = draft.failure,
        )
    }
}

/**
 * Everything wrong with [book] as a found book, in the words its grammar's author needs.
 *
 * A found book is a worked example (§4.2), so each of these is a lesson in the wrong thing: a page the Art
 * had to move or supply teaches nonsense, a coherent book that argues with itself teaches that arguing is
 * fine, and an advanced book with no modifier teaches nothing the basic one did not.
 */
fun problemsWith(book: JudgedBook): List<String> = buildList {
    if (book.pages.size < 2) add("$book, which says nothing")
    // The nucleus is structure rather than content, so nothing below would notice it missing — and a found
    // book without one is the one shape of book a player must never be taught to copy.
    if (!book.hasAnAge) add("$book, which has no Age to hang on")
    if (book.unplaced.isNotEmpty()) add("$book, and the Art could not place ${book.unplaced.joinToString(" ")}")
    if (book.supplied.isNotEmpty()) {
        add("$book, which would not parse — the Art had to supply ${book.supplied.joinToString(" ")}")
    }
    if (book.failure != null) add("$book, which would not resolve: ${book.failure}")
    val cost = book.cost
    if (cost != null && cost <= 0) add("$book, which cost nothing")
    val instability = book.instability
    if (instability != null) {
        val isCoherent = instability == 0
        if (book.kind.isCoherent && !isCoherent) {
            add("$book, which came out at instability $instability: ${book.flaws.joinToString("; ")}")
        }
        // An unstable book that is not is a mistake that teaches nothing about mistakes.
        if (!book.kind.isCoherent && isCoherent) add("$book, which came out coherent")
    }
    // The only place a modifier is found (§4.5), so an advanced book that uses none has taught nothing.
    if (book.kind == FoundBookKind.ADVANCED && book.modifiers.isEmpty()) add("$book, which teaches no modifier")
}
