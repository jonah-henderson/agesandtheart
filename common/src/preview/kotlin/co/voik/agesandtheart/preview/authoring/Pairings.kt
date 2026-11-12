package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.language.Fate
import co.voik.agesandtheart.age.language.Lexicon
import co.voik.agesandtheart.age.language.Reader
import co.voik.agesandtheart.age.language.Structure
import co.voik.agesandtheart.age.language.Word
import co.voik.agesandtheart.age.language.Writing
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.Landing
import co.voik.agesandtheart.age.resolution.Members
import co.voik.agesandtheart.age.resolution.cameToNothingAmong
import co.voik.agesandtheart.age.resolution.decision
import co.voik.agesandtheart.age.resolution.Resolution
import co.voik.agesandtheart.age.word.Vocabulary
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Every phrase a word makes, read and built: the word before each part of the world, each authored word and a few of
 * each kind of derived one, and each authored word before it where it takes pages. Each phrase is resolved beside the
 * same book with the word's page left blank, so what changed is the word's doing, and is filed as a [Verdict].
 *
 * [standIn] is read in place of the saved word of its name, or as a word of its own where there is none — a word being
 * written, or a check's own made-up word.
 */
class Pairings(private val vocabulary: Vocabulary, private val standIn: Word? = null) {

    enum class Verdict(val said: String, val asksForAPerson: Boolean) {
        /** Landed on what it names, and an argument came of it. */
        CLASHES("clashes", asksForAPerson = true),

        /** Landed on what it names and changed nothing, or what it changed was then ignored. Always a fault. */
        HOLLOW("hollow", asksForAPerson = true),

        /** Changed the Age, but one of its meanings on what it names came to nothing in every book that drew it. A fault. */
        PARTLY_HOLLOW("partly hollow", asksForAPerson = true),

        /** Named it and landed nothing anywhere. */
        SILENT("silent", asksForAPerson = true),

        /** Landed, but somewhere other than what it names. */
        STRAYS("strays", asksForAPerson = true),

        /** Landed on what it names and changed the Age. */
        MEANS("means", asksForAPerson = false),
    }

    /**
     * One phrase, [modifier] before [noun], and what came of the modifier's page there. [said] is what changed or why
     * nothing did; [hints] are what other words mean on the same noun, for a phrase that means nothing yet. [nounPart]
     * is the page of the part of the world the noun belongs to, which is how a report folds members under their part.
     */
    data class Pairing(
        val modifier: String,
        val noun: String,
        val verdict: Verdict,
        val said: String,
        val nounPart: String,
        val hints: List<String> = emptyList(),
    ) {
        val phrase: String get() = "$modifier $noun"
        val isOfAPart: Boolean get() = noun == nounPart
    }

    private val lexicon: Lexicon = if (standIn == null) vocabulary.lexicon else Lexicon(
        wordNamed = { page -> if (page == standIn.name) standIn else vocabulary.word(page) },
        structureNamed = { page -> vocabulary.structurePage(page)?.structure },
        tags = vocabulary,
        registries = MinecraftRegistries.worldgen,
    )

    private val agePage: String =
        vocabulary.structurePages.firstOrNull { it.structure == Structure.AGE }?.name ?: error("no 'age' page")

    private fun wordNamed(name: String): Word? = lexicon.word(name)

    private fun takesPages(word: Word): Boolean = lexicon.acceptance(word) != null

    /** The parts of the world, as the pages that take what is laid before them. */
    val partsOfTheWorld: List<Word> by lazy {
        Aspect.entries.mapNotNull { vocabulary.word(it.page) }.filter(::takesPages).distinct()
    }

    /** The authored words that take what is laid before them, but for the parts of the world themselves. */
    private val authoredNouns: List<Word> by lazy {
        vocabulary.authoredWords.filter { Aspect.byPage(it.name) == null && takesPages(it) }
    }

