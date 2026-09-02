package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.word.grammar.Grammar
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.resources.Identifier
import kotlin.random.Random

/**
 * What the workshop offers as the next page.
 *
 * Two questions are being asked of two different things and both have to hold. **Can** this page be laid
 * is the parser's, and it is asked by running it; **would it do anything** is the resolver's, and it has
 * to be asked separately because the grammar admits more than the world does. `belongsHere` is checked
 * once per run of modifiers rather than per term, so `pillars and arthropods landmass` parses perfectly
 * well and does nothing with `arthropods` at all.
 *
 * The filter is the dangerous half: hiding a page that would have worked is worse than showing one that
 * would not. What guards it is a book written a page at a time — every page has to be on the list at the
 * position it was laid — and the named case the filter exists for.
 *
 * **Whether the books the Art writes are any *good* is not asked here.** That is a question about quality
 * rather than correctness, it wants a pass of its own, and it should not run on every commit: holding this
 * filter against generated books cost three minutes and answered a different question badly.
 */
@Tags(NEEDS_REGISTRIES)
class SuggestionsCheck : FunSpec({

    val corpus by lazy { Corpus.load() }
    val vocabulary by lazy { corpus.vocabulary }
    val suggesting by lazy { Suggestions(vocabulary) }

    /** A book off the front of the design, laid one page at a time the way the screen lays it. */
    val book = listOf("beautiful", "floating", "age", "pillars", "and", "hills", "landmass")

    test("a book can be written a page at a time, always from the list") {
        val missed = book.indices.filterNot { at ->
            suggesting.after(book.take(at)).bearing.any { it.page == book[at] }
        }
        check(missed.isEmpty()) {
            "the list never offered " +
                missed.joinToString(", ") { "'${book[it]}' after [${book.take(it).joinToString(" ")}]" }
        }
    }

    /** The case this filter exists for, named so it cannot quietly come back. */
    test("a word carried into a clause it says nothing about is kept off the list") {
        val row = listOf("age", "pillars", "and")
        val offered = suggesting.after(row)
        check(Grammar.parses(vocabulary, row + "arthropods" + "landmass")) {
            "the grammar no longer admits this, so the filter is guarding nothing"
        }
        check(offered.bearing.none { it.page == "arthropods" }) { "'arthropods' was offered for a landmass clause" }
        check(offered.inert.any { it.page == "arthropods" }) { "'arthropods' was dropped rather than set aside" }
        check(offered.bearing.any { it.page == "hills" }) { "'hills' was filtered out of a landmass clause" }
    }

    /**
     * At a clause boundary nothing has been aimed at yet, so every ordinary word may speak where it
     * declares and there is nothing to be inert *against*. Filtering on aim there would be filtering on
     * no information.
     */
    test("no word is held back for its aim before a clause has one") {
        for (row in listOf(emptyList(), listOf("age"), book)) {
            val ordinary = suggesting.after(row).inert.filterNot { it.closes }
            check(ordinary.isEmpty()) {
                "[${row.joinToString(" ")}] held back ${ordinary.size} ordinary pages: ${ordinary.take(5).map { it.page }}"
            }
        }
    }

    /**
     * **An aiming page needs something to aim.** It names a part of the world and supplies no value, so
     * laying one straight after a finished clause spends a page and changes nothing about the Age.
     */
    test("a page that closes is only offered once a clause is open") {
        val boundary = suggesting.after(book)
        check(boundary.bearing.none { it.closes }) {
            "offered ${boundary.bearing.filter { it.closes }.take(5).map { it.page }} with no clause open"
        }
        check(boundary.inert.any { it.page == "landmass" }) { "'landmass' was dropped rather than set aside" }
        // And the nucleus is not one of them: a book that does not carry `age` is not a book at all.
        check(suggesting.after(emptyList()).bearing.any { it.page == "age" }) { "'age' was held back" }

        val open = suggesting.after(book.dropLast(1))
        check(open.bearing.any { it.page == "landmass" }) { "'landmass' was held back from an open clause" }
    }

    test("the finished book reads as laid, and the half-written ones do not") {
        check(suggesting.isASentence(book)) { "the whole book is not a sentence" }
        check(!suggesting.isASentence(book.dropLast(1))) { "a clause with no close read as a book" }
        check(!suggesting.isASentence(listOf("pillars"))) { "a row with no `age` page read as a book" }
    }

    /**
     * **Nothing offered may be a dead end.** A suggestion is a promise the row can still become a book,
     * and one leaving nothing to lay next walks somebody into a corner. Sampled rather than exhaustive:
     * laying every one of seventeen hundred pages and asking again is minutes.
     */
    test("nothing offered leaves a row with nowhere to go") {
        for (row in listOf(emptyList(), listOf("age"), listOf("age", "pillars"), book)) {
            val offers = suggesting.after(row).bearing
            check(offers.isNotEmpty()) { "nothing at all follows [${row.joinToString(" ")}]" }
            val cornered = offers.shuffled(Random(1)).take(12).filter { offer ->
                val later = row + offer.page
                !suggesting.isASentence(later) && suggesting.after(later).all.isEmpty()
            }
            check(cornered.isEmpty()) {
                "after [${row.joinToString(" ")}] these lead nowhere: ${cornered.map { it.page }}"
            }
        }
    }

    /**
     * The one line the suggester keeps a copy of, held to the original.
     *
     * `answersIn` is copied only so the candidate list can be read once instead of sorted seventeen
     * hundred times, and a copy that drifted would hide pages that work — silently, and only for some
     * aspects. So it is asked of every word and every aspect, both ways.
     */
    test("the suggester's reach is the vocabulary's own") {
        val disagreeing = co.voik.agesandtheart.age.aspect.Aspect.entries.flatMap { aspect ->
            vocabulary.words.mapNotNull { word ->
                val mine = suggesting.answersIn(word, aspect)
                val theirs = vocabulary.answersIn(word, aspect)
                if (mine == theirs) null else "${word.name} in ${aspect.page}: forge $mine, corpus $theirs"
            }
        }
        check(disagreeing.isEmpty()) {
            "${disagreeing.size} disagreements: " + disagreeing.take(8).joinToString(" | ")
        }
    }

    test("every page offered is one the corpus knows") {
        val strangers = suggesting.after(listOf("age")).all.map { it.page }
            .filter { vocabulary.word(it) == null && vocabulary.grammarWord(it) == null }
        check(strangers.isEmpty()) { "offered pages nothing can read: $strangers" }
    }

    /**
     * The aiming pages offered have to be the ones that really close, because that line is the only thing
     * on screen saying what the modifiers already laid are being said about.
     */
    test("a clause narrows to the aspects it can still be aimed at") {
        check(suggesting.closersAfter(emptyList()) == listOf("age")) {
            "before the nucleus, only `age` can close: ${suggesting.closersAfter(emptyList())}"
        }
        val afterPillars = suggesting.closersAfter(listOf("age", "pillars"))
        check(afterPillars == listOf("landmass")) { "a landform clause closed with $afterPillars" }
        check(suggesting.closersAfter(book).size > 1) { "a finished book closed nothing further" }
    }

    /** `in <biome>` is the one close that is two pages and no aiming page, so it is the one worth pinning. */
    test("a siting is offered and closes where it stands") {
        check("in" in suggesting.after(listOf("age", "grass")).all.map { it.page }) {
            "`in` was not offered after a confinable clause"
        }
        check(Grammar.parses(vocabulary, listOf("age", "purple", "grass", "in", "jungle"))) {
            "a sited clause did not read"
        }
    }

    test("a draft goes to disk and comes back the same, seeding and all") {
        for (seeding in Seeding.entries) {
            val draft = AgeDraft("workshop-check", book, seeding, 42L)
            try {
                AgeDraft.write(draft)
                check(AgeDraft.read(draft.name) == draft) { "read back ${AgeDraft.read(draft.name)}" }
            } finally {
                AgeDraft.delete(draft.name)
            }
        }
        check(AgeDraft.read("workshop-check") == null) { "the draft survived being deleted" }
    }

    /**
     * The settled seed must be **the game's own**, or the workshop previews a world nobody could be
     * handed: `/age write` with no seed hashes the Age's id, and this has to be that same number.
     */
    test("the settled seed is what the game gives a book with no seed on it") {
        val draft = AgeDraft("emerald_deep", book, Seeding.SETTLED)
        val theGames = AgeRecipe.seedFor(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "emerald_deep"))
        check(draft.seedNow() == theGames) { "workshop ${draft.seedNow()}, game $theGames" }
        // And it must not drift when the book is rewritten under it, or tuning a page would move the world.
        check(draft.copy(pages = book.dropLast(2)).seedNow() == theGames) { "editing the book moved the seed" }
    }

    test("a drawn seed is a different one each roll, and a chosen one is the number typed") {
        val rolls = (1..8).map { AgeDraft("x", book, Seeding.DRAWN).rolled().seedNow() }.toSet()
        check(rolls.size > 1) { "eight rolls gave $rolls" }
        check(AgeDraft("x", book, Seeding.CHOSEN, 99L).seedNow() == 99L)
    }
})
