package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.Vocabulary
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * What becomes of a book that does not read (design §2, §4.3.1).
 *
 * The parser refuses such a book outright now, so everything here is [Repair]'s work: a sentence the Art
 * draws for itself, with the writer's pages laid into it. The properties are the promises that make it
 * something a writer can be *shown* rather than something that merely happened to them.
 */
@Tags(NEEDS_REGISTRIES)
class RepairCheck : FunSpec({

    val vocabulary by lazy {
        Vocabulary.load(MinecraftRegistries.shippedData()).also {
            check(it.problems.isEmpty()) { "the corpus would not load: ${it.problems}" }
        }
    }

    /** A sky word under the land: the commonest way to write a book that is not a sentence. */
    val misaimed = listOf("age", "landmass", "starless")

    /**
     * **Nothing the writer wrote is lost to a book that does not parse.** This is the whole of why repair
     * exists: `landmass starless` used to cost the writer their `starless` page and tell them it was
     * unreadable, when the word is perfectly readable and only the aim was wrong.
     */
    test("a book that does not read keeps the writer's pages") {
        val read = Grammar.read(vocabulary, misaimed)
        check(read.dropped.isEmpty()) { "a re-homable page was dropped: ${read.dropped}" }
        val kept = read.written.map { it.word.name }
        check(kept == listOf("landmass", "starless")) { "the writer's pages came back as $kept" }
    }

    /**
     * **Deterministic**, which is not a nicety: an Age rebuilds from its recipe on every open, so a repair
     * that drew differently the second time would be a different world under the same book.
     *
     * Drawn from the pages themselves rather than from a seed, so the reading belongs to the book and the
     * seed belongs to the Age — a Descriptive Book reads the same wherever it is carried.
     */
    test("the same book always repairs the same way") {
        val once = Grammar.read(vocabulary, misaimed)
        val again = Grammar.read(vocabulary, misaimed)
        check(once == again) { "one book read two ways:\n  $once\n  $again" }
    }

    /**
     * **Every page is one or the other**, and which is never in doubt: a writer's page is what the book
     * shows and what the ink was spent on, and the Art's is the natural course of a world nobody described
     * that far.
     */
    test("the writer's pages are theirs and the Art's are not") {
        val read = Grammar.read(vocabulary, misaimed)
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
        val read = Grammar.read(vocabulary, misaimed)
        val adopted = read.phrases.first { phrase -> phrase.modifiers.any { it.word.name == "starless" } }
        check(adopted.subject?.word?.aspects == setOf(Aspect.SKY)) {
            "'starless' was re-homed under ${adopted.subject?.word}"
        }
        val said = Readout.of(read)
        val underTheSky = said.substringAfter("sky", missingDelimiterValue = "")
        check("starless" in underTheSky) { "the readout hid where the page landed: '$said'" }
    }

    /**
     * **A move is charged, and only a move is** (§4.3.1). The design's objection was never to re-homing
     * but to *silent* re-homing, so the charge is what makes the whole mechanism admissible — and charging
     * a page that went exactly where it was written would bill every repaired book for the pages it got
     * right.
     */
    test("only the page that moved is charged for moving") {
        val read = Grammar.read(vocabulary, listOf("age", "landmass", "flat", "starless"))
        val moved = read.written.filter { it.rehomed }.map { it.word.name }
        check(moved == listOf("starless")) { "the pages read as moved were $moved" }

        val charged = Resolver.resolve(vocabulary, read, SAMPLE_SEED).instability
        val rehomings = charged.flaws.filter { it.register == Register.REHOMED }
        check(rehomings.size == 1) { "one page moved and ${rehomings.size} charges were levied: $charged" }
        check(rehomings.single().aspect == Aspect.SKY) { "the charge did not say where: ${rehomings.single()}" }
    }

    /**
     * **The Art speaks only where the writer did not.** Filling in must never argue back: a latent page
     * contending with a written one would charge a writer for a contradiction they did not write, in a
     * clause they cannot see.
     */
    test("the Art says nothing about a part of the world the writer spoke of") {
        val read = Grammar.read(vocabulary, listOf("age", "landmass", "flat", "sky", "landmass"))
        val spokenIn = read.phrases.filter { phrase -> phrase.modifiers.any { !it.latent } }
        check(spokenIn.isNotEmpty()) { "the writer's pages reached no clause at all: ${read.phrases}" }
        for (phrase in spokenIn) {
            val secondOpinion = phrase.modifiers.filter { it.latent }.map { it.word.name }
            check(secondOpinion.isEmpty()) {
                "the Art answered back under ${phrase.subject?.word}: ${secondOpinion.joinToString(" ")}"
            }
        }
    }

    /**
     * **A repaired book is shown as its writer wrote it** (§4.3.1). The Art's own pages are the world's,
     * not the book's — a readout carrying them would tell a writer they had said things they never wrote.
     */
    test("the readout says only what the writer wrote") {
        val read = Grammar.read(vocabulary, misaimed)
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
        val read = Grammar.read(vocabulary, misaimed)
        val theirsAlone = Sentence(
            read.written.map { Phrase(modifiers = listOf(it)) },
            structural = read.structural,
        )
        val repaired = Resolver.resolve(vocabulary, read, SAMPLE_SEED).cost
        val asWritten = Resolver.resolve(vocabulary, theirsAlone, SAMPLE_SEED).cost
        check(repaired == asWritten) { "a repaired book cost $repaired where its writer spent $asWritten" }
    }

    /**
     * The one page repair does lose: a page with **no position anywhere**. A second `Age` is the clearest
     * case — a book has one head and there is nowhere in a sentence for a second — and losing it must be
     * reported rather than silent (§3.3).
     */
    test("a page with nowhere to go is dropped and reported") {
        val read = Grammar.read(vocabulary, listOf("age", "age", "landmass"))
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

    /** Design §2: the pen never refuses, and repair is now the whole of what stands behind that. */
    test("repair never refuses") {
        val nonsense = listOf(
            listOf("and"),
            listOf("landmass", "starless", "and"),
            listOf("only", "except", "and"),
            listOf("age", "teeming"),
            listOf("sky", "flat"),
            listOf("age", "sky", "flat", "landmass", "starless"),
            listOf("age", "landmass", "and", "and", "starless"),
        )
        for (pages in nonsense) {
            val read = runCatching { Grammar.read(vocabulary, pages) }
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
     * **A writer is never charged for a word the Art chose.**
     *
     * Repair completes a book by writing a whole world around the writer's pages, and those pages carry
     * tags like any others — so a fill-in can contradict the one word a writer actually laid, and the
     * instability lands on somebody who could not have seen it coming and cannot do anything about it.
     * Found in a walk (Jonah, 2026-08-06): every repair skeleton said `sea open`, whose `empty` is the
     * antonym of `watery`, so writing `drenched` and nothing else bought a contradiction outright.
     *
     * `Repair.deferringToTheWriter` already takes the Art's words back out of every clause the writer spoke
     * in, which is the same promise — but a clause is not far enough. Instability is read across the whole
     * sentence, so a word in the *sea* can fight a word in the *atmosphere* and neither is in the other's
     * clause.
     *
     * This is a **content** check, not a mechanism one: what it asks is whether the shipped grammar can
     * write a world that argues with an ordinary word. If it can, the grammar is what changes.
     */
    test("repair never contradicts the one word a writer laid") {
        val quarrelsome = vocabulary.authoredWords.filterNot { it.query.isEmpty() }
        val complaints = mutableListOf<String>()
        for (word in quarrelsome) {
            // Aimed at a section the word is not about, which is what sends a book through repair at all —
            // a word laid under the nucleus alone reads fine, and a book that reads is never filled in.
            for (aimedAt in MISAIMED_AT.filterNot { it in word.aspects.map(Aspect::key) }) {
                val laid = listOf("age", aimedAt, word.name)
                val read = Grammar.read(vocabulary, laid)
                for (seed in REPAIR_SEEDS) {
                    val flaws = Resolver.resolve(vocabulary, read, seed).instability.flaws
                    val theArtsOwn = flaws.filterNot { flaw -> flaw.words.all { it in laid } }
                    if (theArtsOwn.isEmpty()) continue
                    complaints += "'${laid.joinToString(" ")}' at seed $seed: " +
                        theArtsOwn.joinToString("; ") { it.describe() }
                }
            }
        }
        check(complaints.isEmpty()) {
            "repair wrote a world that argues with a word the writer laid alone:\n" +
                complaints.take(MOST_COMPLAINTS_SHOWN).joinToString("\n") +
                if (complaints.size > MOST_COMPLAINTS_SHOWN) "\n(and ${complaints.size - MOST_COMPLAINTS_SHOWN} more)" else ""
        }
    }
})

/** The aiming pages a misaimed book is written under — enough to send every word through repair. */
private val MISAIMED_AT = listOf("landmass", "climate", "sky", "sea")

/** A few, because a flaw can depend on the seed the book is written at. */
private val REPAIR_SEEDS = listOf(1L, 7L, 20260806L)

private const val MOST_COMPLAINTS_SHOWN = 12

private const val SAMPLE_SEED = 20260802L