    /** The authored words laid before a noun: every one but the parts of the world and the structure pages. */
    private val authoredModifiers: List<Word> by lazy {
        vocabulary.authoredWords.filter { Aspect.byPage(it.name) == null && vocabulary.structurePage(it.name) == null }
    }

    /** The derived words that take pages, [everyOne] of them or a few of each part of the world they belong to. */
    private fun derivedNouns(everyOne: Boolean): List<Word> {
        val taking = vocabulary.derivedWords.filter(::takesPages)
        if (everyOne) return taking
        return taking.groupBy { lexicon.acceptance(it)?.place?.aspect }.values.flatMap { ofOnePart ->
            val sorted = ofOnePart.sortedBy { it.name }
            val stride = (sorted.size / DERIVED_PER_PART).coerceAtLeast(1)
            sorted.filterIndexed { index, _ -> index % stride == 0 }.take(DERIVED_PER_PART)
        }
    }

    /** Every phrase [word] makes before each noun, with what other words mean where it means nothing. */
    fun before(word: Word, everyDerived: Boolean = false): List<Pairing> {
        val nouns = (partsOfTheWorld + authoredNouns + derivedNouns(everyDerived)).filter { it.name != word.name }
        return nouns.parallelStream().map { noun -> judged(word, noun) }.toList().map { it.copy(hints = hintsFor(it)) }
    }

    /** The one phrase [modifier] makes before the word named [noun], or null where there is no such word. */
    fun phrase(modifier: Word, noun: String): Pairing? =
        wordNamed(noun)?.let { judged(modifier, it) }?.let { it.copy(hints = hintsFor(it)) }

    /**
     * Where [word] takes pages, every authored word before it that means something before [word]'s own part of the
     * world — `dry porous` beside `dry rock` — since one that lands elsewhere before the part does so before anything.
     */
    fun after(word: Word): List<Pairing> {
        val part = lexicon.acceptance(word)?.place?.aspect?.page?.let(::wordNamed) ?: return emptyList()
        fun meansOnThePart(modifier: Word) = modifier.name != word.name && judged(modifier, part).verdict == Verdict.MEANS
        return authoredModifiers.parallelStream().filter(::meansOnThePart).map { modifier -> judged(modifier, word) }.toList()
    }

    /**
     * Every authored word before every part of the world that came out wholly or partly hollow — faults, every one. Not
     * the member words, `springs` or `ores`, whose page is a member of a part rather than a word said of one.
     */
    fun hollowBeforeTheParts(): List<Pairing> {
        fun isAMemberWord(word: Word) = lexicon.acceptance(word)?.place?.members is Members.Named
        fun isHollow(pairing: Pairing) = pairing.verdict == Verdict.HOLLOW || pairing.verdict == Verdict.PARTLY_HOLLOW
        return authoredModifiers.filterNot(::isAMemberWord).parallelStream().flatMap { modifier ->
            partsOfTheWorld.map { part -> judged(modifier, part) }.filter(::isHollow).stream()
        }.toList()
    }

    private data class Built(val tokens: Set<String>, val resolution: Resolution)

    private val baselines = ConcurrentHashMap<Pair<String, Long>, Built?>()

    /** The book of [noun] alone, with a blank page where the modifier would be, so every page keeps its place. */
    private fun baselineFor(noun: Word, seed: Long): Built? = baselines.computeIfAbsent(noun.name to seed) {
        built(listOf(BLANK_PAGE, noun.name, agePage), seed)
    }

    private fun built(pages: List<String>, seed: Long): Built? {
        val reading = Reader.read(lexicon, pages, seed) ?: return null
        val resolution = Writing.resolve(lexicon, vocabulary, reading, seed)
        return Built(resolution.composition.toString().split(' ').toSet(), resolution)
    }

    private val judgements = ConcurrentHashMap<Pair<String, String>, Pairing>()

