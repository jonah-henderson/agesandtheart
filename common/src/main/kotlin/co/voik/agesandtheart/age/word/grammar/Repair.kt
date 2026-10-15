package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.Vocabulary
import kotlin.random.Random

/**
 * What a book that does not read becomes (design §2, §4.3.1): **the writer's own book, with the fewest
 * edits that make it read.**
 *
 * The parser says where it gave up, and only that clause is touched. Each step takes the cheapest edits
 * that let the parser read past the page it refused, so what the rest of the book said is left exactly as
 * it was written. The edits, cheapest first:
 *
 * - **an aiming page laid in** — `tempest [phenomena] aurora`, `starless [stars] landmass`. Every written
 *   page stays, in order. Never one for a part of the world the book already aims at, which would open a
 *   second sky or mint a second sun.
 * - **a page moved** to wherever the book reads with it.
 * - **a page taken out** — a second `age`, an `and` with nothing after it; a word only where nothing else
 *   will do.
 *
 * **Where several repairs cost the same, the Age's seed picks one** (Jonah, 2026-10-14), so the same
 * mistake made twice may come out differently. That is where the unexpected is allowed in, and nowhere
 * else.
 *
 * **Every edit is charged.** A page moved, or now speaking to a different part of the world, is `rehomed`, and a page taken out is
 * `impossible`. A page the Art laid in is latent: it costs no ink and the book never shows it.
 */
internal object Repair {

    private const val INSERTING = 1
    private const val MOVING = 2
    private const val DROPPING_STRUCTURE = 3
    private const val DROPPING_A_WORD = 4

    /**
     * Each other page an edit sends to a different part of the world than it was written for. Dearer than
     * moving the page that would not read, so a repair keeps what was said right over what is cheap to lay.
     */
    private const val REAIMING_ANOTHER = 2

    /** Two edits are enough for a clause with two pages out of place; past that a page is taken out. */
    private const val MOST_EDITS_PER_STEP = 2

    /** Steps before the repair stops looking and takes out what it is stuck on, so it always ends. */
    private const val STEPS_PER_PAGE = 4

    // Arbitrary, and only so the tie-break is not the seed's first draw for everything else too.
    private const val TIE_SALT = 0x5A17_0F1E_C0DEL

    /** [laid] made to read with the fewest edits, ties broken by [seed]. */
    fun of(vocabulary: Vocabulary, laid: List<Page>, seed: Long): Sentence =
        Mending(vocabulary, laid, Random(seed xor TIE_SALT)).mended()

    /** One page of the row being mended: [id] follows it through a move, and [wroteAt] says it was the writer's. */
    private data class Laid(val page: Page, val id: Int, val wroteAt: Int?, val moved: Boolean = false)

    private data class Candidate(val row: List<Laid>, val cost: Int)

    private class Mending(vocabulary: Vocabulary, written: List<Page>, private val random: Random) {
        private val original = written.mapIndexed { at, page -> Laid(page, id = at, wroteAt = at) }
        private var nextId = written.size
        private val dropped = mutableListOf<String>()

        private val aimingPages: List<Page> = Aspect.entries
            .map { Grammar.classify(vocabulary, it.page, latent = true) }
            .filter { it.kind == PageClass.SUBJECT }

        fun mended(): Sentence {
            var row = original
            val mostSteps = STEPS_PER_PAGE * (original.size + 1)
            for (step in 0..<mostSteps) {
                val refusal = ArtReading.refusal(pagesOf(row)) ?: break
                row = cheapestPast(row, refusal) ?: takingOut(row, refusal.page)
            }
            val marked = markingWhatMoved(row)
            val read = ArtReading.parse(pagesOf(marked))
                ?: return Sentence(emptyList(), impossible = original.map { it.page.written })
            return read.copy(impossible = dropped.toList())
        }

        private fun pagesOf(row: List<Laid>) = row.map(Laid::page)

        /**
         * The cheapest rows, within [MOST_EDITS_PER_STEP] edits, that read past the page [refusal] names —
         * one of them, drawn where several cost the same. Null where none does.
         */
        private fun cheapestPast(row: List<Laid>, refusal: ArtReading.Refusal): List<Laid>? {
            val settled = row.take(refusal.page + 1).map(Laid::id).toSet()
            fun readsPast(candidate: List<Laid>): Boolean {
                val stillSettled = candidate.withIndex()
                    .filter { (_, entry) -> entry.id in settled && !entry.moved }
                    .maxOfOrNull { it.index } ?: -1
                val next = ArtReading.refusal(pagesOf(candidate)) ?: return true
                return next.page > stillSettled
            }
            val reaching = mutableListOf<Candidate>()
            var frontier = listOf(Candidate(row, cost = 0))
            for (depth in 1..MOST_EDITS_PER_STEP) {
                val cheapestSoFar = reaching.minOfOrNull { it.cost }
                if (cheapestSoFar != null && cheapestSoFar <= depth * INSERTING) break
                frontier = frontier.flatMap { from ->
                    val at = if (from.row === row) refusal else ArtReading.refusal(pagesOf(from.row))
                    if (at == null) emptyList() else editsOf(from, at)
                }
                reaching += frontier.filter { readsPast(it.row) }
                frontier = frontier.filterNot { readsPast(it.row) }
            }
            val stuckId = row[refusal.page].id
            val priced = reaching.map { it.copy(cost = it.cost + REAIMING_ANOTHER * reaimedBy(it.row, besides = stuckId)) }
            val cheapest = priced.minOfOrNull { it.cost } ?: return null
            val choices = priced.filter { it.cost == cheapest }.distinctBy { signatureOf(it.row) }
            return choices[random.nextInt(choices.size)].row
        }

