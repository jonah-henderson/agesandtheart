package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.read
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.Resolver
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * What becomes of a book that does not read (design §2, §4.3.1).
 *
 * The parser refuses such a book outright, so everything here is [Repair]'s work: the writer's own book with
 * the fewest edits that make it read. The properties are the promises that make it something a writer can
 * be *shown* rather than something that merely happened to them.
 */
@Tags(NEEDS_REGISTRIES)
class RepairCheck : FunSpec({

    /** A sky word under the land: the commonest way to write a book that is not a sentence. */
    val misaimed = listOf("age", "starless", "landmass")

    /**
     * **Nothing the writer wrote is lost to a book that does not parse.** This is the whole of why repair
     * exists: `landmass starless` used to cost the writer their `starless` page and tell them it was
     * unreadable, when the word is perfectly readable and only the aim was wrong.
     */
    test("a book that does not read keeps the writer's pages") {
        val read = read(misaimed)
        check(read.dropped.isEmpty()) { "a re-homable page was dropped: ${read.dropped}" }
        val kept = read.written.map { it.word.name }
        check(kept == listOf("starless", "landmass")) { "the writer's pages came back as $kept, out of the order laid" }
    }

    /**
     * **What read is left alone.** `tempest aurora obsidian landmass` fails at `tempest aurora`, and the
     * clause after it said exactly what it meant — it keeps its stone, in its own clause, uncharged.
     */
    test("a clause that reads survives the repair of the one beside it") {
        val read = read(listOf("age", "tempest", "aurora", "obsidian", "landmass"))
        val landmass = read.phrases.first { it.subject?.word?.aspects == setOf(Aspect.TERRAIN) }
        val kept = landmass.modifiers.firstOrNull { it.word.name == "obsidian" }
        check(kept != null) { "`obsidian landmass` was pulled apart: ${Readout.of(read)}" }
        check(!kept.rehomed) { "a page that stayed in its own clause was charged as moved" }
    }

    /**
     * **Every page is one or the other**, and which is never in doubt: a writer's page is what the book
     * shows and what the ink was spent on, and the Art's is an aiming page it laid in to make the book read.
     */
    test("the writer's pages are theirs and the Art's are not") {
        val read = read(misaimed)
        val written = read.written.map { it.word.name }.toSet()
        check(written == setOf("landmass", "starless")) { "the reading claimed the writer wrote $written" }
        val supplied = read.constraints.filter { it.latent }
        check(supplied.isNotEmpty()) { "a book that does not parse was repaired with nothing at all" }
        check(supplied.none { it.word.name in written }) {
            "a page is the writer's and the Art's at once: ${supplied.map { it.word.name }}"
        }
    }

    /**
     * §4.3.1's promise that a re-homing is **visible**. The word keeps its own meaning, lands under the
     * part of the world it is about, and the clause that adopted it survives into the readout — a writer
     * owed "under sky starless" must not be handed a bare "starless".
     */
    test("a re-homed page lands where it means something, and says so") {
        val read = read(misaimed)
        val adopted = read.phrases.first { phrase -> phrase.modifiers.any { it.word.name == "starless" } }
        check(adopted.subject?.word?.aspects == setOf(Aspect.STARS)) {
            "'starless' was re-homed under ${adopted.subject?.word}"
        }
        val said = Readout.of(read)
        // Named for the page a writer lays, which is what the reading has to show. **Before** it,
        // modifiers leading their subject now (§4.3.1) — "under starless sky".
        val underTheStars = said.substringBefore("stars", missingDelimiterValue = "")
        check("starless" in underTheStars) { "the readout hid where the page landed: '$said'" }
    }

    /**
     * **A move is charged, and only a move is** (§4.3.1). The design's objection was never to re-homing
     * but to *silent* re-homing, so the charge is what makes the whole mechanism admissible — and charging
     * a page that went exactly where it was written would bill every repaired book for the pages it got
     * right.
     */
    test("only the page that moved is charged for moving") {
        // `flat` belongs under the land and `starless` does not, so only the second has to move.
        val read = read(listOf("age", "flat", "starless", "landmass"))
        val moved = read.written.filter { it.rehomed }.map { it.word.name }
        check(moved == listOf("starless")) { "the pages read as moved were $moved" }

        val charged = Resolver.resolve(vocabulary, read, SAMPLE_SEED).instability
        val rehomings = charged.flaws.filter { it.register == Register.REHOMED }
        check(rehomings.size == 1) { "one page moved and ${rehomings.size} charges were levied: $charged" }
        check(rehomings.single().aspect == Aspect.STARS) { "the charge did not say where: ${rehomings.single()}" }
    }

    /**
     * **A repaired book is shown as its writer wrote it** (§4.3.1). The Art's own pages are the world's,
     * not the book's — a readout carrying them would tell a writer they had said things they never wrote.
     */
    test("the readout says only what the writer wrote") {
        val read = read(misaimed)
        val said = Readout.of(read)
        val supplied = read.constraints.filter { it.latent }.map { it.word.name }
            .filter { word -> word in said.split(" ", ",", ".") }
        // A subject the writer's word was re-homed under is not the Art speaking: it is the address.
        val addresses = read.phrases.mapNotNull { phrase ->
            phrase.subject?.word?.name?.takeIf { phrase.modifiers.any { modifier -> !modifier.latent } }
        }
        check(supplied.all { it in addresses }) { "the readout put the Art's words in the writer's mouth: '$said'" }
    }

    /**
     * **A latent page costs nobody ink.** The writer never spent it, so the pot never saw it — an Age that
     * grew more expensive for being unreadable would price a mistake as though it were a lavish book.
     */
    test("filling a book in costs the writer nothing") {
        val read = read(misaimed)
        val theirsAlone = Sentence(
            read.written.map { Phrase(modifiers = listOf(it)) },
            structural = read.structural,
        )
        val repaired = Resolver.resolve(vocabulary, read, SAMPLE_SEED).cost
        val asWritten = Resolver.resolve(vocabulary, theirsAlone, SAMPLE_SEED).cost
        check(repaired == asWritten) { "a repaired book cost $repaired where its writer spent $asWritten" }
    }

    /**
     * The one page repair does lose: a page **no cheaper edit can make read**. A second `Age` is the clearest
     * case — a book has one head and there is nowhere in a sentence for a second — and losing it must be
     * reported rather than silent (§3.3).
     */
    test("a page with nowhere to go is dropped and reported") {
        val read = read(listOf("age", "age", "landmass"))
        check(read.impossible == listOf("age")) { "a second Age was not reported: ${read.impossible}" }
        // Not the other channel: nobody failed to *read* the page, there was nowhere to put it (§4.3).
        check(read.unreadable.isEmpty()) { "an impossibility was reported as vagueness: ${read.unreadable}" }
        check(read.written.any { it.word.name == "landmass" }) {
            "one impossible page cost the rest of the book: ${read.written}"
        }
        val charged = Resolver.resolve(vocabulary, read, SAMPLE_SEED).instability
        check(charged.flaws.any { it.register == Register.IMPOSSIBLE }) {
            "a page no sentence has room for was lost for free: $charged"
        }
    }

    /**
     * Design §2: the pen never refuses, and repair is now the whole of what stands behind that.
     *
     * **Every row names an Age**, because that is the one thing repair is not for: a book without the
     * `age` page is refused before repair is reached (§4.3.1), so fuzzing those here would re-test the
     * refusal and leave repair itself unexercised.
     */
    test("repair never refuses a book that names an Age") {
        val nonsense = listOf(
            listOf("age", "and"),
            listOf("age", "landmass", "starless", "and"),
            listOf("age", "only", "no", "and"),
            listOf("age", "teeming"),
            listOf("age", "firmament", "flat"),
            listOf("age", "sky", "flat", "landmass", "starless"),
            listOf("age", "landmass", "and", "and", "starless"),
        )
        for (pages in nonsense) {
            val read = runCatching { read(pages) }
                .getOrElse { failure -> error("repair refused '${pages.joinToString(" ")}': $failure") }
            val content = pages.filter { vocabulary.grammarWord(it) == null }
            val accountedFor = read.written.map { it.word.name }.toSet() + read.dropped.toSet()
            val lost = content.filterNot { it in accountedFor }
            check(lost.isEmpty()) {
                "'${pages.joinToString(" ")}' lost ${lost.joinToString()} — neither laid nor reported"
            }
        }
    }

    /**
     * **Where several repairs cost the same, the Age's seed picks** (Jonah, 2026-10-14). `bright` belongs to
     * many parts of the world and none of them is a structure, so the same slip made at different seeds should
     * not always land in the same place — and the same book at the same seed always must.
     */
    test("the seed breaks a tie, and only the seed") {
        val book = listOf("age", "bright", "structures")
        fun landedUnder(seed: Long) = read(book, seed).phrases
            .first { phrase -> phrase.modifiers.any { it.word.name == "bright" } }
            .subject?.word?.name
        val places = TIE_SEEDS.mapNotNull(::landedUnder).toSet()
        check(places.size > 1) { "`bright structures` landed under $places at every seed" }
        check(TIE_SEEDS.all { landedUnder(it) == landedUnder(it) }) { "one book at one seed was repaired two ways" }
    }

    /** The Art never lays in a second aiming page for a part the book already aims at: a second sun is a sun. */
    test("repair never opens a second clause about a part the book already aims at") {
        for (seed in TIE_SEEDS) {
            val read = read(listOf("age", "red", "sun", "blue", "structures"), seed)
            val suns = read.phrases.count { it.subject?.word?.name == "sun" }
            check(suns == 1) { "`red sun blue structures` at seed $seed read as ${Readout.of(read)}" }
        }
    }

    /**
     * **A move has to leave the moved page reading.** A rung laid before a part it cannot count was moved to
     * the end of the book once, failed there, and was dropped as impossible when `teeming zombie` was right
     * there in the book.
     */
    test("a page moved is moved somewhere it reads") {
        for (seed in TIE_SEEDS) {
            val read = read(listOf("age", "biomes", "teeming", "spawns", "sky", "zombie", "orange", "aurora"), seed)
            check(read.impossible.isEmpty()) { "at seed $seed the rung was dropped: ${Readout.of(read)}" }
            check(read.constraints.any { it.quantifier == "teeming" }) { "at seed $seed the rung went nowhere: ${Readout.of(read)}" }
        }
    }

    /**
     * **A repaired book shows on the panel, and only just** (Jonah, 2026-10-14). Every page a repair moved
     * counts one between them, so a book that did not read as written is never coherent and is never much
     * less so than a single mistake.
     */
    test("every page a repair moved counts one between them") {
        val read = read(listOf("age", "starless", "tempest", "landmass"))
        val instability = Resolver.resolve(vocabulary, read, SAMPLE_SEED).instability
        val moved = instability.flaws.count { it.register == Register.REHOMED }
        check(moved == 2) { "two pages were misaimed and $moved were charged: $instability" }
        check(instability.index == 1) { "two moved pages counted ${instability.index}" }
    }
})

/** Enough seeds that a word with several homes is seen to land in more than one. */
private val TIE_SEEDS = (1L..12L).toList()

private const val SAMPLE_SEED = 20260802L