    /** What [modifier] did before [noun], read and built at several seeds so a meaning left to chance is drawn. */
    private fun judged(modifier: Word, noun: Word): Pairing = judgements.computeIfAbsent(modifier.name to noun.name) {
        judgedAfresh(modifier, noun)
    }

    private fun judgedAfresh(modifier: Word, noun: Word): Pairing {
        val acceptance = lexicon.acceptance(noun)
        val nounPart = acceptance?.place?.aspect?.page ?: noun.name
        fun pairing(verdict: Verdict, said: String) = Pairing(modifier.name, noun.name, verdict, said, nounPart)
        if (acceptance == null) return pairing(Verdict.SILENT, "${noun.name} takes nothing")
        val nounParts = setOfNotNull(acceptance.place.aspect, acceptance.companion?.place?.aspect)
        val pages = listOf(modifier.name, noun.name, agePage)

        val couldLand = mutableListOf<Landing>()
        var wasTakenByTheNoun = false
        var drewOnTheNoun = false
        var cameToNothingEverywhere = true
        var changed: String? = null
        var thePartHeldNothing = true
        // Each effect that landed on the noun: how many books drew it, and in how many it came to nothing.
        val drawn = mutableMapOf<Effect, Int>()
        val lostEveryTime = mutableMapOf<Effect, Int>()
        val clashes = mutableSetOf<String>()
        for (seed in SEEDS) {
            val reading = Reader.read(lexicon, pages, seed) ?: continue
            if (reading.pages[MODIFIER_AT].fate == Fate.TakenBy(NOUN_AT)) wasTakenByTheNoun = true
            couldLand += reading.possible.filter { it.page == MODIFIER_AT }
            val drawnOnTheNoun = reading.landings.filter { it.page == MODIFIER_AT && it.place.aspect in nounParts }
            if (drawnOnTheNoun.isEmpty()) continue
            drewOnTheNoun = true
            val resolution = Writing.resolve(lexicon, vocabulary, reading, seed)
            val baseline = baselineFor(noun, seed) ?: continue
            clashes += resolution.instability.flaws.filter { modifier.name in it.words }.map { it.describe() }
            if (!drawnOnTheNoun.all { it.at in resolution.cameToNothing }) cameToNothingEverywhere = false
            fun lostAt(landing: Landing) = resolution.lostDecisions[landing.at].orEmpty()
            // What one of the word's meanings said and another of its own held is not lost: `inferno` sets the air's
            // motes aimed and laid bare, and the aimed one winning is the word agreeing with itself.
            val heldByTheWord = drawnOnTheNoun.flatMap { landing ->
                landing.effects.filterNot { it.cameToNothingAmong(lostAt(landing)) }.map { it.decision }
            }.toSet()
            // Two of the word's own effects deciding one thing at one place are alternatives, and what came to nothing
            // there is recorded by place and decision, so which of them held cannot be told: neither is judged.
            val decidedTwiceAt = drawnOnTheNoun.flatMap { landing -> landing.effects.map { landing.at to it.decision } }
                .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            for (landing in drawnOnTheNoun) {
                for (effect in landing.effects) {
                    if ((landing.at to effect.decision) in decidedTwiceAt) continue
                    drawn.merge(effect, 1, Int::plus)
                    val lostToItsOwnWord = effect.decision in heldByTheWord
                    if (effect.cameToNothingAmong(lostAt(landing)) && !lostToItsOwnWord) lostEveryTime.merge(effect, 1, Int::plus)
                }
            }
            val tokens = ofTheParts(resolution.composition.toString().split(' ').toSet(), nounParts)
            val tokensBefore = ofTheParts(baseline.tokens, nounParts)
            if (tokensBefore.isNotEmpty() || tokens.isNotEmpty()) thePartHeldNothing = false
            if (changed == null && tokens != tokensBefore) changed = difference(tokensBefore, tokens)
        }

        val onTheNoun = couldLand.filter { it.place.aspect in nounParts }
        val elsewhere = couldLand.map { it.place.aspect }.filterNot { it in nounParts }.map { it.page }.toSortedSet()
        val changedSomething = changed != null && !cameToNothingEverywhere
        return when {
            onTheNoun.isEmpty() && couldLand.isEmpty() ->
                pairing(Verdict.SILENT, if (wasTakenByTheNoun) "taken, lands nothing" else "not taken")
            onTheNoun.isEmpty() -> pairing(Verdict.STRAYS, "lands only on ${elsewhere.joinToString(" ")}")
            clashes.isNotEmpty() -> pairing(Verdict.CLASHES, clashes.joinToString("; "))
            changedSomething && neverHeld(drawn, lostEveryTime).isNotEmpty() -> pairing(
                Verdict.PARTLY_HOLLOW,
                "${changed.orEmpty()}; but came to nothing: ${neverHeld(drawn, lostEveryTime).joinToString(", ", transform = ::effectSaid)}",
            )
            changedSomething -> pairing(Verdict.MEANS, changed.orEmpty())
            !drewOnTheNoun -> pairing(Verdict.SILENT, "could land, but was drawn in none of ${SEEDS.count()} books")
            thePartHeldNothing -> pairing(Verdict.SILENT, "said of ${noun.name}, which holds nothing alone")
            else -> pairing(Verdict.HOLLOW, hollowBecause(cameToNothingEverywhere, onTheNoun))
        }
    }

