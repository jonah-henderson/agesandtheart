package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.age.aspect.Bars
import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import java.util.Optional

/**
 * One concept a sense takes from the offers reaching it, which of its values, and what it makes of them.
 *
 * ```json
 * { "concept": "colour", "becomes": [ { "sets": "colour" } ] }
 * { "concept": "member", "only": { "woody": ">0.3" }, "becomes": [ { "chooses": "features" } ] }
 * { "concept": "block", "only": [ "#agesandtheart:whole_blocks" ], "becomes": [ { "made_of": "$" } ] }
 * { "concept": "direction", "folds": true }
 * ```
 */
data class Acceptance(val concept: Identifier, val only: ValueFilter = ValueFilter.Anything, val outcome: Outcome) {
    companion object {
        val CODEC: Codec<Acceptance> = AcceptanceSpelling.CODEC.flatXmap(AcceptanceSpelling::read) { DataResult.success(AcceptanceSpelling.of(it)) }
    }
}

/** Which values of a concept an acceptance takes. */
sealed interface ValueFilter {
    data object Anything : ValueFilter

    /** These keys, or members of these `#tags`, and no others: whole blocks for a structure. */
    data class OneOf(val keys: Set<String>) : ValueFilter

    /** Members whose tags clear these bars: what a group holds, `trees` everything woody. */
    data class Clearing(val bars: Map<String, Double>) : ValueFilter
}

/** What an acceptance makes of what it takes. */
sealed interface Outcome {
    /** Effects, said of the accepting sense's part. */
    data class Becomes(val effects: List<EffectTemplate>) : Outcome

    /** Carried inside the sense's own offer, for whatever takes that to use: `north` in `north rising sun`. */
    data object Folds : Outcome

    /** Offered on under another concept: a pace said beside turning colours is the colours' pace. */
    data class Renames(val concept: Identifier) : Outcome

    /** Offered on with its value scaled. */
    data class Scales(val factor: Double) : Outcome

    /** Taken, and nothing made of it. */
    data object Drops : Outcome
}

/** An acceptance as a file holds it: the concept, any filter, and exactly one outcome. */
private data class AcceptanceSpelling(
    val concept: Identifier,
    val only: Either<List<String>, Map<String, Double>>?,
    val becomes: List<EffectTemplate>?,
    val folds: Boolean,
    val renames: Identifier?,
    val scales: Double?,
    val drops: Boolean,
) {
    fun read(): DataResult<Acceptance> {
        val outcomes = listOfNotNull(
            becomes?.let { Outcome.Becomes(it) },
            Outcome.Folds.takeIf { folds },
            renames?.let { Outcome.Renames(it) },
            scales?.let { Outcome.Scales(it) },
            Outcome.Drops.takeIf { drops },
        )
        val outcome = outcomes.singleOrNull()
            ?: return DataResult.error { "an acceptance of ${PackNames.spell(concept)} names ${outcomes.size} outcomes, where it takes exactly one" }
        val filter = only?.map({ ValueFilter.OneOf(it.toSet()) }, { ValueFilter.Clearing(it) }) ?: ValueFilter.Anything
        return DataResult.success(Acceptance(concept, filter, outcome))
    }

    companion object {
        fun of(acceptance: Acceptance): AcceptanceSpelling {
            val only = when (val filter = acceptance.only) {
                ValueFilter.Anything -> null
                is ValueFilter.OneOf -> Either.left(filter.keys.toList())
                is ValueFilter.Clearing -> Either.right(filter.bars)
            }
            val outcome = acceptance.outcome
            return AcceptanceSpelling(
                concept = acceptance.concept,
                only = only,
                becomes = (outcome as? Outcome.Becomes)?.effects,
                folds = outcome == Outcome.Folds,
                renames = (outcome as? Outcome.Renames)?.concept,
                scales = (outcome as? Outcome.Scales)?.factor,
                drops = outcome == Outcome.Drops,
            )
        }

        private val FILTER: Codec<Either<List<String>, Map<String, Double>>> =
            Codec.either(Codec.STRING.listOf(), Codec.unboundedMap(Codec.STRING, Bars.CODEC))

        val CODEC: Codec<AcceptanceSpelling> = RecordCodecBuilder.create { instance ->
            instance.group(
                PackNames.CODEC.fieldOf("concept").forGetter(AcceptanceSpelling::concept),
                FILTER.optionalFieldOf("only").forGetter { Optional.ofNullable(it.only) },
                EffectTemplate.LIST_CODEC.optionalFieldOf("becomes").forGetter { Optional.ofNullable(it.becomes) },
                Codec.BOOL.optionalFieldOf("folds", false).forGetter(AcceptanceSpelling::folds),
                PackNames.CODEC.optionalFieldOf("renames").forGetter { Optional.ofNullable(it.renames) },
                Codec.DOUBLE.optionalFieldOf("scales").forGetter { Optional.ofNullable(it.scales) },
                Codec.BOOL.optionalFieldOf("drops", false).forGetter(AcceptanceSpelling::drops),
            ).apply(instance) { concept, only, becomes, folds, renames, scales, drops ->
                AcceptanceSpelling(concept, only.orElse(null), becomes.orElse(null), folds, renames.orElse(null), scales.orElse(null), drops)
            }
        }
    }
}
