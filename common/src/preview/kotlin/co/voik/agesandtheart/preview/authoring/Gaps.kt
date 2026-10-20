package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.ownParameters
import co.voik.agesandtheart.age.language.Meaning
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.ParameterKey

/**
 * What the Art cannot yet say: a tag something carries and no word speaks of, a parameter something turns and no
 * word sets, a value of one no word says. Worked out from the words there are, so it empties as the corpus fills.
 */
object Gaps {

    enum class Kind(val title: String) { TAG("tag"), PARAMETER("parameter"), VALUE("value") }

    data class Gap(val kind: Kind, val what: String, val where: List<Aspect>, val said: String, val parameter: String = "") {
        val key: String get() = "${kind.name}/$parameter/$what"
    }

    fun of(corpus: Corpus): List<Gap> = tags(corpus) + parameters(corpus) + values(corpus)

    private fun tags(corpus: Corpus): List<Gap> {
        val asked = corpus.vocabulary.words.flatMap { it.tagsSpokenOf }.toSet()
        return (corpus.vocabulary.carriedTags - asked).sorted().map { tag ->
            val carrying = Aspect.entries.filter { aspect -> corpus.vocabulary.candidatesFor(aspect).any { tag in corpus.vocabulary.tagsOf(it) } }
            val many = carrying.sumOf { aspect -> corpus.vocabulary.candidatesFor(aspect).count { tag in corpus.vocabulary.tagsOf(it) } }
            Gap(Kind.TAG, tag, carrying, "carried by $many thing(s), asked by nothing")
        }
    }

    private fun settingsOf(corpus: Corpus): List<Pair<String, String?>> = corpus.vocabulary.words.flatMap { word ->
        word.effects.mapNotNull { effect ->
            when (effect) {
                is Effect.Sets -> effect.parameter.name to effect.value
                is Effect.Bends -> effect.parameter.name to null
                else -> null
            }
        }
    }

    private fun parameters(corpus: Corpus): List<Gap> {
        val turned = settingsOf(corpus).map { it.first }.toSet()
        return everyParameter(corpus).filterNot { (name, _) -> name in turned }.map { (name, owners) ->
            Gap(Kind.PARAMETER, name, owners.map { it.on }.distinct(), owners.first().parameter.help.ifBlank { "nothing sets it" })
        }
    }

    private fun values(corpus: Corpus): List<Gap> {
        val said = settingsOf(corpus).toSet()
        return everyParameter(corpus).flatMap { (name, owners) ->
            val parameter = owners.first().parameter
            if (parameter.open || parameter.holds != Holds.CATALOGUE) return@flatMap emptyList()
            parameter.options.drop(1).filterNot { option -> (name to option) in said }.map { option ->
                Gap(
                    Kind.VALUE, option, owners.map { it.on }.distinct(),
                    parameter.optionHelp[option].orEmpty().ifBlank { "no word sets $name to it" }, parameter = name,
                )
            }
        }
    }

    private data class Owned(val on: Aspect, val parameter: Parameter)

    private fun everyParameter(corpus: Corpus): List<Pair<String, List<Owned>>> = Aspect.entries
        .flatMap { aspect ->
            (aspect.parameters + corpus.vocabulary.candidatesFor(aspect).flatMap { preset -> preset.ownParameters.filter(preset::honours) })
                .map { Owned(aspect, it) }
        }
        .filterNot { it.parameter.name == Parameter.CAST }
        .groupBy { it.parameter.name }
        .map { (name, owned) -> name to owned }
        .sortedBy { it.first }

    /** A word begun for [gap], meaning the one thing it lacks. */
    fun wordFor(gap: Gap, corpus: Corpus): Candidate {
        val effects = when (gap.kind) {
            Kind.TAG -> gap.where.map { Effect.Restricts(it, mapOf(gap.what to GAP_BAR)) }
            Kind.PARAMETER -> listOf(Effect.Sets(ParameterKey(gap.what), suggestedValue(gap, corpus)))
            Kind.VALUE -> listOf(Effect.Sets(ParameterKey(gap.parameter), gap.what))
        }
        return Candidate(name = gap.what, meanings = listOf(Meaning(effects, mustAlwaysApply = true)))
    }

    private fun suggestedValue(gap: Gap, corpus: Corpus): String {
        val parameter = everyParameter(corpus).firstOrNull { it.first == gap.what }?.second?.first()?.parameter ?: return WHOLE_TOP
        return when {
            parameter.holds == Holds.RANGE -> WHOLE_TOP
            parameter.options.size > 1 -> parameter.options[1]
            else -> parameter.options.first()
        }
    }

    /** What a begun tag word bars at: today's restrictive bar. */
    private const val GAP_BAR = 0.3

    /** The top of an axis, which every word asking for more of something starts from. */
    private const val WHOLE_TOP = "0.5..1.0"
}
