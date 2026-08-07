package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.word.Tier
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.Word

/**
 * What class of thing a page is, as far as the structure is concerned — the grammar's terminals.
 *
 * **A class, plus for most of them an aspect**, which is what the grammar needs to keep a term under a
 * subject it belongs to. The vocabulary still grows without bound underneath: a modpack adding forty
 * thousand block words adds no classes, because every one of them is a [MATERIAL].
 */
enum class PageClass {
    /**
     * `Age` — what the whole book is about, and the one page every book must have.
     *
     * Structure rather than content: it says nothing about the world, it gives the sentence a head. A
     * writer who has only evocative pages still writes a sentence rather than a heap, every descriptor has
     * something to describe, and a term that cannot join its section has nowhere to quietly go instead.
     */
    NUCLEUS,

    /**
     * Names a part of the world and supplies no value — `landmass`, `climate`. It *opens* a section, and
     * aiming is the whole of its job (design §4.3.1).
     */
    SUBJECT,

    /**
     * A block: something a part of the world can be **made of**, rather than something one part is about.
     *
     * Its own class because being made of a substance is shared — `Sea` is an open aspect whose value *is*
     * a block, and a terrain wears one through its `stone` parameter — so `a sea of ice` and `land of
     * blackstone` are both sentences and the *section* decides which is meant. Deciding it by section is
     * what keeps that from being a word reaching out of its clause.
     */
    MATERIAL,

    /** Anything else a writer says about a section: a preset to fill it, or a parameter to steer it. */
    TERM,

    /** `and`, `only`, `except`, and the rungs — structure rather than content. */
    JOINER,
    RESTRICTOR,
    EXCLUDER,
    QUANTIFIER,
    CONFINER,
}

/**
 * One page of a book as the parser sees it: what was written, and what the Art makes of it.
 *
 * [word] is null for a structural page and for one nobody recognises — the two are told apart by [kind],
 * which is null only in the second case. [aspect] is the part of the world a [PageClass.SUBJECT] opens or
 * a [PageClass.TERM] belongs to, and null for every class that belongs to no single one.
 */
data class Page(
    val written: String,
    val kind: PageClass?,
    val word: Word? = null,
    val aspect: Aspect? = null,
    /** The structure a structural page spells, and null for a page that carries a word instead. */
    val production: Production? = null,
    /** The rung a [PageClass.QUANTIFIER] page names — the one structural page that carries a value. */
    val rung: Double? = null,
    /** Whether [Repair] drew this page rather than the writer laying it. */
    val latent: Boolean = false,
    /** Whether the writer laid this page where it could not be read, so [Repair] moved it. */
    val rehomed: Boolean = false,
)

/**
 * Pages in, a [Sentence] out — **the whole of the port** (design §4.3.1). Everything on this side is ours,
 * and `GrammarCheck` fails the build if any file but [ArtGrammar] imports the parser, so replacing the
 * parser means rewriting one file.
 */
object Grammar {
    /**
     * The book [pages] spell, or **null where they are not a book at all** — which happens for exactly one
     * reason, and it is the only refusal in the Art.
     *
     * **Design §2's one exception: a book must carry the `age` page.** Everything else the pen forgives —
     * a book that does not read is not an error but a [Repair], where the Art writes a sentence of its own
     * and lays the writer's pages into it, and a page nobody recognises is dropped and reported and the
     * Age comes out vaguer (§4.3). Naming the thing you are making is different in kind: without it an
     * empty book is a free reroll on a random Age, and the ink, the paper and the one piece of grammar are
     * the ante for playing at all (Jonah, 2026-08-07).
     *
     * Callers refuse rather than repair. Repair is for a book that said what it was and said the rest
     * badly.
     */
    fun read(vocabulary: Vocabulary, pages: List<String>): Sentence? {
        val laid = pages.map { written -> classify(vocabulary, written) }
        // A page nobody recognises never reaches the parser: it has no class to be read as, and letting the
        // parser discover that would turn a vague sentence into a refusal (§4.3).
        val readable = laid.filter { it.kind != null }
        if (readable.none { it.kind == PageClass.NUCLEUS }) return null
        val read = ArtReading.parse(readable) ?: Repair.of(vocabulary, readable)
        return read.copy(unreadable = laid.filter { it.kind == null }.map(Page::written))
    }

    /** Whether these pages are a book — the [read] refusal asked ahead, for a screen that must not guess. */
    fun isABook(vocabulary: Vocabulary, pages: List<String>): Boolean =
        pages.any { classify(vocabulary, it).kind == PageClass.NUCLEUS }

    /**
     * What the Art makes of one page — its class, the word behind it, and the part of the world it is in.
     * [latent] where the page is the Art's own rather than a writer's.
     */
    internal fun classify(vocabulary: Vocabulary, written: String, latent: Boolean = false): Page {
        vocabulary.grammarWord(written)?.let { structural ->
            return Page(
                written,
                kind = structural.production.pageClass,
                production = structural.production,
                rung = structural.rung,
                latent = latent,
            )
        }
        val word = vocabulary.word(written) ?: return Page(written, kind = null, latent = latent)
        val kind = word.pageClass
        return Page(written, kind = kind, word = word, aspect = word.aspectFor(kind), latent = latent)
    }

    /**
     * Which class an ordinary word belongs to.
     *
     * An **aiming page** is recognised by shape rather than by a flag: a word that asks for no tag, names
     * no preset and sets no parameter says nothing except which part of the world it is about, and that is
     * exactly what a subject page *is*.
     *
     * **An evocative word has no class of its own.** It used to, because it sat in a slot before the
     * subject and everything else sat after — and now that every modifier leads, the position is the same
     * one and [Tier] alone decides whether a word tilts or narrows (§4.3.1).
     */
    private val Word.pageClass: PageClass
        get() = when {
            aims -> PageClass.SUBJECT
            isMaterial -> PageClass.MATERIAL
            else -> PageClass.TERM
        }

    /**
     * A block, which every part of the world that is *made of* something can take.
     *
     * Read off the material it sets rather than declared, because that is what a material *is* — every
     * such word arrives from `DerivedWords`, one per block in the pack, and none of them is authored.
     */
    private val Word.isMaterial: Boolean get() = Terrain.STONE.name in sets

    /**
     * Which part of the world this page belongs to, for the classes that belong to one.
     *
     * The first in ordinal order, and now only a hint: a term's real home is the section it was laid in,
     * which the parser knows when it reads one. This is what a page says about itself before anyone has
     * asked where it is standing, and [Repair] uses it to tell two pages of one word apart.
     */
    private fun Word.aspectFor(kind: PageClass): Aspect? = when (kind) {
        PageClass.SUBJECT, PageClass.TERM -> aspects.minByOrNull { it.ordinal }
        else -> null
    }

    private val Production.pageClass: PageClass
        get() = when (this) {
            Production.NUCLEUS -> PageClass.NUCLEUS
            Production.CONJUNCTION -> PageClass.JOINER
            Production.RESTRICTION -> PageClass.RESTRICTOR
            Production.EXCEPTION -> PageClass.EXCLUDER
            Production.QUANTIFICATION -> PageClass.QUANTIFIER
            Production.CONFINEMENT -> PageClass.CONFINER
        }
}
