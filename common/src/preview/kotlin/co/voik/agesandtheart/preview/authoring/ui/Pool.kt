package co.voik.agesandtheart.preview.authoring.ui

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.preview.authoring.Corpus

/**
 * **What the word does to one population, drawn** — the pool it starts from, what it adds and takes out,
 * and how the draw leans between what is left.
 *
 * The counterpart of [Axis] for a member where that is for a value on a range: a claim about a population
 * is five operations over a set, and every one of them is invisible in the file. Reading `#cavernous 1.0`
 * tells you nothing about whether four things carry it or none.
 *
 * A bar per surviving member, scaled against the strongest, with the struck-out shown greyed and marked so
 * a removal is visible rather than merely absent.
 */
object PoolChart {

    fun of(aspect: Aspect, word: Word?, corpus: Corpus, width: Int): List<Line> {
        if (word == null || width < NARROWEST) return emptyList()
        val vocabulary = corpus.vocabulary
        // **A choice ends the pipeline only where the aspect holds one thing.** A population keeps every
        // member it had and the chosen one joins them, so drawing it as an answer would have hidden the
        // whole pool behind a word that merely asked for more of one member of it.
        val chosen = word.choiceIn(aspect)
        if (chosen != null && aspect.holds != Holds.WEIGHTED_SET) {
            return listOf(
                Line("  ") + Line(chosen.key, Palette.chosen) +
                    Line(" is the answer; the ${aspect.page}'s pool is never searched", Palette.faint),
            )
        }
        val curated = vocabulary.askableIn(aspect)
        val named = word.admitsIn(aspect).mapNotNull(aspect::presetFor) + listOfNotNull(chosen)
        val added = named.filterNot { it in curated }
        val pool = curated + added
        if (pool.isEmpty()) return listOf(Line("  nothing in the ${aspect.page} to draw between", Palette.faint))

        val kept = pool.filterNot { struck(it, word, aspect, corpus) }
        val strongest = kept.maxOfOrNull { strength(it, word, aspect, corpus) } ?: 0.0
        val head = Line("  ${kept.size} of ${pool.size}", Palette.value) +
            Line(" in the ${aspect.page}", Palette.faint) +
            Line(if (added.isEmpty()) "" else "  ${Glyph.BULLET} ${added.size} added", Palette.settled) +
            Line(
                if (kept.size == pool.size) "" else "  ${Glyph.BULLET} ${pool.size - kept.size} taken out",
                Palette.refused,
            )
        val rows = pool.sortedByDescending { if (it in kept) strength(it, word, aspect, corpus) else -1.0 }
            .take(SHOWN)
            .map { member -> row(member, member in kept, member in added, strongest, word, aspect, corpus) }
        val more = (pool.size - SHOWN).takeIf { it > 0 }
            ?.let { listOf(Line("    ${Glyph.ELIDED} and $it more", Palette.faint)) }
            .orEmpty()
        return listOf(head) + rows + more
    }

    private fun row(
        member: Taggable,
        kept: Boolean,
        added: Boolean,
        strongest: Double,
        word: Word,
        aspect: Aspect,
        corpus: Corpus,
    ): Line {
        val pull = if (kept) strength(member, word, aspect, corpus) else 0.0
        val filled = if (strongest <= 0.0) 0 else (pull / strongest * BAR).toInt().coerceIn(0, BAR)
        val mark = when {
            !kept -> Glyph.WARN
            added -> "+"
            else -> " "
        }
        return Line("    ") + Line("$mark ", if (kept) Palette.settled else Palette.refused) +
            Line(member.key.padEnd(NAME), if (kept) Palette.value else Palette.faint) +
            Line(Glyph.FULL.repeat(filled) + Glyph.EMPTY.repeat(BAR - filled), Palette.settled) +
            Line(if (kept) "  %.2f".format(pull) else "  taken out", Palette.faint)
    }

    /**
     * What the draw would weigh this member at.
     *
     * **A population's answer is asked of the resolver**, so the bar a writer reads is the number the Age
     * is written with rather than a second reckoning of it that can drift; a member nothing spoke about
     * keeps the share it would have had, which is ordinary. A catalogue has no such claim to ask for — it
     * seats one preset rather than weighing a set — so there this is the word's own pull and lean.
     */
    private fun strength(member: Taggable, word: Word, aspect: Aspect, corpus: Corpus): Double {
        if (aspect.pool == null) {
            val tags = corpus.vocabulary.tagsOf(member)
            return (word.claimOn(member, tags) + word.biasOn(member, tags)).coerceAtLeast(0.0)
        }
        return claimBy(member, word, aspect, corpus)?.density ?: Rung.ORDINARY
    }

    private fun struck(member: Taggable, word: Word, aspect: Aspect, corpus: Corpus): Boolean {
        if (aspect.pool == null) return !word.acceptsOn(member, corpus.vocabulary.tagsOf(member))
        return claimBy(member, word, aspect, corpus)?.polarity == Polarity.EXCEPT
    }

    private fun claimBy(member: Taggable, word: Word, aspect: Aspect, corpus: Corpus) =
        Resolver.claimBy(corpus.vocabulary, word, aspect, member)

    private const val NARROWEST = 40
    private const val SHOWN = 8
    private const val BAR = 10
    private const val NAME = 30
}
