package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.Word
import kotlin.random.Random

/**
 * What a book that does not read becomes (design §2, §4.3.1).
 *
 * The Art draws a sentence of its own — a whole world, one section per part of it — and lays the writer's
 * pages into it. **Nothing is recovered from and nothing is guessed at**: every placement is a page put
 * somewhere the book still reads, tried in an order a reader can follow, so what a writer gets back is a
 * sentence they can be shown rather than whatever a parser managed to salvage.
 *
 * The rule is one sentence, and every part of it is load-bearing:
 *
 * > The Art reads **on** from where the writer left off and lays each page in the first place the book still
 * > reads, turning back only where there is nowhere ahead; and where the Art already drew that very page,
 * > the writer's *is* that one.
 *
 * *Reading on* is what keeps written order wherever written order can be kept, which is what makes a repair
 * followable — `landmass starless` puts the sky word in the sky, not at the front of the book. *Turning
 * back* is what makes `sky flat` land in the land, since a section already passed is still a home. *Already
 * drew that one* keeps a writer's `sky` from opening a second sky beside the Art's. A page with no position
 * anywhere is dropped, which is the only page repair ever loses.
 *
 * **The Art speaks only where the writer did not.** A section a writer put a page into keeps theirs and
 * loses the Art's, so what stays latent is the part of the world nobody described rather than a second
 * opinion about the part they did.
 *
 * **Deterministic, because an Age rebuilds from its recipe on every open.** The skeleton is drawn from the
 * pages themselves, so a book reads the same wherever it is carried and whatever seed it is later written
 * at — the reading belongs to the book, and the seed belongs to the Age.
 */
internal object Repair {
    /** The grammar the Art draws its own sentence from — `art/generation/repair.json`. */
    const val GRAMMAR = "repair"

    // Arbitrary, and only ever needed so a book of one page does not draw the same world as no book at all.
    private const val SKELETON_SALT = 0x5A17_0F1E_C0DEL

    // Which clause a page is in, as a number the writer's book and the filled sentence can be compared by.
    // A writer's own page is named by where they wrote it, and neither of these can be mistaken for one.
    private const val A_CLAUSE_THE_ART_OPENED = -1
    private const val NO_CLAUSE_AT_ALL = -2

    /** [laid] filled into a sentence the Art wrote for itself. */
    fun of(vocabulary: Vocabulary, laid: List<Page>): Sentence =
        Filling(skeletonFor(vocabulary, laid)).fill(laid)

    /**
     * The Art's own sentence, complete: something said about every part of the world, drawn from the
     * curated pool and never from derived content (§8.2), so a writer cannot reroll their way into an Age
     * of diamond.
     *
     * A pack whose repair grammar writes something the parser cannot read falls back to the nucleus alone —
     * the one sentence that always reads, and one whose loose modifiers admit a term of any aspect, so a
     * page still has somewhere to go.
     */
    private fun skeletonFor(vocabulary: Vocabulary, laid: List<Page>): List<Page> {
        val drawn = drawn(vocabulary, laid)
        val reads = drawn.isNotEmpty() && ArtReading.parse(drawn) != null
        return if (reads) drawn else nucleusOf(vocabulary)
    }

    /**
     * The Art's own sentence — **the quietest draw, with anything still arguing taken back out**.
     *
     * A skeleton describes a whole world, so its words carry tags like any others, and a word that
     * contradicts one of the writer's is a contradiction they did not write, cannot see and cannot do
     * anything about. [Filling.deferringToTheWriter] already refuses that within a clause; this is the same
     * promise across the sentence, where it actually bites — a `sea` word and an `atmosphere` word are
     * never in each other's clause and instability is read across the whole book.
     *
     * **Quietest rather than first quiet, and then pruned.** Taking the first candidate that argued about
     * nothing threw away a skeleton right about everything but its sea, and fell back on one *known* to
     * argue whenever all of them did — charging the writer for exactly what this promises they never pay
     * for. Scoring keeps the best of a bad draw; [pruned] then drops the words still quarrelling, which is
     * cheap where forcing them is not: a section with no modifiers is a legal section, and what a book does
     * not say the **template** answers (world model §4).
     *
     * **Still deterministic**, which the whole class depends on: the candidates come from one seeded
     * sequence drawn off the pages, and the choice between them is a pure function of the words. No Age
     * seed is involved, so a book still reads the same wherever it is carried.
     */
    private fun drawn(vocabulary: Vocabulary, laid: List<Page>): List<Page> {
        val grammar = vocabulary.generation.grammar(GRAMMAR) ?: return emptyList()
        val random = Random(seedFor(laid))
        val candidates = (1..DRAWS).map { grammar.expand(random) }
        fun pagesOf(words: List<String>) =
            words.map { Grammar.classify(vocabulary, it, latent = true) }.filter { it.kind != null }
        val drawn = candidates.map(::pagesOf)
        val quietest = drawn.minByOrNull { arguing(vocabulary, it, laid).size } ?: return emptyList()
        return pruned(vocabulary, quietest, laid)
    }

