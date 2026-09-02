package co.voik.agesandtheart.preview.authoring.ui

import co.voik.agesandtheart.age.CompositionSpelling
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.Disagreement
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Readout
import co.voik.agesandtheart.age.word.grammar.Sentence
import co.voik.agesandtheart.preview.authoring.Candidate
import co.voik.agesandtheart.preview.authoring.Corpus
import co.voik.agesandtheart.preview.authoring.Verdict

/** The things a writer reads rather than answers — what a word finds, what is wrong with it, what it does. */
object Preview {

    /**
     * What the word would actually keep, aspect by aspect.
     *
     * **Two numbers per aspect, for the reason `/age tags` reports two**: everything above nothing carries
     * a tag, and only what clears the tier's threshold is reachable — a tail of tenth-weight carriers
     * otherwise reads as coverage it is not.
     */
    fun carriers(candidate: Candidate, word: Word?, corpus: Corpus): Reader {
        if (word == null) return Reader("Preview", listOf(Line("this word will not load", Palette.refused)))
        val lines = buildList {
            add(Line("ink ${word.price}", Palette.value) + Line("  = ${word.tier.key} × ${word.versatility} targets", Palette.faint))
            add(Line.BLANK)
            for (aspect in word.aspects.sortedBy { it.ordinal }) {
                val askable = corpus.vocabulary.askableIn(aspect)
                val kept = corpus.vocabulary.carriersOf(word, aspect)
                val steersOnly = !word.constrainsPresetsIn(aspect)
                add(
                    Line(aspect.page.padEnd(12), Palette.heading) +
                        Line(
                            when {
                                // A word may narrow in one aspect and only turn a parameter in another, and
                                // having no carrier here is then no fault at all — `arid` narrows the
                                // ground on tags and bounds the climate's axes with spans, two real jobs.
                                steersOnly -> "sets a value; picks nothing"
                                askable.isEmpty() -> "nothing to pick between"
                                else -> "keeps ${kept.size} of ${askable.size}"
                            },
                            Palette.value,
                        ) +
                        Line(rememberedFor(aspect, word, corpus), Palette.faint),
                )
                if (kept.isEmpty() && !steersOnly && askable.isNotEmpty()) {
                    add(Line("    nothing here is tagged strongly enough", Palette.warned))
                }
                for (preset in kept.take(SHOWN_PER_ASPECT)) {
                    val strength = word.pullOn(preset, corpus.vocabulary.tagsOf(preset))
                    add(
                        Line("    ") + Line(preset.key.padEnd(38), Palette.value) +
                            Line("%.2f".format(strength), Palette.settled) +
                            Line("  ${corpus.vocabulary.tagsOf(preset).keys.joinToString(" ")}", Palette.faint),
                    )
                }
                if (kept.size > SHOWN_PER_ASPECT) {
                    add(Line("    ${Glyph.ELIDED} and ${kept.size - SHOWN_PER_ASPECT} more", Palette.faint))
                }
                for (parameter in word.setsIn(aspect).keys.filter { corpus.vocabulary.turnsAParameter(aspect, it) }) {
                    add(Line("    turns ", Palette.faint) + Line(parameter, Palette.parameter) +
                        Line(" to ${word.setsIn(aspect)[parameter]}", Palette.value))
                }
                add(Line.BLANK)
            }
            addAll(quarrelLines(candidate, word, corpus))
        }
        return Reader("What '${candidate.name}' matches", lines)
    }

    private fun rememberedFor(aspect: Aspect, word: Word, corpus: Corpus): String {
        val tag = word.wanted.firstOrNull() ?: return ""
        val seen = corpus.snapshot?.reachOf(aspect, tag) ?: return ""
        return "   ${Glyph.BULLET} a server saw ${seen.found} of ${seen.carriers} carry '$tag' " +
            "(imported ${corpus.snapshot.importedAt})"
    }

    private fun quarrelLines(candidate: Candidate, word: Word, corpus: Corpus): List<Line> {
        val against = corpus.otherThan(candidate.name)
            .filterNot(corpus.vocabulary::isDerived)
            .mapNotNull { other -> corpus.vocabulary.disagreement(word, other)?.let { other to it } }
        if (against.isEmpty()) return listOf(Line("nothing else contradicts it", Palette.settled))
        return listOf(Line("contradicts", Palette.heading)) + against.map { (other, why) ->
            Line("    ") + Line(other.name.padEnd(20), Palette.value) +
                Line(why.over.joinToString("/").padEnd(24), Palette.tag) +
                Line(becauseOf(word, other, why), Palette.faint) +
                Line("  severity ${why.severity}", Palette.faint)
        }
    }