    /** Every effect that came to nothing in every book that drew it. */
    private fun neverHeld(drawn: Map<Effect, Int>, lost: Map<Effect, Int>): List<Effect> =
        drawn.filter { (effect, times) -> lost[effect] == times }.keys.toList()

    private fun hollowBecause(cameToNothingEverywhere: Boolean, onTheNoun: List<Landing>): String {
        val what = onTheNoun.flatMap { it.effects }.distinct().joinToString(", ", transform = ::effectSaid)
        val why = if (cameToNothingEverywhere) "came to nothing" else "changed nothing"
        return "$why: $what"
    }

    /** The tokens of a composition's spelling that are about [parts]: `sea=water`, `sea.depth=0.4`, `sea_named=true`. */
    private fun ofTheParts(tokens: Set<String>, parts: Set<Aspect>): Set<String> {
        val pages = parts.map { it.page }
        fun isAbout(token: String) = pages.any { page -> token.startsWith(page) && token.getOrNull(page.length) in PAGE_ENDS }
        return tokens.filter(::isAbout).toSet()
    }

    /** The tokens [after] holds that [before] did not, and the reverse, as a short line. */
    private fun difference(before: Set<String>, after: Set<String>): String {
        val added = (after - before).sorted().map { "+$it" }
        val removed = (before - after).sorted().map { "-$it" }
        val all = added + removed
        val shown = all.take(TOKENS_SHOWN).joinToString(" ")
        return if (all.size > TOKENS_SHOWN) "$shown …" else shown
    }

    /**
     * What other words mean on a noun where this phrase means nothing: the words that set or choose what this one does
     * elsewhere — its siblings, a mirror among them where it pulls the other way — and what they come to before the
     * same noun.
     */
    private fun hintsFor(pairing: Pairing): List<String> {
        if (!pairing.verdict.asksForAPerson || pairing.verdict == Verdict.HOLLOW) return emptyList()
        val modifier = wordNamed(pairing.modifier) ?: return emptyList()
        val noun = wordNamed(pairing.noun) ?: return emptyList()
        val own = dialsOf(modifier)
        if (own.isEmpty()) return emptyList()
        // The mirror first, then the siblings that share the most with this word.
        fun closeness(sibling: Word) = Pair(!pullsAgainst(modifier, sibling), -dialsOf(sibling).count(own::contains))
        return siblingsOf(modifier, own).sortedWith(compareBy({ closeness(it).first }, { closeness(it).second })).mapNotNull { sibling ->
            val theirs = judged(sibling, noun)
            if (theirs.verdict != Verdict.MEANS) return@mapNotNull null
            val mirror = if (pullsAgainst(modifier, sibling)) " (its mirror)" else ""
            "${sibling.name} ${noun.name}$mirror: ${theirs.said}"
        }.take(HINTS_SHOWN)
    }