    /**
     * The Art's own pages that contradict something the writer laid.
     *
     * **Asked of everything a word *may* do, not only what it always does.** A pool is drawn per Age
     * (`Constraint.drawnAt`), so whether two words quarrel can depend on a seed repair does not have and
     * must not have: `scorching` pools a murk band that argues with `clear`'s at some seeds and not others,
     * and a repair that read only the core would promise quiet and deliver it two Ages in three. Repair
     * therefore avoids what *could* argue, which is the only promise it can keep deterministically.
     */
    private fun arguing(vocabulary: Vocabulary, skeleton: List<Page>, laid: List<Page>): List<Page> {
        fun asItMayLand(word: Word) = word.copy(sets = word.canSet)
        val written = laid.mapNotNull { it.word }.map(::asItMayLand)
        if (written.isEmpty()) return emptyList()
        return skeleton.filter { page ->
            val drawn = page.word?.let(::asItMayLand) ?: return@filter false
            written.any { vocabulary.disagreement(drawn, it) != null }
        }
    }

    /**
     * [skeleton] with each arguing page dropped, keeping only the drops the sentence still reads without.
     *
     * **By identity, not by value**: the same word may be drawn twice into different clauses, and only the
     * one that quarrels should go. An aiming page is never dropped because it never argues — it says
     * nothing itself, which is the whole of what makes it an aiming page.
     */
    private fun pruned(vocabulary: Vocabulary, skeleton: List<Page>, laid: List<Page>): List<Page> {
        var kept = skeleton
        for (page in arguing(vocabulary, skeleton, laid)) {
            val without = kept.filterNot { it === page }
            if (ArtReading.parse(without) != null) kept = without
        }
        return kept
    }

    /**
     * How many worlds to draw before settling for the first.
     *
     * The shipped grammar offers three, so this is enough to see each of them several times over and cheap
     * enough not to matter — expansion is a walk of a handful of rules, and repair happens once per book.
     */
    private const val DRAWS = 24

    private fun nucleusOf(vocabulary: Vocabulary): List<Page> {
        val age = vocabulary.grammarWords.firstOrNull { it.production == Production.NUCLEUS } ?: return emptyList()
        return listOf(Grammar.classify(vocabulary, age.name, latent = true))
    }

    /** The pages themselves, so the same book always repairs the same way. */
    private fun seedFor(laid: List<Page>): Long =
        laid.fold(SKELETON_SALT) { seed, page -> seed * 31 + page.written.hashCode() }

    /** One page of the sentence being filled, and where in the writer's book it came from. */
    private data class Laid(val page: Page, val wroteAt: Int?)

    /**
     * The skeleton as it is being filled. Every placement is decided by asking the parser, so a page is
     * only ever somewhere the book still reads.
     */
    private class Filling(skeleton: List<Page>) {
        private val laid = skeleton.map { Laid(it, wroteAt = null) }.toMutableList()
        private val impossible = mutableListOf<String>()

        /** Where written order says the next page belongs: just after the one before it. */
        private var anchor = 0

        fun fill(written: List<Page>): Sentence {
            for ((wroteAt, page) in written.withIndex()) lay(page, wroteAt)
            markWhatMoved(written)
            val read = ArtReading.parse(pages())
            // Every placement was accepted by the parser and marking a move changes only whose page a page
            // is, so this cannot fail. If it ever does, the pages are **reported** rather than dropped: a
            // repair that quietly loses a book is the one failure §3.3 forbids, and the empty sentence this
            // used to fall back to was exactly that.
            if (read == null) return Sentence(emptyList(), impossible = impossible + written.map(Page::written))
            return deferringToTheWriter(read).copy(impossible = impossible.toList())
        }

        private fun pages(): List<Page> = laid.map { it.page }

