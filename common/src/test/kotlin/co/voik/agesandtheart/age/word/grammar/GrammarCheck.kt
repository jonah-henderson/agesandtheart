package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.read
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.aspect.Polarity
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

    /**
     * **Nothing anywhere may import `org.antlr`.**
     *
     * This used to permit one file, the adapter that held the generated parser behind [Grammar]'s port. The
     * parser is ours now — recursive descent over an already-classified row of pages, in [ArtReading] — and
     * the dependency is gone from the build, so the rule is simply absolute.
     *
     * Kept rather than deleted with the dependency, because a check that costs nothing is the cheapest way
     * to notice a library coming back in: the boundary that made swapping the parser a one-file job is the
     * same boundary that makes this worth guarding.
     */
    test("no parser generator has come back") {
        // Import lines rather than any mention of the name, or this check fails on its own error message —
        // which it did, first time out.
        val importsAParserGenerator = Regex("""^\s*import\s+org\.antlr""", RegexOption.MULTILINE)
        val roots = sourceRoots()
        check(roots.isNotEmpty()) {
            "found no Kotlin source to scan from ${Path.of("").toAbsolutePath()} — this check would pass on nothing"
        }
        val leaked = roots.flatMap { root ->
            root.walk().filter { it.extension == "kt" }
                .filter { file -> importsAParserGenerator.containsMatchIn(file.readText()) }
                .map { file -> file.fileName.toString() }
        }
        check(leaked.isEmpty()) {
            "a parser generator is back, in ${leaked.joinToString()} — the Art's parser is ArtReading.kt, " +
                "four productions of recursive descent, and it is meant to stay that way"
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
        val read = read(listOf("floating", "basalt", "age"))
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
        val aimed = read(listOf("age", "floating", "landmass", "molten", "sea"))
        check(aimed.phrases.size == 2) {
            "two aiming pages made ${aimed.phrases.size} section(s): ${aimed.phrases}"
        }
        check(aimed.phrases.map { it.subject?.word?.name } == listOf("landmass", "sea")) {
            "the sections were opened by the wrong pages: ${aimed.phrases.map { it.subject?.word?.name }}"
        }
        check(aimed.phrases.first().modifiers.map { it.word.name } == listOf("floating")) {
            "'floating' did not stay with the landmass: ${aimed.phrases.first().modifiers}"
        }

        val unaimed = read(listOf("floating", "molten", "age"))
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
        val read = read(listOf("beautiful", "floating", "basalt", "age"))
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
        val underTheSea = read(listOf("age", "lava", "sea"))
        val confined = underTheSea.constraints.first { it.word.name == "lava" }
        check(confined.aimedAt == setOf(Aspect.SEA)) { "'sea lava' let lava reach ${confined.aimedAt}" }

        // And unaimed it keeps everything it declares, or aiming would be the only way to say anything.
        val unaimed = read(listOf("lava", "age"))
        val loose = unaimed.constraints.first { it.word.name == "lava" }
        check(Aspect.TERRAIN in loose.aimedAt) {
            "an unaimed word lost an aspect it declares: ${loose.aimedAt}"
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
        val read = read(listOf("age", "starless", "landmass"))
        val land = read.phrases.first { it.subject?.word?.name == "landmass" }
        check(land.modifiers.none { it.word.name == "starless" }) {
            "a sky word aimed at the land was read as saying something about it: $land"
        }
        val adopted = read.phrases.first { phrase -> phrase.modifiers.any { it.word.name == "starless" } }
        check(adopted.subject?.word?.aspects == setOf(Aspect.STARS)) {
            "a star word aimed at the land was re-homed to ${adopted.subject?.word}, which is not the stars"
        }
        check("starless" !in read.dropped) { "a word with a home of its own was dropped: ${read.dropped}" }
    }

    /**
     * §4.5's third rung: a quantifier binds to **the term after it**, and to no other. The rung travels on
     * the claim rather than the word, so what the parser owes is putting it on the right constraint.
     */
    test("a quantifier binds to the term it precedes") {
        val read = read(listOf("age", "basalt", "and", "teeming", "deepslate", "landmass"))
        check(read.dropped.isEmpty()) { "a quantified book lost pages: ${read.dropped}" }
        val counted = read.constraints.first { it.word.name == "deepslate" }
        check(counted.density == TEEMING) { "'teeming deepslate' resolved to ${counted.density}" }
        // And it remembers the page that asked, which is what the reading says back rather than the number.
        check(counted.quantifier == "teeming") { "the rung forgot the page it came from: ${counted.quantifier}" }
        val uncounted = read.constraints.first { it.word.name == "basalt" }
        check(uncounted.density == Rung.ORDINARY) {
            "the rung leaked onto the term before it, which is not the one it counts"
        }
    }

    /**
     * §4.3.1: a word on the nucleus is laid bare, so `beautiful age` reads and still reaches the whole
     * world. This is the beginner's sentence and the commonest thing anyone writes.
     */
    test("a lean on everything, on the nucleus, is global") {
        val read = read(listOf("beautiful", "age"))
        val beautiful = read.constraints.first { it.word.name == "beautiful" }
        check(beautiful.aimedAt == beautiful.word.aspects) {
            "'beautiful' on the nucleus reached ${beautiful.aimedAt}, not all of ${beautiful.word.aspects}"
        }
    }

    /**
     * The other half: a lean on everything laid in an aimed clause belongs to what it is aimed at, and leans
     * there and nowhere else — `beautiful floating landmass` is a beautiful landmass.
     */
    test("a lean on everything laid in an aimed clause is aimed there") {
        val read = read(listOf("age", "beautiful", "floating", "landmass"))
        check(read.dropped.isEmpty() && read.impossible.isEmpty()) { "the book did not read as laid" }
        val beautiful = read.constraints.first { it.word.name == "beautiful" }
        check(beautiful.aimedAt == setOf(Aspect.TERRAIN) && !beautiful.rehomed) {
            "'beautiful' should aim at the landmass it was laid under, and aims at ${beautiful.aimedAt}"
        }
    }

    /**
     * **A word laid after the nucleus is one the writer meant to aim**, so the Art invents the clause it
     * was missing rather than dragging it back to the front — `age beautiful` becomes a beautiful *part*.
     */
    test("a word laid after the nucleus is given a clause") {
        val read = read(listOf("age", "beautiful"))
        val beautiful = read.constraints.first { it.word.name == "beautiful" }
        check(beautiful.aimedAt.size == 1) {
            "a stray 'beautiful' should be aimed at one part the Art chose, and aims at ${beautiful.aimedAt}"
        }
    }

    /**
     * A siting closes its clause and needs no subject — `zombie in jungle` (§4.3.1). Nothing covered `in`
     * at all before this, which is how it moved from the head of a clause to the end without a test moving,
     * and how it came to refuse every biome in the game unnoticed.
     */
    test("a siting closes a clause with no subject of its own") {
        val read = read(listOf("age", "zombie", "in", "jungle"))
        check(read.dropped.isEmpty()) { "the siting did not read: ${read.dropped}" }
        val sited = read.phrases.single { phrase -> phrase.said.any { it.word.name == "zombie" } }
        check(sited.confinedTo?.path == "jungle") { "'zombie' was sited in ${sited.confinedTo}" }
        check(sited.subject == null) { "a siting-closed clause invented a subject: ${sited.subject?.word}" }
    }

    /** And it may trail a subject, which is the other half of `close` — `zombie spawns in jungle`. */
    test("a siting may trail a subject") {
        val read = read(listOf("age", "zombie", "spawns", "in", "jungle"))
        check(read.dropped.isEmpty()) { "the siting did not read: ${read.dropped}" }
        val sited = read.phrases.single { phrase -> phrase.subject?.word?.name == "spawns" }
        check(sited.confinedTo?.path == "jungle") { "the clause was sited in ${sited.confinedTo}" }
    }

    /**
     * **The subject carries the clause's siting too**, which is what a minted feature reads: `Resolver`
     * mints off `subject.confinedTo`, so a subject that forgot its ground minted over the whole Age while
     * the clause around it was sited.
     */
    test("a sited clause sites its subject") {
        val read = read(listOf("age", "mud", "pits", "in", "jungle"))
        check(read.dropped.isEmpty()) { "the siting did not read: ${read.dropped}" }
        val pits = read.constraints.single { it.word.name == "pits" }
        check(pits.confinedTo?.path == "jungle") { "'pits' was sited in ${pits.confinedTo}" }
    }

    /**
     * A word reaching exactly one part of the world closes its own clause, so no aiming page is needed
     * after it (§4.3.1). It is a last resort, which the test below guards.
     */
    test("an unambiguous word closes its own clause") {
        val read = read(listOf("age", "ore_diamond"))
        check(read.dropped.isEmpty()) { "the word did not read: ${read.dropped}" }
        val ore = read.constraints.single { it.word.name == "ore_diamond" }
        check(ore.aimedAt == setOf(Aspect.FEATURES)) { "'ore_diamond' reaches ${ore.aimedAt}" }
    }

    /** And it never steals a clause an aiming page would have closed — `teeming ore_diamond features`. */
    test("an aiming page still closes a clause a term could have") {
        val read = read(listOf("age", "teeming", "ore_diamond", "features"))
        val aimed = read.phrases.single { phrase -> phrase.subject?.word?.name == "features" }
        check(aimed.modifiers.any { it.word.name == "ore_diamond" }) {
            "'ore_diamond' closed the clause itself, leaving ${aimed.modifiers.map { it.word.name }}"
        }
    }

    /**
     * And one that is not what the following aiming page is about closes a clause of its own where it
     * stands, rather than being refused by that page and moved into it by `Repair` (walked 2026-10-01: a
     * cat read as landmass and said nothing).
     */
    test("an unambiguous word before another part's aiming page closes its own clause") {
        val read = read(listOf("age", "teeming", "cat", "gentle", "landmass"))
        check(read.dropped.isEmpty()) { "the book did not read as laid: ${read.dropped}" }
        val cat = read.constraints.single { it.word.name == "cat" }
        check(cat.aimedAt == setOf(Aspect.SPAWNS)) { "'cat' reaches ${cat.aimedAt}" }
        check(cat.density == TEEMING) { "'teeming' did not reach the cat: ${cat.density}" }
        val landmass = read.phrases.single { phrase -> phrase.subject?.word?.name == "landmass" }
        check(landmass.modifiers.map { it.word.name } == listOf("gentle")) {
            "the landmass clause holds ${landmass.modifiers.map { it.word.name }}"
        }
    }

    /** A self-closing word keeps the quantifier and the `except` laid in front of it. */
    test("a word closing its own clause keeps its rung and its polarity") {
        val counted = read(listOf("age", "teeming", "ore_diamond"))
        val ore = counted.constraints.single { it.word.name == "ore_diamond" }
        check(ore.density == TEEMING) { "'teeming ore_diamond' came out at ${ore.density}" }
        val struck = read(listOf("age", "no", "zombie"))
        val zombie = struck.constraints.single { it.word.name == "zombie" }
        check(zombie.polarity == Polarity.EXCEPT) { "'no zombie' came out ${zombie.polarity}" }
    }

    /**
     * §4.3.1: a lean on everything is sited like any other word — it reaches the spawns, which vanilla
     * resolves through the biome, so `beautiful zombie in jungle` is a beautiful jungle's zombies.
     */
    test("a lean on everything is sited like any word") {
        val read = read(listOf("age", "beautiful", "zombie", "in", "jungle"))
        val beautiful = read.constraints.first { it.word.name == "beautiful" }
        check(beautiful.confinedTo?.path == "jungle") { "'beautiful' was sited in ${beautiful.confinedTo}" }
    }

    /** The other half: a word that narrows candidates narrows where it speaks. */
    test("a narrowing word is confined") {
        val read = read(listOf("floating", "age"))
        val floating = read.constraints.single()
        check(floating.aimedAt == setOf(Aspect.TERRAIN)) { "'floating' reaches ${floating.aimedAt}" }
    }

    /**
     * `and` joins; juxtaposition does not.
     *
     * The distinction the whole conjunction rests on (§3.2): if standing side by side already meant "and",
     * then "and" would mean nothing, and there would be no way left to say *keep both*.
     */
    test("joining is not juxtaposition") {
        val joined = read(listOf("verdant", "basalt", "and", "deepslate", "age"))
        val groups = joined.constraints.mapNotNull { it.group }.distinct()
        check(groups.size == 1) { "'basalt and molten' should share one group, got ${joined.constraints}" }
        val grouped = joined.constraints.filter { it.group != null }.map { it.word.name }
        check(grouped.size == 2) { "expected two words in the group, got $grouped" }

        val unjoined = read(listOf("verdant", "basalt", "deepslate", "age"))
        check(unjoined.constraints.all { it.group == null }) {
            "unjoined juxtaposition was read as a group, which would leave 'and' meaning nothing"
        }
    }

    /** `only` and `except` attach to the values they precede, not to the section at large. */
    test("only and no reach their values") {
        for ((page, expected) in listOf("only" to Polarity.ONLY, "no" to Polarity.EXCEPT)) {
            val read = read(listOf("verdant", page, "basalt", "age"))
            val basalt = read.constraints.first { it.word.name == "basalt" }
            check(basalt.polarity == expected) { "'$page basalt' gave ${basalt.polarity}" }
            val subject = read.constraints.first { it.word.name == "verdant" }
            check(subject.polarity == Polarity.ASSERTED) { "'$page' leaked onto the subject" }
        }
    }

    /**
     * §4.3's first failure channel: what the Art cannot read is **dropped and reported**, costing vagueness
     * rather than instability. Note what is *not* asserted — that the rest of the book survives. Recovery
     * is [ArtReading]'s own strategy, so pinning it would describe the parser rather than the design.
     */
    test("an unreadable page becomes vagueness, not an error") {
        val read = read(listOf("floating", "zzzznotaword", "basalt", "age"))
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
            listOf("only", "no", "and"),
            listOf("basalt", "floating", "verdant", "deepslate"),
            // Every row names an Age, without which it is refused rather than read (§4.3.1) — and a
            // refusal loses every page at once, which would pass this check for the wrong reason.
        ).map { pages -> listOf("age") + pages }
        for (pages in books) {
            val read = read(pages)
            val accountedFor = read.words.map { it.name }.toSet() + read.dropped.toSet()
            val content = pages.filter { vocabulary.grammarWord(it) == null }
            val lost = content.filterNot { it in accountedFor }
            check(lost.isEmpty()) {
                "'${pages.joinToString(" ")}' lost ${lost.joinToString()} — neither used nor reported, which is " +
                    "the one failure §3.3 forbids"
            }
        }
        // The joining word owes no constraint of its own, so it must never be reported as unread.
        val joined = read(listOf("verdant", "basalt", "and", "deepslate", "age"))
        check(joined.dropped.isEmpty()) { "a structural page was reported as unread: ${joined.dropped}" }
    }

    /**
     * "A world of blackstone" — a book with **no subject at all**, naming what the rock is made of rather
     * than a shape (§3.2). A grammar demanding a subject in every section left a writer holding only
     * material pages unable to say anything. With nothing aimed at them, such words fall back to the
     * aspects they declare themselves.
     */
    test("a book that only steers still says something") {
        val read = read(listOf("basalt", "age"))
        check(read.dropped.isEmpty()) { "'basalt' alone was unreadable: ${read.dropped}" }
        val basalt = read.constraints.singleOrNull() ?: error("'basalt' alone gave ${read.constraints}")
        check(Aspect.TERRAIN in basalt.aimedAt) {
            "an unaimed material lost its own declared aspect: ${basalt.aimedAt}"
        }
    }

    /**
     * **Every book opens with the `age` page** (§4.3.1), and one that does not is not a sentence.
     *
     * The nucleus used to be optional and an empty page row read as a sentence saying nothing, which made
     * a book of no pages a way to author an Age nobody described. §2 is unhurt: the pen still never
     * refuses, because what stops parsing here becomes [Repair]'s and comes back with the Art's own words
     * — which is what the second half checks, since "not a sentence" and "silently empty" look alike from
     * the outside.
     */
    test("a book without the Age page is not a book at all") {
        check(Grammar.read(vocabulary, emptyList()) == null) { "an empty book was read as a sentence" }
        check(Grammar.read(vocabulary, listOf("landmass", "flat")) == null) {
            "a book with no Age page was read as a sentence"
        }
        check(Grammar.read(vocabulary, listOf("age")) != null) { "the Age page alone is a book and must read" }
        // **Not repaired.** Repair would hand back a whole drawn world, which is the free reroll this rule
        // exists to stop: an empty book must cost a page, some ink, and knowing that `age` opens a book.
        check(Grammar.read(vocabulary, NONSENSE.first()) == null) { "a nucleus-less book was repaired" }
    }

    /**
     * Design §2: the pen never refuses — **once the book is a book**.
     *
     * Every shape of nonsense still comes back as a sentence, because that is what [Repair] is for. The
     * one exception is above and it is a different kind of thing: naming what you are making is the ante,
     * where everything after it is forgiven.
     */
    test("the parser never refuses a book that names an Age") {
        for (pages in NONSENSE) {
            val asABook = listOf("age") + pages
            val read = runCatching { Grammar.read(vocabulary, asABook) }
                .getOrElse { failure -> error("the pen refused '${asABook.joinToString(" ")}': $failure") }
            check(read != null) { "'${asABook.joinToString(" ")}' names an Age and was still refused" }
            // And the same pages without it are refused, so the two halves cannot both be tested by luck.
            check(Grammar.read(vocabulary, pages) == null) {
                "'${pages.joinToString(" ")}' has no Age page and was read anyway"
            }
        }
    }
})

/** Every shape of nonsense a writer can lay out, none of which names an Age. */
private val NONSENSE = listOf(
    emptyList(),
    listOf("and"),
    listOf("only"),
    listOf("basalt"),
    listOf("and", "and", "and"),
    listOf("no", "and", "only"),
    listOf("zzzz", "yyyy"),
    listOf("basalt", "and"),
    listOf("floating", "and", "and", "basalt"),
)

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

/** What `art/grammar/teeming.json` asks for — four times as many. */
private const val TEEMING = 4.0
