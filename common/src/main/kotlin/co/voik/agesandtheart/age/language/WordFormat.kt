package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.ParameterKey
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Bars
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import java.util.Optional

/**
 * How a word is written in `art/word/<name>.json`: a list of meanings, each holding its effects under the same keys
 * the effects are named for, with how likely it is to apply beside them.
 *
 * ```json
 * { "speech": "plural",
 *   "meanings": [
 *     { "sets": { "temperature": "-0.95..-0.45" }, "must_always_apply": true },
 *     { "sets": { "sky.colour": "white" }, "chance_to_apply": 0.4 },
 *     { "leans": { "biomes": { "minecraft:ice_spikes": 1.0 } } } ] }
 * ```
 *
 * A file's `_comment` is the author's and is ignored here.
 */
object WordFormat {

    fun mapCodec(id: Identifier): MapCodec<Word> = RecordCodecBuilder.mapCodec { instance ->
        instance.group(
            MEANING.listOf().optionalFieldOf("meanings", emptyList()).forGetter(Word::meanings),
            Speech.CODEC.optionalFieldOf("speech").forGetter { Optional.ofNullable(it.speech) },
        ).apply(instance) { meanings, speech -> Word(id, meanings, speech.orElse(null)) }
    }

    private const val EVERYWHERE = "all"

    private val ASPECT: Codec<Aspect> = Codec.STRING.comapFlatMap(
        { page -> Aspect.byPage(page)?.let { DataResult.success(it) } ?: DataResult.error { "'$page' is no part of the world" } },
        Aspect::page,
    )

    /** What a minting is written as. */
    private data class MintsFile(val pattern: String, val unstated: String?, val flows: Boolean) {
        companion object {
            val CODEC: Codec<MintsFile> = RecordCodecBuilder.create { instance ->
                instance.group(
                    Codec.STRING.fieldOf("pattern").forGetter(MintsFile::pattern),
                    Codec.STRING.optionalFieldOf("unstated").forGetter { Optional.ofNullable(it.unstated) },
                    Codec.BOOL.optionalFieldOf("flows", false).forGetter(MintsFile::flows),
                ).apply(instance) { pattern, unstated, flows -> MintsFile(pattern, unstated.orElse(null), flows) }
            }
        }
    }