        private fun lay(page: Page, wroteAt: Int) {
            val standing = positionsFromTheAnchor().firstOrNull { at -> theArtAlreadyDrew(at, page) }
            if (standing != null) {
                // The writer's page *is* that one, so it stops being the Art's.
                laid[standing] = Laid(page, wroteAt)
                anchor = standing + 1
                return
            }
            for (at in positionsFromTheAnchor()) {
                if (!canOpenAt(at)) continue
                if (ArtReading.parse(pagesWith(at, page)) == null) continue
                laid.add(at, Laid(page, wroteAt))
                anchor = at + 1
                return
            }
            impossible += page.written
        }

        /** Every position a page could take: on from the anchor, and only then back towards the front. */
        private fun positionsFromTheAnchor(): List<Int> = (anchor..laid.size) + (anchor - 1 downTo 0)

        /**
         * Whether the Art's own page at [at] is the one the writer wrote, so the writer may simply claim it.
         *
         * **The same word is not always the same page.** A term at home in several parts of the world takes
         * its terminal from the section it sits in (`Grammar`), so two pages spelling `clear` can be a sky
         * term and an atmosphere term — and swapping one for the other here is the one placement nothing
         * asks the parser about, which turned a sentence that read into one that did not.
         */
        private fun theArtAlreadyDrew(at: Int, page: Page): Boolean {
            val standing = laid.getOrNull(at) ?: return false
            val isTheArtsOwn = standing.page.latent && standing.page.written == page.written
            return isTheArtsOwn && standing.page.aspect == page.aspect
        }

        /**
         * A rung binds to the page after it and to no other (§4.5), so nothing may be laid between the two.
         * The parser would take such a page happily and quietly count it instead.
         */
        private fun canOpenAt(at: Int): Boolean = laid.getOrNull(at - 1)?.page?.kind != PageClass.QUANTIFIER

        private fun pagesWith(at: Int, page: Page): List<Page> {
            val standing = pages()
            return standing.take(at) + page + standing.drop(at)
        }

        /**
         * Which of the writer's pages ended up in a clause other than the one they wrote it in — the whole
         * of what a re-homing is, and what repair charges for.
         *
         * **Compared by clause, never by position.** A repaired sentence is a different row of pages
         * entirely, so every page has moved in the trivial sense; what a writer needs told is only that a
         * page changed what it is *about*.
         */
        private fun markWhatMoved(written: List<Page>) {
            val wroteItUnder = clausesIn(written)
            for ((at, entry) in laid.withIndex()) {
                val wroteAt = entry.wroteAt ?: continue
                if (clauseAt(at) == wroteItUnder[wroteAt]) continue
                laid[at] = entry.copy(page = entry.page.copy(rehomed = true))
            }
        }

        /** For each page of a book, which clause it is in, named by the page that opens it. */
        private fun clausesIn(book: List<Page>): List<Int> {
            var opened = NO_CLAUSE_AT_ALL
            return book.mapIndexed { at, page ->
                if (opensAClause(page)) opened = at
                opened
            }
        }

        /** The same for the filled sentence, said in the writer's numbering so the two can be compared. */
        private fun clauseAt(at: Int): Int {
            val opened = (at downTo 0).firstOrNull { opensAClause(laid[it].page) } ?: return NO_CLAUSE_AT_ALL
            return laid[opened].wroteAt ?: A_CLAUSE_THE_ART_OPENED
        }

        private fun opensAClause(page: Page): Boolean =
            page.kind == PageClass.SUBJECT || page.kind == PageClass.NUCLEUS

        /**
         * The Art's own words taken back out of every clause the writer spoke in, so that filling in is
         * never arguing back — a latent page contending with a written one would charge a writer for a
         * contradiction they did not write and cannot see.
         *
         * Done to the reading rather than to the pages, because taking a page out could change where the
         * ones around it attach, and where a writer's page landed is settled by now.
         *
         * **A latent subject survives it**: the writer said nothing about that part of the world, they only
         * put a word under it, and it is the clause that says where their word went.
         */
        private fun deferringToTheWriter(read: Sentence): Sentence =
            read.copy(phrases = read.phrases.map(::deferring))

        private fun deferring(phrase: Phrase): Phrase {
            val theWriterSpokeHere = phrase.modifiers.any { !it.latent }
            if (!theWriterSpokeHere) return phrase
            return phrase.copy(modifiers = phrase.modifiers.filterNot { it.latent })
        }
    }
}