    /**
     * Which of the three sources of disagreement this one is.
     *
     * `Disagreement` carries what the quarrel is over and not where it came from, and the three read
     * identically on screen while wanting completely different fixes: an antonym pair is a line in
     * `art/antonyms/`, a parameter clash is two bands somebody has to widen, and a pushed tag is one
     * word's own query. Told apart the way `Vocabulary` asks them, in the same order.
     */
    private fun becauseOf(word: Word, other: Word, why: Disagreement): String {
        if (why.over.size == 2) return "opposed in the antonym table"
        val over = why.over.firstOrNull() ?: return ""
        val bothBind = over in word.sets && over in other.sets
        return if (bothBind) "bands on $over that cannot both hold" else "one asks for it, the other pushes it away"
    }

    /** Everything the verdict found, at length — the strip shows the worst three. */
    fun faults(findings: List<Verdict.Finding>): Reader {
        if (findings.isEmpty()) {
            return Reader("Faults", listOf(Line("${Glyph.TICK} nothing wrong", Palette.settled)))
        }
        val lines = findings.sortedBy { it.standing.ordinal }.flatMap { finding ->
            listOf(
                Line("${finding.standing.name.lowercase().padEnd(9)}", styleOf(finding.standing)) +
                    Line(finding.says, Palette.value) +
                    Line(finding.heldBy?.let { "   [$it]" }.orEmpty(), Palette.faint),
            ) + (finding.because?.let { listOf(Line("          $it", Palette.faint)) }.orEmpty())
        }
        return Reader("What is wrong with it", lines)
    }

    private fun styleOf(standing: Verdict.Standing) = when (standing) {
        Verdict.Standing.ERROR -> Palette.refused
        Verdict.Standing.WARNED -> Palette.warned
        Verdict.Standing.NUDGED -> Palette.nudged
        Verdict.Standing.NOTED -> Palette.noted
    }

    /**
     * A sentence read and resolved with the candidate in the corpus.
     *
     * The vocabulary is loaded again with the word laid over the pack, which is a second — and is what
     * makes this the honest answer rather than an approximation of one.
     */
    fun resolved(candidate: Candidate, sentence: String, corpus: Corpus): Reader {
        val pages = sentence.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        val vocabulary = runCatching { corpus.spliced(candidate) }.getOrElse { failure ->
            return Reader("Try a sentence", listOf(Line("could not reload the words: ${failure.message}", Palette.refused)))
        }
        val unknown = pages.filter { vocabulary.word(it) == null && vocabulary.grammarWord(it) == null }
        if (unknown.isNotEmpty()) {
            return Reader(
                "Try a sentence",
                listOf(Line("no word called ${unknown.joinToString(" ")}", Palette.refused)),
            )
        }
        val read = Grammar.read(vocabulary, pages)
            ?: return Reader(
                "Try a sentence",
                listOf(
                    Line("that is not a valid book", Palette.refused),
                    Line("the first clause ends with `age`; later ones end with what they describe, e.g. `blue sun`", Palette.faint),
                ),
            )
        return spelled(vocabulary, read, SEED, "'$sentence' at seed $SEED")
    }

    /**
     * A book already read, resolved and said back — the readout, what it costs, and the world it makes.
     */
    fun spelled(vocabulary: Vocabulary, read: Sentence, seed: Long, title: String): Reader {
        val resolution = Resolver.resolve(vocabulary, read, seed)
        val written = CompositionSpelling.Written(resolution.composition, resolution.template)
        return Reader(
            title,
            buildList {
                add(Line("reads as ", Palette.faint) + Line(Readout.of(read), Palette.value))
                add(Line("costs ", Palette.faint) + Line("${resolution.cost} ink", Palette.value) +
                    Line("   ${resolution.instability}", if (resolution.instability.isCoherent) Palette.settled else Palette.warned))
                if (resolution.dropped.isNotEmpty()) {
                    add(Line("dropped ", Palette.faint) + Line(resolution.dropped.joinToString(" "), Palette.refused))
                }
                add(Line.BLANK)
                for (flaw in resolution.instability.flaws) {
                    add(Line("  ${Glyph.WARN} ", Palette.warned) + Line(flaw.describe(), Palette.value))
                }
                if (resolution.instability.flaws.isNotEmpty()) add(Line.BLANK)
                add(Line("the world it makes", Palette.heading))
                addAll(CompositionSpelling.spell(written).lines().map { Line("  $it", Palette.faint) })
            },
        )
    }

    private const val SHOWN_PER_ASPECT = 12
    private const val SEED = 1L
}
