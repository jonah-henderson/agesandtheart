package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.Landing
import co.voik.agesandtheart.age.resolution.Members
import co.voik.agesandtheart.age.resolution.Place

/**
 * A book read: what became of each page, everything that could have landed, and what the draw let land
 * (`the-world-model.md` §3).
 */
data class Reading(
    val pages: List<PageRead>,
    /** What landed, once every page's chances were drawn — what resolution builds the Age from. */
    val landings: List<Landing>,
    /** Everything that could have landed, before the draw — what incoherence is charged from. */
    val possible: List<Landing>,
    /** The world the book starts from: the first template a page that landed named. */
    val template: String?,
) {
    /** Pages with nowhere to land, which the Art laid on the Age instead — each charged as rehomed. */
    val moved: List<PageRead> get() = pages.filter { it.fate == Fate.Moved }

    /** Structure pages with nothing to bind, and words with nowhere at all to land — each charged as impossible. */
    val impossible: List<PageRead> get() = pages.filter { it.fate == Fate.Impossible }

    /** Pages that are no word at all. */
    val unread: List<PageRead> get() = pages.filter { it.fate == Fate.Unread }

    /** Whether every page is a word or structure that found where it was laid, so nothing had to be put right. */
    val readsAsLaid: Boolean get() = moved.isEmpty() && impossible.isEmpty() && unread.isEmpty()

    /** What the book was read as, page by page — every hop a page made, and what it said where it landed. */
    val readout: String get() = pages.joinToString("\n") { page -> "${page.written}: ${saidOf(page)}" }

    private fun saidOf(page: PageRead): String {
        val landed = landings.filter { it.page == page.index }
        fun what(place: Place) = landed.filter { it.place == place }.flatMap { it.effects }.joinToString(", ") { describe(it) }
        val where = landed.map { it.place }.distinct().joinToString("; ") { place -> "${placeName(place)} (${what(place)})" }
        val placed = if (where.isEmpty()) "nothing landed" else where
        return when (val fate = page.fate) {
            Fate.Unread -> "not a word"
            Fate.Impossible -> "nothing to bind, so it does nothing"
            is Fate.Structural -> fate.structure.production
            is Fate.Took -> "takes what was laid before it at ${placeName(fate.place)}; $placed"
            is Fate.TakenBy -> "taken by ${pages[fate.taker].written}: $placed"
            Fate.LaidBare -> "laid bare on the Age: $placed"
            Fate.Moved -> "nothing would take it, so it was laid on the Age: $placed"
            Fate.AssertedAlone -> "nothing took it, and it says what it says alone: $placed"
            Fate.Banned -> "banned: the Age places none of it"
            Fate.Sited -> "the biome the last word was sited in"
        }
    }

    private fun placeName(place: Place): String = when (val members = place.members) {
        Members.All -> place.aspect.page
        is Members.Named ->
            if (members.keys.size == 1) members.keys.single() else "${members.keys.size} of the ${place.aspect.page}"
        is Members.Body -> "${place.aspect.page} ${members.index + 1}"
    }

    private fun describe(effect: Effect): String = when (effect) {
        is Effect.Sets -> "${effect.parameter.name} ${effect.value}"
        is Effect.Bends -> "${effect.parameter.name} toward ${effect.value}"
        is Effect.Chooses -> effect.member
        is Effect.Admits -> "admits ${effect.members.joinToString()}"
        is Effect.Excludes -> "strikes ${effect.members.joinToString()}"
        is Effect.Restricts -> "keeps ${effect.bars.keys.joinToString()}"
        is Effect.Leans -> "leans toward ${effect.weights.keys.joinToString()}"
        is Effect.LeansEverywhere -> "leans toward ${effect.weights.keys.joinToString()}"
        is Effect.Mints -> "mints ${effect.pattern}"
        is Effect.Template -> "starts from ${effect.name}"
        is Effect.Abundance -> "${effect.rung} times as many"
        is Effect.MadeOf -> "made of ${effect.substance}"
        is Effect.Bans -> "never ${effect.block}"
    }
}

/** One page of a book, as written, and what became of it. */
data class PageRead(val index: Int, val written: String, val fate: Fate)

/** What became of a page. */
sealed interface Fate {
    /** No word or structure is called that. */
    data object Unread : Fate

    /** A structure page that bound what it should. */
    data class Structural(val structure: Structure) : Fate

    /** A structure page with nothing to bind, or a word with nowhere at all to land. */
    data object Impossible : Fate

    /** A word that took the pages waiting before it, landing them at [place]. */
    data class Took(val place: Place) : Fate

    /** A page the word at [taker] took. */
    data class TakenBy(val taker: Int) : Fate

    /** A page the Age took, laid bare. */
    data object LaidBare : Fate

    /** A page nothing would take, which the Art laid on the Age instead. */
    data object Moved : Fate

    /** A page nothing took, which said what it asserts alone. */
    data object AssertedAlone : Fate

    /** A block `no` led, which the Age never places. */
    data object Banned : Fate

    /** The biome an `in` named. */
    data object Sited : Fate
}
