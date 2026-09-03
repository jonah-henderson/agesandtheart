package co.voik.agesandtheart.preview.authoring.ui

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.preview.authoring.Corpus

/**
 * **What the word does to one part of the world's set of members, drawn** — the pool it starts from, what
 * it adds and takes out, and how the draw leans between what is left.
 *
 * The counterpart of [Axis] for a member where that is for a value on a range: a claim about a set is five
 * operations over it, and every one of them is invisible in the file. Reading `#cavernous 1.0` tells you
 * nothing about whether four things carry it or none.
 *
 * A bar per surviving member, scaled against the strongest, with the struck-out shown greyed and marked so
 * a removal is visible rather than merely absent.
 */
object PoolChart {

    fun of(aspect: Aspect, word: Word?, corpus: Corpus, width: Int): List<Line> {
        if (word == null || width < NARROWEST) return emptyList()
        val vocabulary = corpus.vocabulary
        // **A choice ends the search only where the aspect seats one member.** A population keeps
        // everything it had and the chosen one joins it, so drawing that as an answer would hide the whole
        // pool behind a word that merely asked for more of one member of it.
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

        val standing = pool.associateWith { Resolver.standingOf(vocabulary, word, aspect, it) }
        fun strengthOf(member: Taggable) = standing.getValue(member).strength
        val kept = pool.filter { standing.getValue(it).kept }
        val strongest = kept.maxOfOrNull(::strengthOf) ?: 0.0
        val head = Line("  ${kept.size} of ${pool.size}", Palette.value) +
            Line(" in the ${aspect.page}", Palette.faint) +
            Line(if (added.isEmpty()) "" else "  ${Glyph.BULLET} ${added.size} added", Palette.settled) +
            Line(
                if (kept.size == pool.size) "" else "  ${Glyph.BULLET} ${pool.size - kept.size} taken out",
                Palette.refused,
            )
        val rows = pool.sortedByDescending { if (it in kept) strengthOf(it) else -1.0 }
            .take(SHOWN)
            .map { member -> row(member, member in kept, member in added, strongest, strengthOf(member)) }
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
        strength: Double,
    ): Line {
        val pull = if (kept) strength else 0.0
        val mark = when {
            !kept -> Glyph.WARN
            added -> "+"
            else -> " "
        }
        return Line("    ") + Line("$mark ", if (kept) Palette.settled else Palette.refused) +
            Line(member.key.padEnd(NAME), if (kept) Palette.value else Palette.faint) +
            Gauge.filled(pull, strongest, BAR) +
            Line(if (kept) "  %.2f".format(pull) else "  taken out", Palette.faint)
    }

    private const val NARROWEST = 40
    private const val SHOWN = 8
    private const val BAR = 10
    private const val NAME = 30
}

/**
 * **Which members a row of the populations page actually names**, listed.
 *
 * The chart beside this says what the *pool* came to; this says what the row under the cursor did to
 * reach it. `remove #flowering` explaining itself as "everything with #flowering" is a definition rather
 * than an answer — what a writer wants to know is that it took out the cherry grove, the meadow and the
 * flower forest, and whether that is what they meant.
 */
object Touched {

    fun of(aspect: Aspect, named: String, corpus: Corpus, width: Int): List<Line> {
        if (width < NARROWEST) return emptyList()
        val matching = matching(aspect, named, corpus)
        if (matching.isEmpty()) {
            return listOf(Line("  nothing in the ${aspect.page} answers $named", Palette.warned))
        }
        val head = Line("  ${matching.size}", Palette.value) +
            Line(" in the ${aspect.page} ${Glyph.BULLET} $named", Palette.faint)
        val shown = matching.take(SHOWN).joinToString("  ${Glyph.BULLET}  ")
        val more = (matching.size - SHOWN).takeIf { it > 0 }?.let { "  ${Glyph.ELIDED} and $it more" }.orEmpty()
        return listOf(head) + Line("  $shown$more", Palette.value).wrapped(width, hanging = "  ")
    }

    /**
     * What [named] picks out of [aspect]'s pool — everything carrying the tag, or the one member itself.
     *
     * One function for all four steps on purpose: adding, keeping, removing and leaning differ in what
     * they *do* with what they name, and the row above already says which. What they name is one question.
     */
    private fun matching(aspect: Aspect, named: String, corpus: Corpus): List<String> {
        val pool = corpus.vocabulary.candidatesFor(aspect)
        if (!named.startsWith(Word.TAG_MARK)) {
            return pool.map { it.key }.filter { it == named }.ifEmpty {
                listOfNotNull(aspect.presetFor(named)?.key?.takeIf { it == named })
            }
        }
        val tag = named.drop(Word.TAG_MARK.length)
        return pool.filter { corpus.vocabulary.tagsOf(it).containsKey(tag) }.map { it.key }.sorted()
    }

    private const val NARROWEST = 24
    private const val SHOWN = 24
}
