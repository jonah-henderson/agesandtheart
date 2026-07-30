package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.age.word.Tier
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.Word

/**
 * What class of thing a page is, as far as the structure is concerned.
 *
 * The grammar's terminals, and the reason it can stay small while the vocabulary grows without bound: a
 * page is looked up once here and thereafter is only its class. A modpack adding forty thousand block words
 * adds no terminals, exactly as a language does not gain a keyword when you declare a variable.
 */
enum class PageClass {
    /** Tilts weights and never narrows, so it precedes a subject and colours it (design §4.4). */
    EVOCATIVE,

    /** Chooses which preset fills a aspect, so it *opens* a section. */
    PRESET,

    /** Steers a preset's parameter — a material, a population. Attaches to whatever section it sits in. */
    SETTER,

    /** `and`, `only`, `except` — structure rather than content. */
    JOINER,
    RESTRICTOR,
    EXCLUDER,
}

/**
 * One page of a book as the parser sees it: what was written, and what the Art makes of it.
 *
 * [word] is null for a structural page and for one nobody recognises — the two are told apart by [kind],
 * which is null only in the second case.
 */
data class Page(val written: String, val kind: PageClass?, val word: Word? = null)

/**
 * Pages in, a [Sentence] out — **the whole of the port** (design §4.3.1).
 *
 * Everything on this side of it is ours: [Page] going in, [Constraint] and [Scope] coming out. The parser
 * behind it is an implementation detail, and `GrammarCheck` fails the build if any file but
 * [ArtGrammar] so much as imports it. So replacing the parser means rewriting one file, which is the only
 * kind of "abstraction layer" worth having — a narrow boundary with a single implementation, rather than a
 * plugin point for a future that may never arrive.
 *
 * The same shape `Palette` takes over vanilla's surface rules: wrap it, expose an opinionated subset, and
 * say why in one place.
 */
object Grammar {
    /**
     * The book [pages] spell, at whatever the vocabulary currently says those pages mean.
     *
     * **Never refuses.** A page nobody recognises is dropped and reported, and the Age comes out vaguer for
     * it (§4.3); design §2 forbids the pen validating a sentence, because validation would make precision
     * risk-free.
     */
    fun read(vocabulary: Vocabulary, pages: List<String>): Sentence =
        ArtGrammar.parse(pages.map { written -> classify(vocabulary, written) })

    /** What the Art makes of one page — its class, and the word behind it where there is one. */
    private fun classify(vocabulary: Vocabulary, written: String): Page {
        vocabulary.grammarWord(written)?.let { structural ->
            // A production nobody has unlocked reads as an unknown page rather than as an error: the writer
            // holds a symbol they cannot yet use, and the fragment becomes vagueness like any other.
            if (!structural.production.available) return Page(written, kind = null)
            return Page(written, kind = structural.production.pageClass)
        }
        val word = vocabulary.word(written) ?: return Page(written, kind = null)
        return Page(written, kind = word.pageClass, word = word)
    }

    /**
     * Which class an ordinary word belongs to.
     *
     * The line between a subject and a modifier is [Word.constrainsPresets], which already existed and
     * already means the right thing: a word with a query or a name has an opinion about *which* preset
     * fills a aspect, and one that only sets a parameter has an opinion about how that preset is made. So the
     * grammar's most load-bearing distinction costs no new field on a word.
     */
    private val Word.pageClass: PageClass
        get() = when {
            tier == Tier.EVOCATIVE -> PageClass.EVOCATIVE
            constrainsPresets -> PageClass.PRESET
            else -> PageClass.SETTER
        }

    private val Production.pageClass: PageClass
        get() = when (this) {
            Production.CONJUNCTION -> PageClass.JOINER
            Production.RESTRICTION -> PageClass.RESTRICTOR
            Production.EXCEPTION -> PageClass.EXCLUDER
        }
}
