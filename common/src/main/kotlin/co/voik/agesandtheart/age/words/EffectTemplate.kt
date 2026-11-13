package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Bars
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.ParameterKey
import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.Optional

/**
 * An effect as a word file spells it — a sense's own meaning, or what an acceptance makes of an offer. Fixed, or taking
 * the offer's value:
 *
 * ```json
 * { "chooses": { "structures": "minecraft:villages" } }   fixed
 * { "sets": "colour" }                                     the value, as this part's colour
 * { "sets": "surface.bare" }                               the value, as the surface's
 * { "leans": "features" }  { "abundance": "$" }            the value's weights; the value's amount
 * ```
 *
 * A bare parameter is the accepting sense's own part's.
 */
sealed interface EffectTemplate {
    /** What this becomes for [value], said of [part]; null where the value is not of the kind the effect takes. */
    fun applied(value: Value, part: Aspect?): Effect?

    data class Fixed(val effect: Effect) : EffectTemplate {
        override fun applied(value: Value, part: Aspect?): Effect = effect
    }

    /** An effect made of the offer's value: [target] is a parameter, a part's page, or [THE_VALUE] where neither is needed. */
    data class Taking(val verb: Verb, val target: String) : EffectTemplate {
        override fun applied(value: Value, part: Aspect?): Effect? {
            val text = (value as? Value.Text)?.text
            return when (verb) {
                Verb.SETS -> text?.let { Effect.Sets(parameterOf(part), it) }
                Verb.BENDS -> text?.let { Effect.Bends(parameterOf(part), it) }
                Verb.CHOOSES -> text?.let { Effect.Chooses(partNamed(), it) }
                Verb.ADMITS -> text?.let { Effect.Admits(partNamed(), setOf(it)) }
                Verb.EXCLUDES -> text?.let { Effect.Excludes(partNamed(), setOf(it)) }
                Verb.RESTRICTS -> (value as? Value.Bars)?.let { Effect.Restricts(partNamed(), it.bars) }
                Verb.LEANS -> (value as? Value.Weights)?.let { leaning(it.weights) }
                Verb.TEMPLATE -> text?.let { Effect.Template(it) }
                Verb.MADE_OF -> text?.let { Effect.MadeOf(it) }
                Verb.BANS -> text?.let { Effect.Bans(it) }
                Verb.ABUNDANCE -> (value as? Value.Amount)?.let { Effect.Abundance(it.multiple) }
            }
        }

        private fun parameterOf(part: Aspect?): ParameterKey {
            val key = ParameterKey.read(target)
            return if (key.fencedTo == null && part != null) ParameterKey(key.name, part) else key
        }

        private fun partNamed(): Aspect = Aspect.byPage(target) ?: error("'$target' was checked as a part when read")

        private fun leaning(weights: Map<String, Double>): Effect =
            if (target == EVERY_PART) Effect.LeansEverywhere(weights) else Effect.Leans(partNamed(), weights)
    }

    /** What a taking effect does with the value. */
    enum class Verb(val key: String, val targets: Targets) {
        SETS("sets", Targets.PARAMETER),
        BENDS("bends", Targets.PARAMETER),
        CHOOSES("chooses", Targets.PART),
        ADMITS("admits", Targets.PART),
        EXCLUDES("excludes", Targets.PART),
        RESTRICTS("restricts", Targets.PART),
        LEANS("leans", Targets.PART_OR_EVERY_PART),
        TEMPLATE("template", Targets.NONE),
        MADE_OF("made_of", Targets.NONE),
        BANS("bans", Targets.NONE),
        ABUNDANCE("abundance", Targets.NONE),
    }

    /** What a taking effect's target names. */
    enum class Targets {
        PARAMETER,
        PART,
        PART_OR_EVERY_PART,
        NONE,
        ;

