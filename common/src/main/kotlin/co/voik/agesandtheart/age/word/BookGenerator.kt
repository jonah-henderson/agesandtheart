package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.grammar.Production
import kotlin.random.Random

/**
 * The authoring pipeline run backwards: a vocabulary and a seed in, a **well-formed book** out
 * (design §4.5).
 *
 * It exists twice over, and that is the point. As content it is what writes the found Descriptive Books a
 * player learns grammar from — you cannot acquire subordination without holding a book that subordinates —
 * and as a tool it is the only practical way to test a system this size, since thousands of generated books
 * can be asserted to parse, cohere and resolve. Content requirement and testing requirement are one build.
 *
 * **Well-formed here means two things**: every page is read (nothing is dropped), and the book does not
 * contradict itself. Both are properties of *how the words were chosen*, not of the parser, so this avoids
 * the four ways a sentence goes wrong rather than checking afterwards:
 *
 * - one narrowing word per section, so no aspect is asked to be two things it cannot reconcile;
 * - words with opposed tags never share a book, consulting the same antonym table the resolver prices with;
 * - a word is only ever laid under a section it speaks to, so nothing is misaimed;
 * - and a narrowing word is only chosen where the world can satisfy it, so nothing is unbacked.
 *
 * The bar rises when these become real content in Phase 7 — an exemplar a player learns from cannot be
 * merely legal — so what is here is the floor rather than the finished thing.
 */
object BookGenerator {
    /** How many parts of the world one book talks about. Enough to need sections; short enough to read. */
    private val SECTIONS = 1..3

    private const val CHANCE_OF_A_DESCRIPTOR = 0.4
    private const val CHANCE_OF_A_SETTER = 0.5
    private const val CHANCE_OF_JOINING_A_SECOND_VALUE = 0.35

    /** The pages of a book that says something coherent about the world, in the order they are laid out. */
    fun write(vocabulary: Vocabulary, seed: Long): List<String> {
        val random = Random(seed)
        val written = Written()
        val wanted = random.nextInt(SECTIONS.first, SECTIONS.last + 1)
        val pages = mutableListOf<String>()
        var sectionsWritten = 0
        // Every aspect is tried, not just the first few drawn: a section that turned out to have nothing to
        // say is skipped rather than written empty, so drawing exactly `wanted` aspects up front would let
        // a book come out shorter than asked — and, at the limit, blank.
        for (aspect in Aspect.entries.filter { canBeWrittenAbout(vocabulary, it) }.shuffled(random)) {
            if (sectionsWritten == wanted) break
            val section = sectionAbout(vocabulary, aspect, random, written)
            if (section.isEmpty()) continue
            pages += section
            sectionsWritten++
        }
        return pages
    }

    /**
     * What the book has said so far. Tags because a tension crosses section boundaries — two words pulling
     * opposite ways contradict each other wherever they sit — and words because a page repeated says
     * nothing the first one did not, and an exemplar that stutters teaches the stutter.
     */
    private class Written {
        val tagsAsked = mutableSetOf<String>()
        val wordsUsed = mutableSetOf<String>()

        fun accepts(vocabulary: Vocabulary, word: Word): Boolean {
            if (word.name in wordsUsed) return false
            return word.wanted.none { tag -> tagsAsked.any { vocabulary.opposition(tag, it) != null } }
        }

        fun take(word: Word): String {
            tagsAsked += word.wanted
            wordsUsed += word.name
            return word.name
        }
    }

    /** Whether a book can say anything about this aspect: it needs the page that aims at it, at least. */
    private fun canBeWrittenAbout(vocabulary: Vocabulary, aspect: Aspect): Boolean =
        aimingPageFor(vocabulary, aspect) != null

    private fun aimingPageFor(vocabulary: Vocabulary, aspect: Aspect): Word? =
        vocabulary.words.firstOrNull { it.aims && it.aspects == setOf(aspect) }

    /**
     * One section: what it is about, then what is true of it.
     *
     * **A section that says nothing is not written at all.** An aiming page with nothing after it costs a
     * page, costs ink and changes no world — and as an exemplar it teaches a beginner to spend a rare page
     * on silence.
     */
    private fun sectionAbout(vocabulary: Vocabulary, aspect: Aspect, random: Random, written: Written): List<String> {
        val aimingPage = aimingPageFor(vocabulary, aspect) ?: return emptyList()

        val narrowing = vocabulary.words.filter { word ->
            val speaksHere = aspect in word.aspects && word.tier.narrows && word.constrainsPresetsIn(aspect)
            speaksHere && vocabulary.carriersOf(word, aspect).isNotEmpty() && written.accepts(vocabulary, word)
        }
        // At most one, so the aspect is never asked to be two things it cannot be at once (§3.4).
        val subject = narrowing.randomOrNull(random)
        // Always steer where nothing narrows, or an aspect with no word about *which* preset fills it —
        // an open one, whose values are registry ids — would be skipped half the time for no reason.
        val steers = subject == null || random.nextDouble() < CHANCE_OF_A_SETTER
        val steering = if (steers) steering(vocabulary, aspect, random, written) else emptyList()
        if (subject == null && steering.isEmpty()) return emptyList()

        // **The subject is taken before a descriptor is chosen, not while the pages are assembled.** A
        // descriptor precedes its subject on the page but is picked against everything the book has already
        // committed to — chosen first, `savage` was free to walk in beside `ordered` and contradict it.
        val subjectPage = subject?.let(written::take)
        val describes = random.nextDouble() < CHANCE_OF_A_DESCRIPTOR
        val descriptor = if (!describes) null else {
            vocabulary.words.filter { it.tier == Tier.EVOCATIVE && written.accepts(vocabulary, it) }
                .randomOrNull(random)
        }

        return listOfNotNull(descriptor?.let(written::take), written.take(aimingPage), subjectPage) + steering
    }

    /**
     * A parameter of this aspect, given one value or two joined by `and`. One parameter per section, since
     * two unjoined claims on the same one contend and one of them is displaced (§3.2) — which would be a
     * contradiction the book never meant.
     */
    private fun steering(vocabulary: Vocabulary, aspect: Aspect, random: Random, written: Written): List<String> {
        val setters = vocabulary.words.filter { word ->
            val steersThisAspect = aspect in word.aspects && word.sets.keys.any { vocabulary.turnsAKnob(aspect, it) }
            steersThisAspect && written.accepts(vocabulary, word)
        }
        val parameter = setters.flatMap { it.sets.keys }.distinct().randomOrNull(random) ?: return emptyList()
        val values = setters.filter { parameter in it.sets }.shuffled(random)
        val first = values.firstOrNull() ?: return emptyList()

        val second = values.drop(1).firstOrNull()
        val joins = second != null && random.nextDouble() < CHANCE_OF_JOINING_A_SECOND_VALUE
        // Joined rather than juxtaposed: `and` means keep both, where standing side by side means one of
        // them loses. A generated book must never demonstrate the second by accident.
        val joiner = vocabulary.grammarWords.firstOrNull { it.production == Production.CONJUNCTION }
        if (!joins || joiner == null || second == null) return listOf(written.take(first))
        return listOf(written.take(first), joiner.name, written.take(second))
    }
}
