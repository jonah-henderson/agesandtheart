package co.voik.agesandtheart.preview

import co.voik.agesandtheart.age.slot.Slot
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.age.word.grammar.Polarity
import co.voik.agesandtheart.age.word.grammar.Scope
import co.voik.agesandtheart.age.word.grammar.Sentence
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.walk

/**
 * Whether the grammar reads a book the way design §4.3.1 says it should — and whether its boundary holds.
 *
 * Offline, like every other check here: parsing is a pure function of (vocabulary, pages), so it needs no
 * world and no server. The resolver is deliberately not involved; this asks only what the *parser* decided,
 * which is the whole point of `Sentence` being our own type.
 */
fun main() {
    SharedConstants.tryDetectVersion()
    Bootstrap.bootStrap()
    val vocabulary = Vocabulary.load(shippedData())
    check(vocabulary.problems.isEmpty()) { "the corpus would not load: ${vocabulary.problems}" }

    theBoundaryHolds()
    everyProductionHasAWord(vocabulary)
    aSectionAttachesItsModifiers(vocabulary)
    anEvocativeWordStaysGlobalWhenAimed(vocabulary)
    aNarrowingWordIsConfined(vocabulary)
    joiningIsNotJuxtaposition(vocabulary)
    onlyAndExceptReachTheirValues(vocabulary)
    anUnreadablePageBecomesVaguenessNotAnError(vocabulary)
    theParserNeverRefuses(vocabulary)

    println(
        "Grammar: ${vocabulary.grammarWords.size} structural words, the parser boundary is intact, " +
            "and all eight readings hold.",
    )
}

/**
 * **Nothing but `ArtGrammar.kt` may import `org.antlr`.**
 *
 * The one guard that keeps the abstraction from eroding, and mechanical for the reason every guard here is:
 * a boundary defended only by a comment is a boundary that lasts until someone is in a hurry. Swapping the
 * parser is meant to cost one file, and this is what keeps that true.
 */
private fun theBoundaryHolds() {
    val adapter = "ArtGrammar.kt"
    // Import lines rather than any mention of the name, or this check fails on its own error message —
    // which it did, first time out.
    val importsTheParser = Regex("""^\s*import\s+org\.antlr""", RegexOption.MULTILINE)
    val leaked = sourceRoots().flatMap { root ->
        root.walk().filter { it.extension == "kt" && it.fileName.toString() != adapter }
            .filter { file -> importsTheParser.containsMatchIn(file.readText()) }
            .map { file -> file.fileName.toString() }
    }
    check(leaked.isEmpty()) {
        "the parser has leaked out of $adapter into ${leaked.joinToString()} — see Grammar's KDoc for why " +
            "that boundary exists, and put the translation back behind it"
    }
}

/** A production nothing spells is a structure no writer can reach — a content bug, like an unbacked word. */
private fun everyProductionHasAWord(vocabulary: Vocabulary) {
    val spelled = vocabulary.grammarWords.map { it.production }.toSet()
    val unreachable = co.voik.agesandtheart.age.word.grammar.Production.entries.filter { it !in spelled }
    check(unreachable.isEmpty()) {
        "no word spells ${unreachable.joinToString { it.key }}, so the structure cannot be written"
    }
}

/** Position decides attachment: a modifier belongs to the subject it follows, never to one it does not. */
private fun aSectionAttachesItsModifiers(vocabulary: Vocabulary) {
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
 * landform. Confining it here would make the commonest thing anyone writes the *narrow* reading, which
 * inverts "vague is free and precision is paid for".
 */
private fun anEvocativeWordStaysGlobalWhenAimed(vocabulary: Vocabulary) {
    val read = Grammar.read(vocabulary, listOf("beautiful", "floating"))
    val beautiful = read.constraints.first { it.word.name == "beautiful" }
    val scope = beautiful.scope as? Scope.Everywhere
        ?: error("an aimed evocative word was confined to ${beautiful.scope}, which demotes it to restrictive")
    check(Slot.LANDFORM in scope.emphasised) {
        "'beautiful' before a landform should lean on the landform, but emphasises ${scope.emphasised}"
    }
}

/** The other half: a word that narrows candidates narrows where it speaks. */
private fun aNarrowingWordIsConfined(vocabulary: Vocabulary) {
    val read = Grammar.read(vocabulary, listOf("floating"))
    val floating = read.constraints.single()
    val scope = floating.scope as? Scope.Confined ?: error("'floating' was left global at ${floating.scope}")
    check(scope.slots == setOf(Slot.LANDFORM)) { "'floating' reaches ${scope.slots}" }
}

/**
 * `and` joins; juxtaposition does not.
 *
 * The distinction the whole conjunction rests on (§3.2): if standing side by side already meant "and", then
 * "and" would mean nothing, and there would be no way left to say *keep both*.
 */
private fun joiningIsNotJuxtaposition(vocabulary: Vocabulary) {
    val joined = Grammar.read(vocabulary, listOf("verdant", "basalt", "and", "molten"))
    val groups = joined.constraints.mapNotNull { it.group }.distinct()
    check(groups.size == 1) { "'basalt and molten' should share one group, got ${joined.constraints}" }
    val grouped = joined.constraints.filter { it.group != null }.map { it.word.name }
    check(grouped.size == 2) { "expected two words in the group, got $grouped" }

    val unjoined = Grammar.read(vocabulary, listOf("verdant", "basalt", "molten"))
    check(unjoined.constraints.all { it.group == null }) {
        "unjoined juxtaposition was read as a group, which would leave 'and' meaning nothing"
    }
}

/** `only` and `except` attach to the values they precede, not to the section at large. */
private fun onlyAndExceptReachTheirValues(vocabulary: Vocabulary) {
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
private fun anUnreadablePageBecomesVaguenessNotAnError(vocabulary: Vocabulary) {
    val read = Grammar.read(vocabulary, listOf("floating", "zzzznotaword", "basalt"))
    check("zzzznotaword" in read.dropped) { "an unknown page was not reported: ${read.dropped}" }
    check(read.constraints.any { it.word.name == "floating" }) {
        "one unreadable page cost the whole book: ${read.constraints}"
    }
}

/** Design §2: the pen never refuses. Every shape of nonsense must still come back as a sentence. */
private fun theParserNeverRefuses(vocabulary: Vocabulary) {
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
        val read: Sentence = runCatching { Grammar.read(vocabulary, pages) }
            .getOrElse { failure -> error("the pen refused '${pages.joinToString(" ")}': $failure") }
        check(read.constraints.size + read.dropped.size >= 0) { "unreachable" }
    }
}

/** The module's Kotlin sources, for the boundary scan. Run from `common`, so these are relative to it. */
private fun sourceRoots(): List<Path> = listOf(Path.of("src/main/kotlin"), Path.of("src/preview/kotlin"))