    /** The parameters a word sets or bends and the parts it chooses on — what makes another word its sibling. */
    private fun dialsOf(word: Word): Set<String> = word.meanings.flatMap { it.effects }.mapNotNull { effect ->
        when (effect) {
            is Effect.Sets -> effect.parameter.name
            is Effect.Bends -> effect.parameter.name
            is Effect.Chooses -> "${CHOOSES_ON}${effect.aspect.page}"
            else -> null
        }
    }.toSet()

    private val siblingCache = ConcurrentHashMap<String, List<Word>>()

    private fun siblingsOf(word: Word, own: Set<String>): List<Word> = siblingCache.computeIfAbsent(word.name) {
        authoredModifiers.filter { it.name != word.name && dialsOf(it).any(own::contains) }
    }

    /** Whether [sibling] sets a parameter [word] sets toward the other end of it — `low` against `high`. */
    private fun pullsAgainst(word: Word, sibling: Word): Boolean {
        val mine = leaningsOf(word)
        val theirs = leaningsOf(sibling)
        return mine.any { (parameter, sign) -> theirs[parameter]?.let { it == -sign } == true }
    }

    /** Which way each parameter a word sets or bends is pulled: up, down, or neither. */
    private fun leaningsOf(word: Word): Map<String, Int> = word.meanings.flatMap { it.effects }.mapNotNull { effect ->
        when (effect) {
            is Effect.Sets -> effect.parameter.name to signOf(effect.value)
            is Effect.Bends -> effect.parameter.name to signOf(effect.value)
            else -> null
        }
    }.filter { it.second != 0 }.toMap()

    /** Up, down or neither, read off a value written as a range, a shift or a number: `0.5..1.0`, `-0.4`, `+0.3`. */
    private fun signOf(value: String): Int {
        val ends = value.split(RANGE).mapNotNull { it.trim().toDoubleOrNull() }
        if (ends.isEmpty()) return 0
        val middle = ends.average()
        return when {
            middle > 0.0 -> 1
            middle < 0.0 -> -1
            else -> 0
        }
    }

    private fun effectSaid(effect: Effect): String = when (effect) {
        is Effect.Sets -> "sets ${effect.parameter.spelled}=${effect.value}"
        is Effect.Bends -> "bends ${effect.parameter.spelled} ${effect.value}"
        is Effect.Chooses -> "chooses ${effect.aspect.page} ${effect.member}"
        is Effect.Leans -> "leans ${effect.aspect.page} ${effect.weights.keys.joinToString(" ")}"
        else -> effect.javaClass.simpleName.replaceFirstChar(Char::lowercase)
    }

    companion object {
        /** Where a person's rulings are kept, one phrase a line: `phrase | verdict | ruling | note`. */
        val RULINGS = File(".authoring/pairings-ruled.txt")

        /** Where a word's report is written. */
        fun reportFor(word: String): File = File(".authoring/pairings/$word.md")

        /** A page no word answers to, laid where the modifier stands in the baseline so every page keeps its place. */
        private const val BLANK_PAGE = "zzz_pairing_blank"

        private const val MODIFIER_AT = 0
        private const val NOUN_AT = 1

        /** Enough books that a meaning at a fair chance is drawn in at least one. */
        private val SEEDS = 1L..8L

        private const val DERIVED_PER_PART = 3
        private const val TOKENS_SHOWN = 6
        private const val HINTS_SHOWN = 3
        private const val CHOOSES_ON = "chooses:"
        private const val RANGE = ".."

        /** What may follow a part's page in a token of the composition's spelling. */
        private val PAGE_ENDS = setOf('=', '.', '_')
    }
}
