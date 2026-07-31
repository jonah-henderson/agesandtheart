package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.age.aspect.Density
import co.voik.agesandtheart.age.word.Tier
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.Word

/**
 * What class of thing a page is, as far as the structure is concerned — the grammar's terminals, and why
 * it stays small while the vocabulary grows without bound. A modpack adding forty thousand block words
 * adds no terminals.
 */
enum class PageClass {
    /** Tilts weights and never narrows, so it precedes a subject and colours it (design §4.4). */
    EVOCATIVE,

    /**
     * Names a part of the world and supplies no value — `landmass`, `climate`. It *opens* a section, and
     * aiming is the whole of its job (design §4.3.1).
     */
    SUBJECT,

    /** Chooses which preset fills an aspect. Attaches to whatever section it sits in. */
    PRESET,

    /** Steers a preset's parameter — a material, a population. Attaches to whatever section it sits in. */
    SETTER,

    /** `and`, `only`, `except`, and the rungs — structure rather than content. */
    JOINER,
    RESTRICTOR,
    EXCLUDER,
    QUANTIFIER,
}

/**
 * One page of a book as the parser sees it: what was written, and what the Art makes of it.
 *
 * [word] is null for a structural page and for one nobody recognises — the two are told apart by [kind],
 * which is null only in the second case.
 */
data class Page(
    val written: String,
    val kind: PageClass?,
    val word: Word? = null,
    /** The rung a [PageClass.QUANTIFIER] page names — the one structural page that carries a value. */
    val rung: Density? = null,
)

/**
 * Pages in, a [Sentence] out — **the whole of the port** (design §4.3.1). Everything on this side is ours,
 * and `GrammarCheck` fails the build if any file but [ArtGrammar] imports the parser, so replacing the
 * parser means rewriting one file.
 */
object Grammar {
    /**
     * The book [pages] spell, at whatever the vocabulary currently says those pages mean.
     *
     * **Never refuses.** A page nobody recognises is dropped and reported, and the Age comes out vaguer
     * (§4.3) — design §2 forbids the pen validating a sentence, since that would make precision risk-free.
     */
    fun read(vocabulary: Vocabulary, pages: List<String>): Sentence =
        ArtGrammar.parse(pages.map { written -> classify(vocabulary, written) })

    /** What the Art makes of one page — its class, and the word behind it where there is one. */
    private fun classify(vocabulary: Vocabulary, written: String): Page {
        vocabulary.grammarWord(written)?.let { structural ->
            // A production nobody has unlocked reads as an unknown page rather than an error, so the
            // fragment becomes vagueness like any other.
            if (!structural.production.available) return Page(written, kind = null)
            return Page(written, kind = structural.production.pageClass, rung = structural.rung)
        }
        val word = vocabulary.word(written) ?: return Page(written, kind = null)
        return Page(written, kind = word.pageClass, word = word)
    }

    /**
     * Which class an ordinary word belongs to.
     *
     * An **aiming page** is recognised by shape rather than by a flag: a word that asks for no tag, names
     * no preset and sets no parameter says nothing except which part of the world it is about, and that is
     * exactly what a subject page *is*. Anything else is a modifier, split by [Word.constrainsPresets] —
     * a word with a query or a name has an opinion about *which* preset fills an aspect, where one that
     * only sets a parameter has an opinion about how that preset is made.
     */
    private val Word.pageClass: PageClass
        get() = when {
            aims -> PageClass.SUBJECT
            tier == Tier.EVOCATIVE -> PageClass.EVOCATIVE
            constrainsPresets -> PageClass.PRESET
            else -> PageClass.SETTER
        }

    private val Production.pageClass: PageClass
        get() = when (this) {
            Production.CONJUNCTION -> PageClass.JOINER
            Production.RESTRICTION -> PageClass.RESTRICTOR
            Production.EXCEPTION -> PageClass.EXCLUDER
            Production.QUANTIFICATION -> PageClass.QUANTIFIER
        }
}
