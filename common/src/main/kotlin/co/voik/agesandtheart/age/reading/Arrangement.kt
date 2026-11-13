package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.age.LinkAnchor
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.words.CoreConcepts
import co.voik.agesandtheart.age.words.Sense
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.age.words.Words
import net.minecraft.resources.Identifier

/**
 * A word, or words `and` joined, with what the structure words around it said of it. Offers travel over terms, and a
 * joined term's words share what reaches them.
 */
data class Term(
    val pages: List<Int>,
    val polarity: Polarity = Polarity.ASSERTED,
    val sitedIn: Identifier? = null,
    val everywhere: Boolean = false,
    /** Whether an `of` turned this round to stand after the word it is said of: `herds` in `herds of warden`. */
    val saidOfWhatFollows: Boolean = false,
    val isAge: Boolean = false,
) {
    /** The page of the `and` group, where `and` joined several. */
    val joined: Int? get() = pages.first().takeIf { pages.size > 1 }
}

/** A row of pages arranged into terms by its structure words, before anything travels. */
data class Arrangement(val terms: List<Term>, val fates: Map<Int, Fate>, val arrival: LinkAnchor?) {
    companion object {
        fun of(pages: List<String>, words: Words): Arrangement {
            val arranging = Arranging(pages, words)
            var at = 0
            while (at < pages.size) {
                val word = arranging.word(at)
                val structure = arranging.structureAt(at)
                at += when {
                    word == null -> 1.also { arranging.fates[at] = Fate.Unknown }
                    structure != null -> 1 + structure.arrange(at, arranging)
                    else -> 1.also { arranging.addWord(at) }
                }
            }
            arranging.finish()
            return Arrangement(arranging.terms.toList(), arranging.fates.toMap(), arranging.arrival)
        }
    }
}

/** The arranging under way, as the structure words see it. */
class Arranging internal constructor(val pages: List<String>, private val words: Words) {
    val terms = mutableListOf<Term>()
    val fates = mutableMapOf<Int, Fate>()
    var arrival: LinkAnchor? = null
    private var pendingPolarity: Pair<Int, Polarity>? = null

    fun word(at: Int): Word? = pages.getOrNull(at)?.let(words::word)

    fun senseAt(at: Int): Sense? = word(at)?.defaultSense

    fun structureAt(at: Int): StructureWord? = senseAt(at)?.behaviour?.let(StructureWords::named)

    /** Whether the page at [at] is a word that is neither unknown nor a structure word. */
    fun isPlainWord(at: Int): Boolean = word(at) != null && structureAt(at) == null

    fun lastTerm(): Term? = terms.lastOrNull()

    fun replaceLast(term: Term) {
        terms[terms.lastIndex] = term
    }

    fun binds(vararg pages: Int) = pages.forEach { fates[it] = Fate.Structure }

    fun dangles(at: Int) {
        fates[at] = Fate.Dangles
    }

    fun awaitThing(at: Int, polarity: Polarity) {
        pendingPolarity?.let { (waiting, _) -> dangles(waiting) }
        pendingPolarity = at to polarity
        binds(at)
    }

    fun addAge(at: Int) {
        pendingPolarity?.let { (waiting, _) -> dangles(waiting) }
        pendingPolarity = null
        terms += Term(listOf(at), isAge = true)
        binds(at)
    }

    /**
     * A plain word, as a term of its own, taking any polarity waiting for it: a thing takes it, and a block takes `no` as
     * a ban (`no snow`). Before anything else, the polarity binds nothing.
     */
    fun addWord(at: Int) {
        val waiting = pendingPolarity
        pendingPolarity = null
        val sense = senseAt(at)
        val polarity = waiting?.let { (from, polarity) ->
            val isAThing = sense?.endsAPhrase == true
            val offersOnlyBlocks = sense != null && sense.offers.isNotEmpty() && sense.offers.all { it.concept == CoreConcepts.BLOCK }
            val isABannedBlock = polarity == Polarity.EXCEPT && offersOnlyBlocks
            if (isAThing || isABannedBlock) polarity else null.also { dangles(from) }
        } ?: Polarity.ASSERTED
        terms += Term(listOf(at), polarity = polarity)
    }

    internal fun finish() {
        pendingPolarity?.let { (waiting, _) -> dangles(waiting) }
    }

    /** The biome [at] names, where its word is one: the one member of the biomes it chooses. */
    fun biomeNamedAt(at: Int): Identifier? {
        val choice = senseAt(at)?.means?.singleOrNull() as? Effect.Chooses ?: return null
        return if (choice.aspect == Aspect.BIOMES) Identifier.tryParse(choice.member) else null
    }
}