        fun admits(target: String): Boolean = when (this) {
            PARAMETER -> target.isNotBlank()
            PART -> Aspect.byPage(target) != null
            PART_OR_EVERY_PART -> target == EVERY_PART || Aspect.byPage(target) != null
            NONE -> target == THE_VALUE
        }
    }

    companion object {
        /** The target of a taking effect that needs none: the value goes in whole. */
        const val THE_VALUE = "$"

        /** A lean's target meaning every part it likes something in. */
        const val EVERY_PART = "all"

        /** One object of effects, read as every effect it holds. */
        val CODEC: Codec<List<EffectTemplate>> = EffectSpelling.CODEC.flatXmap(EffectSpelling::read) { templates ->
            templates.singleOrNull()?.let { DataResult.success(EffectSpelling.of(it)) }
                ?: DataResult.error { "an effect object is written one effect at a time" }
        }

        /** A list of effect objects, flattened: what a translation or an own meaning holds. */
        val LIST_CODEC: Codec<List<EffectTemplate>> = CODEC.listOf().xmap({ it.flatten() }, { templates -> templates.map(::listOf) })
    }
}

/** One object of effects as a file holds it: each effect under its verb, either a target (taking) or a fixed value. */
private data class EffectSpelling(
    val sets: Either<String, Map<String, String>>? = null,
    val bends: Either<String, Map<String, String>>? = null,
    val chooses: Either<String, Map<Aspect, String>>? = null,
    val admits: Either<String, Map<Aspect, List<String>>>? = null,
    val excludes: Either<String, Map<Aspect, List<String>>>? = null,
    val restricts: Either<String, Map<Aspect, Map<String, Double>>>? = null,
    val leans: Either<String, Map<String, Map<String, Double>>>? = null,
    val mints: MintsSpelling? = null,
    val template: String? = null,
    val madeOf: String? = null,
    val bans: String? = null,
    val abundance: Either<String, Double>? = null,
) {
    fun read(): DataResult<List<EffectTemplate>> {
        val templates = mutableListOf<EffectTemplate>()
        val problems = mutableListOf<String>()
        fun taking(verb: EffectTemplate.Verb, target: String) {
            if (verb.targets.admits(target)) templates += EffectTemplate.Taking(verb, target) else problems += "'$target' is no target for ${verb.key}"
        }
        fun <T> either(spelled: Either<String, T>?, verb: EffectTemplate.Verb, fixed: (T) -> List<Effect>) {
            spelled?.ifLeft { taking(verb, it) }?.ifRight { value -> templates += fixed(value).map(EffectTemplate::Fixed) }
        }
        fun scalar(spelled: String?, verb: EffectTemplate.Verb, fixed: (String) -> Effect) {
            when (spelled) {
                null -> Unit
                EffectTemplate.THE_VALUE -> taking(verb, spelled)
                else -> templates += EffectTemplate.Fixed(fixed(spelled))
            }
        }
        either(sets, EffectTemplate.Verb.SETS) { it.map { (key, value) -> Effect.Sets(ParameterKey.read(key), value) } }
        either(bends, EffectTemplate.Verb.BENDS) { it.map { (key, value) -> Effect.Bends(ParameterKey.read(key), value) } }
        either(chooses, EffectTemplate.Verb.CHOOSES) { it.map { (aspect, member) -> Effect.Chooses(aspect, member) } }
        either(admits, EffectTemplate.Verb.ADMITS) { it.map { (aspect, members) -> Effect.Admits(aspect, members.toSet()) } }
        either(excludes, EffectTemplate.Verb.EXCLUDES) { it.map { (aspect, members) -> Effect.Excludes(aspect, members.toSet()) } }
        either(restricts, EffectTemplate.Verb.RESTRICTS) { it.map { (aspect, bars) -> Effect.Restricts(aspect, bars) } }
        either(leans, EffectTemplate.Verb.LEANS) { spelled ->
            spelled.mapNotNull { (on, weights) ->
                val aspect = Aspect.byPage(on)
                when {
                    on == EffectTemplate.EVERY_PART -> Effect.LeansEverywhere(weights)
                    aspect != null -> Effect.Leans(aspect, weights)
                    else -> null.also { problems += "'$on' is no part of the world" }
                }
            }
        }
        mints?.let { templates += EffectTemplate.Fixed(Effect.Mints(it.pattern, it.unstated, it.flows, it.frequency)) }
        scalar(template, EffectTemplate.Verb.TEMPLATE, Effect::Template)
        scalar(madeOf, EffectTemplate.Verb.MADE_OF, Effect::MadeOf)
        scalar(bans, EffectTemplate.Verb.BANS, Effect::Bans)
        abundance?.ifLeft { taking(EffectTemplate.Verb.ABUNDANCE, it) }?.ifRight { templates += EffectTemplate.Fixed(Effect.Abundance(it)) }
        return when {
            problems.isNotEmpty() -> DataResult.error { problems.joinToString("; ") }
            templates.isEmpty() -> DataResult.error { "an effect object that holds no effect" }
            else -> DataResult.success(templates)
        }
    }

    companion object {
        /** [template] as an object of its own. Effects with no spelling — a link's arrival — are never written. */
        fun of(template: EffectTemplate): EffectSpelling = when (template) {
            is EffectTemplate.Taking -> taking(template)
            is EffectTemplate.Fixed -> fixed(template.effect)
        }

        private fun taking(template: EffectTemplate.Taking): EffectSpelling {
            val target = template.target
            return when (template.verb) {
                EffectTemplate.Verb.SETS -> EffectSpelling(sets = Either.left(target))
                EffectTemplate.Verb.BENDS -> EffectSpelling(bends = Either.left(target))
                EffectTemplate.Verb.CHOOSES -> EffectSpelling(chooses = Either.left(target))
                EffectTemplate.Verb.ADMITS -> EffectSpelling(admits = Either.left(target))
                EffectTemplate.Verb.EXCLUDES -> EffectSpelling(excludes = Either.left(target))
                EffectTemplate.Verb.RESTRICTS -> EffectSpelling(restricts = Either.left(target))
                EffectTemplate.Verb.LEANS -> EffectSpelling(leans = Either.left(target))
                EffectTemplate.Verb.TEMPLATE -> EffectSpelling(template = target)
                EffectTemplate.Verb.MADE_OF -> EffectSpelling(madeOf = target)
                EffectTemplate.Verb.BANS -> EffectSpelling(bans = target)
                EffectTemplate.Verb.ABUNDANCE -> EffectSpelling(abundance = Either.left(target))
            }
        }

        private fun fixed(effect: Effect): EffectSpelling = when (effect) {
            is Effect.Sets -> EffectSpelling(sets = Either.right(mapOf(effect.parameter.spelled to effect.value)))
            is Effect.Bends -> EffectSpelling(bends = Either.right(mapOf(effect.parameter.spelled to effect.value)))
            is Effect.Chooses -> EffectSpelling(chooses = Either.right(mapOf(effect.aspect to effect.member)))
            is Effect.Admits -> EffectSpelling(admits = Either.right(mapOf(effect.aspect to effect.members.toList())))
            is Effect.Excludes -> EffectSpelling(excludes = Either.right(mapOf(effect.aspect to effect.members.toList())))
            is Effect.Restricts -> EffectSpelling(restricts = Either.right(mapOf(effect.aspect to effect.bars)))
            is Effect.Leans -> EffectSpelling(leans = Either.right(mapOf(effect.aspect.page to effect.weights)))
            is Effect.LeansEverywhere -> EffectSpelling(leans = Either.right(mapOf(EffectTemplate.EVERY_PART to effect.weights)))
            is Effect.Mints -> EffectSpelling(mints = MintsSpelling(effect.pattern, effect.unstated, effect.flows, effect.frequency))
            is Effect.Template -> EffectSpelling(template = effect.name)
            is Effect.MadeOf -> EffectSpelling(madeOf = effect.substance)
            is Effect.Bans -> EffectSpelling(bans = effect.block)
            is Effect.Abundance -> EffectSpelling(abundance = Either.right(effect.rung))
            is Effect.LinksIn -> error("an arrival is made by its structure word, never written in a file")
        }

        private val PART: Codec<Aspect> = Parts.CODEC
        private val STRINGS = Codec.unboundedMap(Codec.STRING, Codec.STRING)
        private val WEIGHTS = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE)
        private fun <T> targetOr(fixed: Codec<T>): Codec<Either<String, T>> = Codec.either(Codec.STRING, fixed)
        private fun <T> Either<String, T>?.optional(): Optional<Either<String, T>> = Optional.ofNullable(this)

        val CODEC: Codec<EffectSpelling> = RecordCodecBuilder.create { instance ->
            instance.group(
                targetOr(STRINGS).optionalFieldOf("sets").forGetter { it.sets.optional() },
                targetOr(STRINGS).optionalFieldOf("bends").forGetter { it.bends.optional() },
                targetOr(Codec.unboundedMap(PART, Codec.STRING)).optionalFieldOf("chooses").forGetter { it.chooses.optional() },
                targetOr(Codec.unboundedMap(PART, Codec.STRING.listOf())).optionalFieldOf("admits").forGetter { it.admits.optional() },
                targetOr(Codec.unboundedMap(PART, Codec.STRING.listOf())).optionalFieldOf("excludes").forGetter { it.excludes.optional() },
                targetOr(Codec.unboundedMap(PART, Codec.unboundedMap(Codec.STRING, Bars.CODEC)))
                    .optionalFieldOf("restricts").forGetter { it.restricts.optional() },
                targetOr(Codec.unboundedMap(Codec.STRING, WEIGHTS)).optionalFieldOf("leans").forGetter { it.leans.optional() },
                MintsSpelling.CODEC.optionalFieldOf("mints").forGetter { Optional.ofNullable(it.mints) },
                Codec.STRING.optionalFieldOf("template").forGetter { Optional.ofNullable(it.template) },
                Codec.STRING.optionalFieldOf("made_of").forGetter { Optional.ofNullable(it.madeOf) },
                Codec.STRING.optionalFieldOf("bans").forGetter { Optional.ofNullable(it.bans) },
                targetOr(Codec.DOUBLE).optionalFieldOf("abundance").forGetter { it.abundance.optional() },
            ).apply(instance) { sets, bends, chooses, admits, excludes, restricts, leans, mints, template, madeOf, bans, abundance ->
                EffectSpelling(
                    sets.orElse(null), bends.orElse(null), chooses.orElse(null), admits.orElse(null), excludes.orElse(null),
                    restricts.orElse(null), leans.orElse(null), mints.orElse(null), template.orElse(null), madeOf.orElse(null),
                    bans.orElse(null), abundance.orElse(null),
                )
            }
        }
    }
}

/** A minting as a file spells it. */
private data class MintsSpelling(val pattern: String, val unstated: String?, val flows: Boolean, val frequency: Double?) {
    companion object {
        val CODEC: Codec<MintsSpelling> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.fieldOf("pattern").forGetter(MintsSpelling::pattern),
                Codec.STRING.optionalFieldOf("unstated").forGetter { Optional.ofNullable(it.unstated) },
                Codec.BOOL.optionalFieldOf("flows", false).forGetter(MintsSpelling::flows),
                Codec.DOUBLE.optionalFieldOf("frequency").forGetter { Optional.ofNullable(it.frequency) },
            ).apply(instance) { pattern, unstated, flows, frequency -> MintsSpelling(pattern, unstated.orElse(null), flows, frequency.orElse(null)) }
        }
    }
}
