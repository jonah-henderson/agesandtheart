package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Aspect
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
 * Whether the grammar reads a book the way design §4.3.1 says it should — and whether its boundary holds.
 *
 * Offline, like every other check here: parsing is a pure function of (vocabulary, pages), so it needs no
 * world and no server. The resolver is deliberately not involved; this asks only what the *parser* decided,
 * which is the whole point of [Sentence] being our own type.
 */
@Tags(NEEDS_REGISTRIES)
class GrammarCheck : FunSpec({

    val vocabulary by lazy {
        Vocabulary.load(MinecraftRegistries.shippedData()).also {
            check(it.problems.isEmpty()) { "the corpus would not load: ${it.problems}" }
        }
    }

    /**
     * **Nothing but `ArtGrammar.kt` may import `org.antlr`.**
     *
     * The one guard that keeps the abstraction from eroding, and mechanical for the reason every guard here
     * is: a boundary defended only by a comment is a boundary that lasts until someone is in a hurry.
     * Swapping the parser is meant to cost one file, and this is what keeps that true.
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
     * Asked only of the **available** ones, since an unavailable production is deliberately unreachable.
     */
    test("every production has a word") {
        val spelled = vocabulary.grammarWords.map { it.production }.toSet()
        val unreachable = Production.entries.filter { it.available && it !in spelled }
        check(unreachable.isEmpty()) {
            "no word spells ${unreachable.joinToString { it.key }}, so the structure cannot be written"
        }
    }

    /** Position decides attachment: a modifier belongs to the subject it follows, never to one it does not. */
    test("a section attaches its modifiers") {
        val read = Grammar.read(vocabulary, listOf("floating", "basalt"))
        check(read.dropped.isEmpty()) { "a plain two-page book would not read: ${read.dropped}" }
        check(read.constraints.size == 2) { "expected a subject and its modifier, got ${read.constraints}" }
        check(read.words.map { it.name } == listOf("floating", "basalt")) {
            "the reading reordered the book: ${read.words}"
        }
    }

    /**
     * §4.3.1's tier rule, first half — and the one that protects the beginner's sentence.
     *
     * `beautiful floating` must leave "beautiful" reaching the whole world, merely leaning hardest on the
     * terrain. Confining it here would make the commonest thing anyone writes the *narrow* reading, which
     * inverts "vague is free and precision is paid for".
     */
    test("an evocative word stays global when aimed") {
        val read = Grammar.read(vocabulary, listOf("beautiful", "floating"))
        val beautiful = read.constraints.first { it.word.name == "beautiful" }
        val scope = beautiful.scope as? Scope.Everywhere
            ?: error("an aimed evocative word was confined to ${beautiful.scope}, which demotes it to restrictive")
        check(Aspect.TERRAIN in scope.emphasised) {
            "'beautiful' before a terrain should lean on the terrain, but emphasises ${scope.emphasised}"
        }
    }

    /** The other half: a word that narrows candidates narrows where it speaks. */
    test("a narrowing word is confined") {
        val read = Grammar.read(vocabulary, listOf("floating"))
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
        val joined = Grammar.read(vocabulary, listOf("verdant", "basalt", "and", "slate"))
        val groups = joined.constraints.mapNotNull { it.group }.distinct()
        check(groups.size == 1) { "'basalt and molten' should share one group, got ${joined.constraints}" }
        val grouped = joined.constraints.filter { it.group != null }.map { it.word.name }
        check(grouped.size == 2) { "expected two words in the group, got $grouped" }

        val unjoined = Grammar.read(vocabulary, listOf("verdant", "basalt", "slate"))
        check(unjoined.constraints.all { it.group == null }) {
            "unjoined juxtaposition was read as a group, which would leave 'and' meaning nothing"
        }
    }

    /** `only` and `except` attach to the values they precede, not to the section at large. */
    test("only and except reach their values") {
        for ((page, expected) in listOf("only" to Polarity.ONLY, "except" to Polarity.EXCEPT)) {
            val read = Grammar.read(vocabulary, listOf("verdant", page, "basalt"))
            val basalt = read.constraints.first { it.word.name == "basalt" }
            check(basalt.polarity == expected) { "'$page basalt' gave ${basalt.polarity}" }
            val subject = read.constraints.first { it.word.name == "verdant" }
            check(subject.polarity == Polarity.ASSERTED) { "'$page' leaked onto the subject" }
        }
    }

    /**
     * §4.3's first failure channel: what the Art cannot read is **dropped and reported**, and costs vagueness
     * rather than instability.
     *
     * Note what is *not* asserted — that the rest of the book survives. It does here, but recovery is ANTLR's
     * own strategy and a garbled middle may cost the pages around it; that is a designed tolerance, not a
     * promise, so pinning it would make the check a description of the parser rather than of the design.
     */
    test("an unreadable page becomes vagueness, not an error") {
        val read = Grammar.read(vocabulary, listOf("floating", "zzzznotaword", "basalt"))
        check("zzzznotaword" in read.dropped) { "an unknown page was not reported: ${read.dropped}" }
        check(read.constraints.any { it.word.name == "floating" }) {
            "one unreadable page cost the whole book: ${read.constraints}"
        }
    }

    /**
     * **Nothing vanishes in silence** — the invariant, rather than one example of breaking it.
     *
     * Every content page a writer laid down is either *used* (it produced a constraint) or *reported* (it went
     * unread and the Age is vaguer for it). §3.3's one hard requirement, and the failure it forbids is
     * precisely a page that does neither: the writer gets a world their sentence did not describe and is told
     * nothing.
     *
     * Written as an invariant over malformed books on purpose. The first version asserted that one particular
     * trailing word was *dropped* — and then a grammar fix made that word legitimately usable, so the check
     * failed while the behaviour improved. A property about what may never happen survives the parser changing
     * its mind; a property about one parse does not.
     *
     * Structural pages are exempt: `and` earns its keep by joining two words that speak, not by speaking.
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
        val joined = Grammar.read(vocabulary, listOf("verdant", "basalt", "and", "slate"))
        check(joined.dropped.isEmpty()) { "a structural page was reported as unread: ${joined.dropped}" }
    }

    /**
     * "A world of blackstone" — a book with no subject at all.
     *
     * It names no shape; it names what the rock a shape is made of, which is the sentence the material hook was
     * built for (§3.2). A grammar demanding a subject in every section left a writer holding only material
     * pages unable to say anything, and `/age write basalt` came back refused — found on a server, not here.
     *
     * With nothing aimed at them, such words fall back to the aspects they declare themselves, which is exactly
     * what an unaimed word has always meant.
     */
    test("a book that only steers still says something") {
        val read = Grammar.read(vocabulary, listOf("basalt"))
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
 * The module's Kotlin sources, for the boundary scan.
 *
 * **Filtered to the roots that exist, and the caller asserts it found some.** The old version hard-coded two
 * relative paths and silently scanned nothing if the working directory moved — a boundary check that passes
 * because it looked nowhere is worse than none. The test source root is included: a check that imported the
 * parser to "verify" something would breach the boundary exactly as a production file would.
 */
private fun sourceRoots(): List<Path> = listOf(
    Path.of("src/main/kotlin"),
    Path.of("src/preview/kotlin"),
    Path.of("src/test/kotlin"),
    Path.of("common/src/main/kotlin"),
    Path.of("common/src/preview/kotlin"),
    Path.of("common/src/test/kotlin"),
).filter { it.isDirectory() }