    /** A meaning as its file holds it: each kind of effect under its own key. */
    private data class MeaningFile(
        val sets: Map<String, String> = emptyMap(),
        val bends: Map<String, String> = emptyMap(),
        val chooses: Map<Aspect, String> = emptyMap(),
        val admits: Map<Aspect, List<String>> = emptyMap(),
        val excludes: Map<Aspect, List<String>> = emptyMap(),
        val restricts: Map<Aspect, Map<String, Double>> = emptyMap(),
        val leans: Map<String, Map<String, Double>> = emptyMap(),
        val mints: MintsFile? = null,
        val template: String? = null,
        val abundance: Double? = null,
        val chanceToApply: Double = Meaning.CERTAIN,
        val unaimedChanceToApply: Double? = null,
        val mustAlwaysApply: Boolean = false,
        val asserts: Boolean = false,
        val offeredTo: List<String> = emptyList(),
    ) {
        fun toMeaning(): DataResult<Meaning> {
            val leanings = leans.map { (on, weights) ->
                if (on == EVERYWHERE) {
                    Effect.LeansEverywhere(weights)
                } else {
                    val aspect = Aspect.byPage(on) ?: return DataResult.error { "'$on' is no part of the world" }
                    Effect.Leans(aspect, weights)
                }
            }
            val effects = buildList {
                sets.forEach { (key, value) -> add(Effect.Sets(ParameterKey.read(key), value)) }
                bends.forEach { (key, value) -> add(Effect.Bends(ParameterKey.read(key), value)) }
                chooses.forEach { (aspect, member) -> add(Effect.Chooses(aspect, member)) }
                admits.forEach { (aspect, members) -> add(Effect.Admits(aspect, members.toSet())) }
                excludes.forEach { (aspect, members) -> add(Effect.Excludes(aspect, members.toSet())) }
                restricts.forEach { (aspect, bars) -> add(Effect.Restricts(aspect, bars)) }
                addAll(leanings)
                mints?.let { add(Effect.Mints(it.pattern, it.unstated, it.flows)) }
                template?.let { add(Effect.Template(it)) }
                abundance?.let { add(Effect.Abundance(it)) }
            }
            if (effects.isEmpty()) return DataResult.error { "a meaning that does nothing" }
            val chancesInRange = listOfNotNull(chanceToApply, unaimedChanceToApply).all { it in Meaning.NEVER..Meaning.CERTAIN }
            if (!chancesInRange) return DataResult.error { "a chance to apply is a share, from 0 to 1" }
            val alwaysAndSometimes = mustAlwaysApply && (chanceToApply != Meaning.CERTAIN || unaimedChanceToApply != null)
            if (alwaysAndSometimes) return DataResult.error { "a meaning that must always apply has no chance to give" }
            return DataResult.success(Meaning(effects, chanceToApply, unaimedChanceToApply, mustAlwaysApply, asserts, offeredTo.toSet()))
        }

        companion object {
            fun of(meaning: Meaning): MeaningFile {
                val effects = meaning.effects
                fun <T : Effect> all(kind: Class<T>): List<T> = effects.filterIsInstance(kind)
                return MeaningFile(
                    sets = all(Effect.Sets::class.java).associate { it.parameter.spelled to it.value },
                    bends = all(Effect.Bends::class.java).associate { it.parameter.spelled to it.value },
                    chooses = all(Effect.Chooses::class.java).associate { it.aspect to it.member },
                    admits = all(Effect.Admits::class.java).associate { it.aspect to it.members.toList() },
                    excludes = all(Effect.Excludes::class.java).associate { it.aspect to it.members.toList() },
                    restricts = all(Effect.Restricts::class.java).associate { it.aspect to it.bars },
                    leans = all(Effect.Leans::class.java).associate { it.aspect.page to it.weights } +
                        all(Effect.LeansEverywhere::class.java).associate { EVERYWHERE to it.weights },
                    mints = all(Effect.Mints::class.java).firstOrNull()?.let { MintsFile(it.pattern, it.unstated, it.flows) },
                    template = all(Effect.Template::class.java).firstOrNull()?.name,
                    abundance = all(Effect.Abundance::class.java).firstOrNull()?.rung,
                    chanceToApply = meaning.chanceToApply,
                    unaimedChanceToApply = meaning.unaimedChanceToApply,
                    mustAlwaysApply = meaning.mustAlwaysApply,
                    asserts = meaning.asserts,
                    offeredTo = meaning.offeredTo.toList(),
                )
            }

            private val STRINGS = Codec.unboundedMap(Codec.STRING, Codec.STRING)
            private val MEMBERS = Codec.unboundedMap(ASPECT, Codec.STRING.listOf())
            private val WEIGHTS = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE)

            val CODEC: Codec<MeaningFile> = RecordCodecBuilder.create { instance ->
                instance.group(
                    STRINGS.optionalFieldOf("sets", emptyMap()).forGetter(MeaningFile::sets),
                    STRINGS.optionalFieldOf("bends", emptyMap()).forGetter(MeaningFile::bends),
                    Codec.unboundedMap(ASPECT, Codec.STRING).optionalFieldOf("chooses", emptyMap()).forGetter(MeaningFile::chooses),
                    MEMBERS.optionalFieldOf("admits", emptyMap()).forGetter(MeaningFile::admits),
                    MEMBERS.optionalFieldOf("excludes", emptyMap()).forGetter(MeaningFile::excludes),
                    Codec.unboundedMap(ASPECT, Codec.unboundedMap(Codec.STRING, Bars.CODEC))
                        .optionalFieldOf("restricts", emptyMap()).forGetter(MeaningFile::restricts),
                    Codec.unboundedMap(Codec.STRING, WEIGHTS).optionalFieldOf("leans", emptyMap()).forGetter(MeaningFile::leans),
                    MintsFile.CODEC.optionalFieldOf("mints").forGetter { Optional.ofNullable(it.mints) },
                    Codec.STRING.optionalFieldOf("template").forGetter { Optional.ofNullable(it.template) },
                    Codec.DOUBLE.optionalFieldOf("abundance").forGetter { Optional.ofNullable(it.abundance) },
                    Codec.DOUBLE.optionalFieldOf("chance_to_apply", Meaning.CERTAIN).forGetter(MeaningFile::chanceToApply),
                    Codec.DOUBLE.optionalFieldOf("unaimed_chance_to_apply")
                        .forGetter { Optional.ofNullable(it.unaimedChanceToApply) },
                    Codec.BOOL.optionalFieldOf("must_always_apply", false).forGetter(MeaningFile::mustAlwaysApply),
                    Codec.BOOL.optionalFieldOf("asserts", false).forGetter(MeaningFile::asserts),
                    Codec.STRING.listOf().optionalFieldOf("offered_to", emptyList()).forGetter(MeaningFile::offeredTo),
                ).apply(instance) { sets, bends, chooses, admits, excludes, restricts, leans, mints, template, abundance, chance,
                                    unaimedChance, mustAlwaysApply, asserts, offeredTo ->
                    MeaningFile(
                        sets, bends, chooses, admits, excludes, restricts, leans, mints.orElse(null), template.orElse(null),
                        abundance.orElse(null), chance, unaimedChance.orElse(null), mustAlwaysApply, asserts, offeredTo,
                    )
                }
            }
        }
    }

    private val MEANING: Codec<Meaning> = MeaningFile.CODEC.flatXmap(MeaningFile::toMeaning) { DataResult.success(MeaningFile.of(it)) }
}