        /** How many of the writer's pages [row] has speaking to a part other than the one meant, [besides] the stuck one. */
        private fun reaimedBy(row: List<Laid>, besides: Int): Int {
            val reaching = reachOfEachPage(row)
            return row.withIndex().count { (at, entry) ->
                val isAnotherOfTheWriters = entry.wroteAt != null && entry.id != besides && !entry.moved
                isAnotherOfTheWriters && reaching[at] != null && reaching[at] != meantFor(entry)
            }
        }

        /** Every single edit to [from] around the page [refusal] could not read. */
        private fun editsOf(from: Candidate, refusal: ArtReading.Refusal): List<Candidate> {
            val row = from.row
            val stuck = row.getOrNull(refusal.page) ?: return emptyList()
            val aimedAlready = row.filter { it.page.kind == PageClass.SUBJECT }.map { it.page.written }.toSet()
            val wanted = row.subList(refusal.clause, refusal.page + 1).flatMap { aspectsFor(it.page) }.toSet()
            val worthAiming = aimingPages.filter { it.written !in aimedAlready && it.aspect in wanted }
            // A rung or a polarity binds to the page after it, so an aiming page laid there would be counted.
            fun bindsTheNextPage(at: Int) = row.getOrNull(at - 1)?.page?.kind.let {
                it == PageClass.QUANTIFIER || it == PageClass.RESTRICTOR || it == PageClass.EXCLUDER
            }

            val insertions = (refusal.clause + 1..refusal.page + 1).filterNot(::bindsTheNextPage).flatMap { at ->
                worthAiming.map { aiming ->
                    Candidate(row.take(at) + Laid(aiming, id = nextId++, wroteAt = null) + row.drop(at), from.cost + INSERTING)
                }
            }
            val mayMove = stuck.wroteAt != null && !stuck.moved
            val without = row.filterIndexed { at, _ -> at != refusal.page }
            val moves = if (!mayMove) emptyList() else (0..without.size)
                .filter { it != refusal.page }
                .map { at -> Candidate(without.take(at) + stuck.copy(moved = true) + without.drop(at), from.cost + MOVING) }
            val dropCost = if (stuck.page.word == null) DROPPING_STRUCTURE else DROPPING_A_WORD
            val drop = Candidate(without, from.cost + dropCost)
            return insertions + moves + drop
        }

        /** The parts of the world an aiming page could be laid in front of [page] for. */
        private fun aspectsFor(page: Page): Set<Aspect> {
            val word = page.word ?: return emptySet()
            return Grammar.placesFor(word).ifEmpty { Aspect.entries.toSet() }
        }

        /** What a repair that found nothing does: takes out the page it is stuck on, which always ends. */
        private fun takingOut(row: List<Laid>, page: Int): List<Laid> = row.filterIndexed { at, _ -> at != page }

        /**
         * The row with every writer's page marked `rehomed` that was moved, or that now speaks to a different
         * part of the world than the clause it was written in — read off the parse itself, so a page that
         * closes a clause of its own where it stands is never charged for the aiming page laid after it.
         * A page the repair took out is charged as `impossible`.
         */
        private fun markingWhatMoved(row: List<Laid>): List<Laid> {
            val kept = row.mapNotNull { it.wroteAt }.toSet()
            dropped += original.filter { it.wroteAt !in kept }.map { it.page.written }
            val reaching = reachOfEachPage(row)
            return row.mapIndexed { at, entry ->
                val wasTheWriters = entry.wroteAt != null
                val changedPart = wasTheWriters && reaching[at] != null && reaching[at] != meantFor(entry)
                if (wasTheWriters && (entry.moved || changedPart)) entry.copy(page = entry.page.copy(rehomed = true)) else entry
            }
        }

        /** What [entry] was written to speak to: the part its clause closes on, or its own where `age` closes it. */
        private fun meantFor(entry: Laid): Set<Aspect> = meant.getOrPut(entry.id) {
            val from = original.indexOfFirst { it.id == entry.id }
            val closer = original.drop(from).firstOrNull { it.page.kind == PageClass.SUBJECT || it.page.kind == PageClass.NUCLEUS }
            val aimed = closer?.page?.takeIf { it.kind == PageClass.SUBJECT }?.aspect
            if (aimed != null) setOf(aimed) else entry.page.word?.aspects.orEmpty()
        }

        private val meant = mutableMapOf<Int, Set<Aspect>>()

        /**
         * For each page of [row], the parts of the world the parse has it speaking to, or null for a page that
         * makes no claim. Constraints come out in the order their pages were laid, a siting's biome being
         * the one word page that makes none.
         */
        private fun reachOfEachPage(row: List<Laid>): List<Set<Aspect>?> {
            val said = ArtReading.parse(pagesOf(row))?.constraints ?: return row.map { null }
            var next = 0
            return row.mapIndexed { at, entry ->
                val namesASiting = row.getOrNull(at - 1)?.page?.kind == PageClass.CONFINER
                val claims = entry.page.word != null && !namesASiting
                val constraint = if (claims) said.getOrNull(next++) else null
                constraint?.takeIf { it.word === entry.page.word }?.aimedAt
            }
        }

        private fun signatureOf(row: List<Laid>): List<String> =
            row.map { "${it.page.written}:${it.wroteAt}" }
    }
}
