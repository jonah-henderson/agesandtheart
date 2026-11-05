package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.language.Fate
import co.voik.agesandtheart.age.language.Reading
import co.voik.agesandtheart.age.language.Structure
import co.voik.agesandtheart.age.language.Word
import co.voik.agesandtheart.age.word.Vocabulary
import java.io.File
import kotlin.random.Random

/**
 * Phrases a writer could reach for that land nothing on what they name: `eroded features`, before the features could
 * be worn. Every authored word is read before every part of the world, and before each member word too where asked;
 * then random sentences of a few clauses, for a word that lands on its own but not beside another.
 *
 * Whether a phrase reads as English is not something this can tell, so [REVIEWED] holds the ones a person has ruled
 * out — `word noun`, `word *` or `* noun` — and every later run leaves them out.
 */
class InertPhrases(private val vocabulary: Vocabulary) {

    /** Why a word landed nothing on the noun after it. */
    enum class Why(val said: String) {
        NOT_TAKEN("not taken"),
        TAKEN_SAYS_NOTHING("taken, lands nothing"),
        LANDS_ELSEWHERE("lands only elsewhere"),
    }

    /** A word that landed nothing on [noun], and where it landed instead. */
    data class Gap(val word: String, val noun: String, val why: Why, val landsOn: Set<Aspect>, val sentence: String) {
        val phrase: String get() = "$word $noun"
    }

    private val agePage = structurePage(Structure.AGE)
    private val andPage = structurePage(Structure.AND)

    private fun structurePage(structure: Structure): String =
        vocabulary.structurePages.firstOrNull { it.structure == structure }?.name ?: error("no '${structure.production}' page")

    /** The authored words a writer lays before a noun: every one but the parts of the world themselves. */
    val modifiers: List<Word> by lazy {
        vocabulary.authoredWords.filter { Aspect.byPage(it.name) == null && vocabulary.structurePage(it.name) == null }
    }

    /** The modifiers that take nothing themselves: the adjectives, which only ever wait for a noun. */
    private val adjectives: List<Word> by lazy { modifiers.filter { vocabulary.lexicon.acceptance(it) == null } }

    /** The modifiers that take pages too, as `eroded` or `reefs` do, said of the part of the world after them. */
    private val takingModifiers: List<Word> by lazy { modifiers.filter { vocabulary.lexicon.acceptance(it) != null } }

    /** The parts of the world, as the pages that take what is laid before them. */
    val partsOfTheWorld: List<Word> by lazy {
        Aspect.entries.mapNotNull { vocabulary.word(it.page) }.filter { vocabulary.lexicon.acceptance(it) != null }.distinct()
    }

    /** Every other word that takes what is laid before it: a creature, a feature, a biome, a structure. */
    val members: List<Word> by lazy {
        fun isAPart(word: Word) = Aspect.byPage(word.name) != null
        vocabulary.words.filter { !isAPart(it) && vocabulary.lexicon.acceptance(it) != null }
    }

    /** Every modifier before every noun in [nouns], in a book of its own. */
    fun pairs(nouns: List<Word>): List<Gap> = modifiers.flatMap { modifier ->
        nouns.mapNotNull { noun ->
            val pages = listOf(modifier.name, noun.name, agePage)
            val reading = vocabulary.read(pages, SEED) ?: return@mapNotNull null
            judge(reading, page = 0, noun = noun, nounAt = 1)?.let { (why, landsOn) ->
                Gap(modifier.name, noun.name, why, landsOn, pages.joinToString(" "))
            }
        }
    }

