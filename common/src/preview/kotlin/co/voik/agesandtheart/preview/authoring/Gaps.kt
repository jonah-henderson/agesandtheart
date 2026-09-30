package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.age.aspect.ownParameters
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Parameter

/**
 * What the Art cannot yet say — **the parts of the world no word reaches.**
 *
 * The audit asks what is wrong with the words there are. This asks the other question: which tags, parameters
 * and values nothing has ever been written for. They are the same shape of debt the vocabulary pass keeps
 * finding by hand — eight carried tags asked by nobody, four parameters with no word at all — and finding them
 * by hand is what this replaces.
 */
object Gaps {

    /** What kind of gap this is, which decides what a word for it would look like. */
    enum class Kind(val title: String) {
        TAG("tag"),
        PARAMETER("parameter"),
        VALUE("value"),
    }

    /**
     * One thing nothing reaches. [where] names the aspects it belongs to, which is both what a reader
     * needs and what the word this becomes has to target.
     */
    data class Gap(
        val kind: Kind,
        val what: String,
        val where: List<Aspect>,
        val said: String,
        /** The parameter a [Kind.VALUE] gap is a value of. */
        val parameter: String = "",
    ) {
        val key: String get() = "${kind.name}/$parameter/$what"
    }

    fun of(corpus: Corpus): List<Gap> = tags(corpus) + parameters(corpus) + values(corpus)

    /**
     * Tags something carries that no word asks for.
     *
     * A tag nothing asks is a distinction the world makes and the language cannot: `#aloft` is on the bat
     * and the phantom, and until `fliers` was written there was no way to say either.
     */
    private fun tags(corpus: Corpus): List<Gap> {
        val asked = corpus.vocabulary.words.flatMap { it.wanted + it.unwanted + it.leanedTags }.toSet()
        return (corpus.vocabulary.carriedTags - asked).sorted().map { tag ->
            val carrying = Aspect.entries.filter { aspect ->
                corpus.vocabulary.candidatesFor(aspect).any { tag in corpus.vocabulary.tagsOf(it) }
            }
            Gap(
                kind = Kind.TAG,
                what = tag,
                where = carrying,
                said = "carried by ${carrying.sumOf { countCarrying(corpus, it, tag) }} thing(s), asked by nothing",
            )
        }
    }

    private fun countCarrying(corpus: Corpus, aspect: Aspect, tag: String) =
        corpus.vocabulary.candidatesFor(aspect).count { tag in corpus.vocabulary.tagsOf(it) }

    /** Parameters nothing turns — a part of the world that exists and cannot be spoken to. */
    private fun parameters(corpus: Corpus): List<Gap> {
        val turned = corpus.vocabulary.words.flatMap { it.canSet.keys.map { parameter -> parameter.substringAfterLast('.') } }
            .toSet()
        return everyParameter(corpus).filterNot { (name, _) -> name in turned }
            .map { (name, owners) ->
                Gap(
                    kind = Kind.PARAMETER,
                    what = name,
                    where = owners.map { it.on }.distinct(),
                    said = owners.first().parameter.help.ifBlank { "nothing sets it" },
                )
            }
    }

    /**
     * Values of a closed parameter that no word ever asks for.
     *
     * The default is left out: not asking for it is what saying nothing already does, so a word for it
     * would say what silence says.
     */
    private fun values(corpus: Corpus): List<Gap> {
        val set = corpus.vocabulary.words.flatMap { word ->
            word.canSet.entries.flatMap { (parameter, value) ->
                value.split('|').map { parameter.substringAfterLast('.') to it.trim() }
            }
        }.toSet()
        return everyParameter(corpus).flatMap { (name, owners) ->
            val parameter = owners.first().parameter
            if (parameter.open || parameter.holds != Holds.CATALOGUE) return@flatMap emptyList()
            parameter.options.drop(1)
                .filterNot { option -> (name to option) in set }
                .map { option ->
                    Gap(
                        kind = Kind.VALUE,
                        what = option,
                        where = owners.map { it.on }.distinct(),
                        said = parameter.optionHelp[option].orEmpty().ifBlank { "no word sets $name to it" },
                        parameter = name,
                    )
                }
        }
    }

    /** Every parameter in the game with the aspects that own it, which both gap kinds are asked of. */
    private fun everyParameter(corpus: Corpus): List<Pair<String, List<Owned>>> =
        Aspect.entries.flatMap { aspect ->
            (aspect.parameters + corpus.vocabulary.candidatesFor(aspect).flatMap { preset ->
                preset.ownParameters.filter(preset::honours)
            }).map { Owned(aspect, it) }
        }
            .filterNot { it.parameter.name == Parameter.CAST }
            .groupBy { it.parameter.name }
            .map { (name, owned) -> name to owned }
            .sortedBy { it.first }

    /** A parameter and the aspect it was found on — the same name may be several parameters. */
    data class Owned(val on: Aspect, val parameter: Parameter)

    /**
     * A word that would fill this gap, as far as the gap itself says.
     *
     * The name is a guess and the verdict will complain about it if it collides, which is the right way
     * round: a suggestion you have to correct beats an empty screen you have to fill.
     */
    fun wordFor(gap: Gap, corpus: Corpus): Candidate = when (gap.kind) {
        // The query is keyed to the aspects that carry the tag, which is both what the word means and
        // the only thing that gives it any reach at all now that a word cannot declare one.
        Kind.TAG -> Candidate(
            name = gap.what,
            restricts = gap.where.associateWith { mapOf(gap.what to GAP_BAR) },
        )
        // A parameter names the aspects that own it, so setting one is all the reach the word needs.
        Kind.PARAMETER -> Candidate(
            name = gap.what,
            sets = mapOf(gap.what to suggestedValue(gap, corpus)),
        )
        Kind.VALUE -> Candidate(
            name = gap.what,
            sets = mapOf(gap.parameter to gap.what),
        )
    }

    /** Something the parameter will actually take, so the new word starts valid rather than merely started. */
    private fun suggestedValue(gap: Gap, corpus: Corpus): String {
        val parameter = everyParameter(corpus).firstOrNull { it.first == gap.what }?.second?.first()?.parameter
            ?: return WHOLE_TOP
        return when {
            parameter.holds == Holds.RANGE -> WHOLE_TOP
            parameter.options.size > 1 -> parameter.options[1]
            else -> parameter.options.first()
        }
    }

    /** A band a writer would plausibly have meant, for a parameter whose values are an axis. */
    private const val WHOLE_TOP = "0.5..1.0"

    /** Today's restrictive bar: what a word reaching a tag starts out asking of it. */
    private const val GAP_BAR = 0.3
}
