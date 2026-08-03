package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Density
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.word.Vocabulary
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * Whether the grammar reads a book the way design §4.3.1 says, and whether its boundary holds. The
 * resolver is deliberately not involved — this asks only what the *parser* decided, which is the point of
 * [Sentence] being our own type.
 */
@Tags(NEEDS_REGISTRIES)
class GrammarCheck : FunSpec({

    val vocabulary by lazy {
        Vocabulary.load(MinecraftRegistries.shippedData()).also {
            check(it.problems.isEmpty()) { "the corpus would not load: ${it.problems}" }
        }
    }

    /**
     * **Nothing but `ArtGrammar.kt` may import `org.antlr`.** Swapping the parser is meant to cost one
     * file, and a boundary defended only by a comment lasts until someone is in a hurry.
     */
    test("the parser boundary holds") {
        val adapter = "ArtGrammar.kt"
        // Import lines rather than any mention of the name, or this check fails on its own error message —
        // which it did, first time out.
        val importsTheParser = Regex("""^\s*import\s+org\.antlr""", RegexOption.MULTILINE)
        val roots = sourceRoots()
        check(roots.isNotEmpty()) {
            "found no Kotlin source to scan from ${Path.of("").toAbsolutePath()} — this check would pass on nothing"
        }
        val leaked = roots.flatMap { root ->
            root.walk().filter { it.extension == "kt" && it.fileName.toString() != adapter }
                .filter { file -> importsTheParser.containsMatchIn(file.readText()) }
                .map { file -> file.fileName.toString() }
        }
        check(leaked.isEmpty()) {
            "the parser has leaked out of $adapter into ${leaked.joinToString()} — see Grammar's KDoc for why " +
                "that boundary exists, and put the translation back behind it"
        }
    }

    /**
     * A production nothing spells is a structure no writer can reach — a content bug, like an unbacked word.
     *
     * Asked of **every** production, there being no such thing as one held back: a page teaches its word
     * outright and mastery gates comprehension rather than availability.
     */
    test("every production has a word") {
        val spelled = vocabulary.grammarWords.map { it.production }.toSet()
        val unreachable = Production.entries.filter { it !in spelled }
        check(unreachable.isEmpty()) {
            "no word spells ${unreachable.joinToString { it.key }}, so the structure cannot be written"
        }
    }

    /** Position decides attachment: a modifier belongs to the subject it follows, never to one it does not. */
    test("a section attaches its modifiers") {
        val read = Grammar.read(vocabulary, listOf("age", "floating", "basalt"))
        check(read.dropped.isEmpty()) { "a plain two-page book would not read: ${read.dropped}" }
        check(read.constraints.size == 2) { "expected a subject and its modifier, got ${read.constraints}" }
        check(read.words.map { it.name } == listOf("floating", "basalt")) {
            "the reading reordered the book: ${read.words}"
        }
    }

    /**
     * **An aiming page opens a section; a word that fills something does not.** Players do not write
     * presets — those are ours — so what carves a book into parts is the target a writer aimed at.
     *
     * Written as a pair on purpose: the same two content words are one section or two depending on nothing
     * but whether an aiming page stands in front of them, which is the whole of what position decides.
     */
    test("an aiming page opens a section") {
        val aimed = Grammar.read(vocabulary, listOf("age", "landmass", "floating", "sea", "molten"))
        check(aimed.phrases.size == 2) {
            "two aiming pages made ${aimed.phrases.size} section(s): ${aimed.phrases}"
        }
        check(aimed.phrases.map { it.subject?.word?.name } == listOf("landmass", "sea")) {
            "the sections were opened by the wrong pages: ${aimed.phrases.map { it.subject?.word?.name }}"
        }
        check(aimed.phrases.first().modifiers.map { it.word.name } == listOf("floating")) {
            "'floating' did not stay with the landmass: ${aimed.phrases.first().modifiers}"
        }

        val unaimed = Grammar.read(vocabulary, listOf("age", "floating", "molten"))
        check(unaimed.phrases.size == 1) {
            "a book with no aiming page split into ${unaimed.phrases.size} sections: ${unaimed.phrases}"
        }
        check(unaimed.phrases.single().subject == null) { "a section was given a subject nobody wrote" }
    }

    /**
     * The beginner's book, which is the commonest thing anyone writes: no aiming pages at all, so every
     * word is about the whole Age. Aiming is a found page (§4.5), and a grammar that demanded one would
     * make the first book unwritable.
     */
    test("a book that aims at nothing still says everything in it") {
        val read = Grammar.read(vocabulary, listOf("beautiful", "age", "floating", "basalt"))
        check(read.dropped.isEmpty()) { "an unaimed book lost pages: ${read.dropped}" }
        check(read.words.map { it.name } == listOf("beautiful", "floating", "basalt")) {
            "the reading reordered or dropped an unaimed book: ${read.words}"
        }
    }

    /**
     * **Aiming confines, and this is what it is for.** A word's scope is its own declared aspects ∩ what
     * the section aims at (§4.3.1).
     *
     * `lava` declares both the sea and the terrain, since a fluid is a sea and also a block a landmass can
     * be made of. Written under `sea` it must be the sea only — without the intersection it silently made
     * the *land* out of lava as well, and the readout said nothing about it because the parse looked right.
     */
    test("aiming confines a word to what it was aimed at") {
        val underTheSea = Grammar.read(vocabulary, listOf("age", "sea", "lava"))
        val confined = underTheSea.constraints.first { it.word.name == "lava" }
        check(confined.scope.reaches(emptyList()) == listOf(Aspect.SEA)) {
            "'sea lava' let lava reach ${confined.scope.reaches(emptyList())}"
        }

        // And unaimed it keeps everything it declares, or aiming would be the only way to say anything.
        val unaimed = Grammar.read(vocabulary, listOf("age", "lava"))
        val loose = unaimed.constraints.first { it.word.name == "lava" }
        check(Aspect.TERRAIN in loose.scope.reaches(emptyList())) {
            "an unaimed word lost an aspect it declares: ${loose.scope.reaches(emptyList())}"
        }
    }

    /**
     * The other side of aiming: a word cannot be **absorbed** by a section it says nothing about.
     *
     * A section admits only terms belonging to the part of the world it aims at, so `landmass starless`
     * cannot make the land starless — it is not a sentence at all, and what happens to it is [Repair]'s
     * business. What is asserted here is the half the grammar owes: the word keeps its own meaning and
     * takes nothing from the land.
     *
     * **Re-homing owes the writer a charge**, which is not the parser's to levy either. What it does owe
     * is that the move be *visible*, which is why the clause the word ended up under is asserted too.
     */
    test("a word cannot be absorbed by a section it says nothing about") {
        val read = Grammar.read(vocabulary, listOf("age", "landmass", "starless"))
        val land = read.phrases.first { it.subject?.word?.name == "landmass" }
        check(land.modifiers.none { it.word.name == "starless" }) {
            "a sky word aimed at the land was read as saying something about it: $land"
        }
        val adopted = read.phrases.first { phrase -> phrase.modifiers.any { it.word.name == "starless" } }
        check(adopted.subject?.word?.aspects == setOf(Aspect.SKY)) {
            "a sky word aimed at the land was re-homed to ${adopted.subject?.word}, which is not the sky"
        }
        check("starless" !in read.dropped) { "a word with a home of its own was dropped: ${read.dropped}" }
    }

    /**
     * §4.5's third rung: a quantifier binds to **the term after it**, and to no other. The rung travels on
     * the claim rather than the word, so what the parser owes is putting it on the right constraint.
     */
    test("a quantifier binds to the term it precedes") {
        val read = Grammar.read(vocabulary, listOf("age", "landmass", "basalt", "and", "teeming", "slate"))
        check(read.dropped.isEmpty()) { "a quantified book lost pages: ${read.dropped}" }
        val counted = read.constraints.first { it.word.name == "slate" }
        check(counted.density == Density.TEEMING) { "'teeming slate' resolved to ${counted.density}" }
        val uncounted = read.constraints.first { it.word.name == "basalt" }
        check(uncounted.density == Density.ORDINARY) {
            "the rung leaked onto the term before it, which is not the one it counts"
        }
    }

    /**
     * §4.3.1's tier rule, and the one protecting the beginner's sentence: `beautiful landmass` must leave
     * "beautiful" reaching the whole world, merely leaning hardest on the terrain. Confining it makes the
     * commonest thing anyone writes the *narrow* reading.
     */
    test("an evocative word stays global when aimed") {
        val read = Grammar.read(vocabulary, listOf("age", "beautiful", "landmass", "floating"))
        val beautiful = read.constraints.first { it.word.name == "beautiful" }
        val scope = beautiful.scope as? Scope.Everywhere
            ?: error("an aimed evocative word was confined to ${beautiful.scope}, which demotes it to restrictive")
        check(Aspect.TERRAIN in scope.emphasised) {
            "'beautiful' before a terrain should lean on the terrain, but emphasises ${scope.emphasised}"
        }
    }

    /** The other half: a word that narrows candidates narrows where it speaks. */
    test("a narrowing word is confined") {
        val read = Grammar.read(vocabulary, listOf("age", "floating"))
        val floating = read.constraints.single()
        val scope = floating.scope as? Scope.Confined ?: error("'floating' was left global at ${floating.scope}")
        check(scope.aspects == setOf(Aspect.TERRAIN)) { "'floating' reaches ${scope.aspects}" }
    }

    /**
     * `and` joins; juxtaposition does not.
     *
     * The distinction the whole conjunction rests on (§3.2): if standing side by side already meant "and",
     * then "and" would mean nothing, and there would be no way left to say *keep both*.
     */
    test("joining is not juxtaposition") {
        val joined = Grammar.read(vocabulary, listOf("age", "verdant", "basalt", "and", "slate"))
        val groups = joined.constraints.mapNotNull { it.group }.distinct()
        check(groups.size == 1) { "'basalt and molten' should share one group, got ${joined.constraints}" }
        val grouped = joined.constraints.filter { it.group != null }.map { it.word.name }
        check(grouped.size == 2) { "expected two words in the group, got $grouped" }

        val unjoined = Grammar.read(vocabulary, listOf("age", "verdant", "basalt", "slate"))
        check(unjoined.constraints.all { it.group == null }) {
            "unjoined juxtaposition was read as a group, which would leave 'and' meaning nothing"
        }
    }

    /** `only` and `except` attach to the values they precede, not to the section at large. */
    test("only and except reach their values") {
        for ((page, expected) in listOf("only" to Polarity.ONLY, "except" to Polarity.EXCEPT)) {
            val read = Grammar.read(vocabulary, listOf("age", "verdant", page, "basalt"))
            val basalt = read.constraints.first { it.word.name == "basalt" }
            check(basalt.polarity == expected) { "'$page basalt' gave ${basalt.polarity}" }
            val subject = read.constraints.first { it.word.name == "verdant" }
            check(subject.polarity == Polarity.ASSERTED) { "'$page' leaked onto the subject" }
        }
    }

    /**
     * §4.3's first failure channel: what the Art cannot read is **dropped and reported**, costing vagueness
     * rather than instability. Note what is *not* asserted — that the rest of the book survives. Recovery
     * is ANTLR's own strategy, so pinning it would describe the parser rather than the design.
     */
    test("an unreadable page becomes vagueness, not an error") {
        val read = Grammar.read(vocabulary, listOf("age", "floating", "zzzznotaword", "basalt"))
        check(read.unreadable == listOf("zzzznotaword")) { "an unknown page was not reported: ${read.unreadable}" }
        check(read.constraints.any { it.word.name == "floating" }) {
            "one unreadable page cost the whole book: ${read.constraints}"
        }
    }

    /**
     * **Nothing vanishes in silence.** Every content page is either *used* or *reported*; a page that does
     * neither means a writer gets a world their sentence did not describe and is told nothing (§3.3).
     *
     * Written as an invariant over malformed books on purpose: an earlier version asserted one particular
     * word was *dropped*, and a grammar fix that made it usable failed the check while improving the
     * behaviour. Structural pages are exempt — `and` speaks by joining two words that do.
     */
    test("nothing vanishes in silence") {
        val books = listOf(
            listOf("floating", "beautiful"),
            listOf("basalt", "and"),
            listOf("and", "basalt"),
            listOf("floating", "zzzznotaword", "beautiful"),
            listOf("verdant", "and", "and", "basalt"),
            listOf("beautiful", "and", "floating"),
            listOf("only", "except", "and"),
            listOf("basalt", "floating", "verdant", "slate"),
        )
        for (pages in books) {
            val read = Grammar.read(vocabulary, pages)
            val accountedFor = read.words.map { it.name }.toSet() + read.dropped.toSet()
            val content = pages.filter { vocabulary.grammarWord(it) == null }
            val lost = content.filterNot { it in accountedFor }
            check(lost.isEmpty()) {
                "'${pages.joinToString(" ")}' lost ${lost.joinToString()} — neither used nor reported, which is " +
                    "the one failure §3.3 forbids"
            }
        }
        // The joining word owes no constraint of its own, so it must never be reported as unread.
        val joined = Grammar.read(vocabulary, listOf("age", "verdant", "basalt", "and", "slate"))
        check(joined.dropped.isEmpty()) { "a structural page was reported as unread: ${joined.dropped}" }
    }

    /**
     * "A world of blackstone" — a book with **no subject at all**, naming what the rock is made of rather
     * than a shape (§3.2). A grammar demanding a subject in every section left a writer holding only
     * material pages unable to say anything. With nothing aimed at them, such words fall back to the
     * aspects they declare themselves.
     */
    test("a book that only steers still says something") {
        val read = Grammar.read(vocabulary, listOf("age", "basalt"))
        check(read.dropped.isEmpty()) { "'basalt' alone was unreadable: ${read.dropped}" }
        val basalt = read.constraints.singleOrNull() ?: error("'basalt' alone gave ${read.constraints}")
        check(Aspect.TERRAIN in basalt.scope.reaches(emptyList())) {
            "an unaimed material lost its own declared aspect: ${basalt.scope}"
        }
    }

    /** Design §2: the pen never refuses. Every shape of nonsense must still come back as a sentence. */
    test("the parser never refuses") {
        val nonsense = listOf(
            emptyList(),
            listOf("and"),
            listOf("only"),
            listOf("basalt"),
            listOf("and", "and", "and"),
            listOf("except", "and", "only"),
            listOf("zzzz", "yyyy"),
            listOf("basalt", "and"),
            listOf("floating", "and", "and", "basalt"),
        )
        for (pages in nonsense) {
            runCatching { Grammar.read(vocabulary, pages) }
                .getOrElse { failure -> error("the pen refused '${pages.joinToString(" ")}': $failure") }
        }
    }
})

/**
 * The module's Kotlin sources, for the boundary scan. **Filtered to the roots that exist, and the caller
 * asserts it found some** — a boundary check that passes because it looked nowhere is worse than none. The
 * test root is included: a check importing the parser breaches the boundary as a production file would.
 */
private fun sourceRoots(): List<Path> = listOf(
    Path.of("src/main/kotlin"),
    Path.of("src/preview/kotlin"),
    Path.of("src/test/kotlin"),
    Path.of("common/src/main/kotlin"),
    Path.of("common/src/preview/kotlin"),
    Path.of("common/src/test/kotlin"),
).filter { it.isDirectory() }