    /**
     * [count] random sentences of one to three clauses, and every word in them that landed nothing on its noun though it
     * does alone ([aloneGaps] holds the phrases that land nothing alone, which are not news here). A clause is one or two
     * adjectives and a noun drawn from [nouns]; before a part of the world, a word that takes pages may stand last, as
     * `eroded` does in `empty eroded features`.
     */
    fun sentences(nouns: List<Word>, count: Int, seed: Long, aloneGaps: Set<String>): List<Gap> {
        val random = Random(seed)
        val found = mutableListOf<Gap>()
        repeat(count) {
            val clauses = List(random.nextInt(1, MOST_CLAUSES + 1)) {
                val noun = nouns.random(random)
                val adjectivesLaid = List(random.nextInt(1, MOST_MODIFIERS + 1)) { adjectives.random(random) }
                // Only a word that lands on this part alone, so what is found is the adjective's doing.
                val belonging = takingModifiers.filter { "${it.name} ${noun.name}" !in aloneGaps }
                val beforeAPart = noun in partsOfTheWorld && belonging.isNotEmpty() && random.nextDouble() < TAKING_MODIFIER_SHARE
                val laidBefore = if (beforeAPart) adjectivesLaid.take(1) + belonging.random(random) else adjectivesLaid
                laidBefore to noun
            }
            val pages = clauses.flatMapIndexed { index, (laidBefore, noun) ->
                val joining = if (index > 0) listOf(andPage) else emptyList()
                joining + laidBefore.map { it.name } + noun.name
            } + agePage
            val reading = vocabulary.read(pages, random.nextLong()) ?: return@repeat
            var at = 0
            for ((index, clause) in clauses.withIndex()) {
                if (index > 0) at++
                val (laidBefore, noun) = clause
                val nounAt = at + laidBefore.size
                for ((offset, modifier) in laidBefore.withIndex()) {
                    val phrase = "${modifier.name} ${noun.name}"
                    if (phrase in aloneGaps) continue
                    judge(reading, page = at + offset, noun = noun, nounAt = nounAt)?.let { (why, landsOn) ->
                        found += Gap(modifier.name, noun.name, why, landsOn, pages.joinToString(" "))
                    }
                }
                at = nounAt + 1
            }
        }
        return found
    }

    /** Why the page at [page] landed nothing on [noun] at [nounAt], and where it did land, or null where it landed there. */
    private fun judge(reading: Reading, page: Int, noun: Word, nounAt: Int): Pair<Why, Set<Aspect>>? {
        val acceptance = vocabulary.lexicon.acceptance(noun) ?: return null
        val nounParts = setOfNotNull(acceptance.place.aspect, acceptance.companion?.place?.aspect)
        // A word that took the pages before it lands through them, as `villagers` does in `small villagers`.
        val landed = reading.possible.filter { it.page == page || it.clause == page }
        if (landed.any { it.place.aspect in nounParts }) return null
        val landsOn = landed.map { it.place.aspect }.toSet()
        val takenByTheNoun = reading.pages[page].fate == Fate.TakenBy(nounAt)
        val why = when {
            !takenByTheNoun -> Why.NOT_TAKEN
            landed.isEmpty() -> Why.TAKEN_SAYS_NOTHING
            else -> Why.LANDS_ELSEWHERE
        }
        return why to landsOn
    }

    companion object {
        /** The phrases a person has ruled out, one per line: `word noun`, `word *` or `* noun`; `#` starts a comment. */
        val REVIEWED = File(".authoring/inert-reviewed.txt")

        /** Where the report is written. */
        val REPORT = File(".authoring/inert-phrases.md")

        private const val SEED = 1L
        private const val MOST_CLAUSES = 3
        private const val MOST_MODIFIERS = 2

        /** How often a clause before a part of the world ends on a word that takes pages, as `eroded features` does. */
        private const val TAKING_MODIFIER_SHARE = 0.3

        /** What [REVIEWED] rules out, as a test of a phrase. */
        fun reviewed(): (Gap) -> Boolean {
            val lines = if (REVIEWED.isFile) REVIEWED.readLines() else emptyList()
            val ruled = lines.map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
                .map { it.split(Regex("\\s+")) }.filter { it.size == 2 }.map { (word, noun) -> word to noun }
            return { gap ->
                ruled.any { (word, noun) ->
                    val wordMatches = word == "*" || word == gap.word
                    val nounMatches = noun == "*" || noun == gap.noun
                    wordMatches && nounMatches
                }
            }
        }
    }
}
