package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.resolution.Effect
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import java.util.Optional

/**
 * One meaning a sense offers the words after it: a concept, its value, how likely it is to apply, and who may take it.
 * It applies on [chance] where a thing or a part's page takes it, and on [chanceFromWholeAge] where `age` hands it
 * out, which is the same unless the meaning is incidental to the word: `red`'s stone, one Age in ten.
 *
 * **A reading** is the last resort, for what only this word means of one part and no shared concept says: it carries
 * its own effects ([reading]) in place of a concept, and is taken by that part's page or any thing of it — `molten`
 * said of the underground opens magma chambers.
 *
 * ```json
 * { "concept": "colour", "value": "red" }
 * { "concept": "colour", "value": "dark_red", "for": "moon", "chance": 0.6 }
 * { "concept": "block", "value": "#red", "chance_from_whole_age": 0.1 }
 * { "for": "underground", "means": [ { "admits": { "features": [ "agesandtheart:magma_chamber" ] } } ] }
 * ```
 */
data class Offer(
    val concept: Identifier,
    val value: Value = Value.None,
    val chance: Double = CERTAIN,
    val audience: Audience = Audience.Anyone,
    /** Its chance where `age` hands it out, where it says one; null follows [chance]. */
    val ownChanceFromWholeAge: Double? = null,
    val reading: List<Effect> = emptyList(),
) {
    val chanceFromWholeAge: Double get() = ownChanceFromWholeAge ?: chance

    val isAReading: Boolean get() = reading.isNotEmpty()

    companion object {
        const val NEVER = 0.0
        const val CERTAIN = 1.0

        /** A reading for [part]: what this word means of it, carried whole. */
        fun reading(part: Aspect, effects: List<Effect>, chance: Double = CERTAIN, chanceFromWholeAge: Double? = null) =
            Offer(CoreConcepts.READING, Value.None, chance, Audience.Part(part), chanceFromWholeAge, effects)

        private val CHANCE: Codec<Double> = Codec.doubleRange(NEVER, CERTAIN)

        private val SPELLING: Codec<Offer> = RecordCodecBuilder.create<Offer> { instance ->
            instance.group(
                PackNames.CODEC.optionalFieldOf("concept").forGetter { Optional.ofNullable(it.concept.takeUnless { _ -> it.isAReading }) },
                Value.CODEC.optionalFieldOf("value").forGetter { Optional.ofNullable(it.value.takeIf { value -> value != Value.None }) },
                CHANCE.optionalFieldOf("chance", CERTAIN).forGetter(Offer::chance),
                Audience.CODEC.optionalFieldOf("for", Audience.Anyone).forGetter(Offer::audience),
                CHANCE.optionalFieldOf("chance_from_whole_age").forGetter { Optional.ofNullable(it.ownChanceFromWholeAge) },
                Sense.FIXED_EFFECTS.optionalFieldOf("means", emptyList()).forGetter(Offer::reading),
            ).apply(instance) { concept, value, chance, audience, fromWholeAge, reading ->
                Offer(concept.orElse(CoreConcepts.READING), value.orElse(Value.None), chance, audience, fromWholeAge.orElse(null), reading)
            }
        }

        val CODEC: Codec<Offer> = SPELLING.validate(::wellFormed)

        /** An offer names a concept or carries a reading, never both, and a reading is for one part. */
        private fun wellFormed(offer: Offer): DataResult<Offer> {
            val namesItsConcept = offer.concept != CoreConcepts.READING
            return when {
                offer.isAReading && namesItsConcept -> DataResult.error { "an offer names a concept or carries a reading, not both" }
                !offer.isAReading && !namesItsConcept -> DataResult.error { "an offer names neither a concept nor a reading" }
                offer.isAReading && offer.audience !is Audience.Part -> DataResult.error { "a reading is for one part: say which with 'for'" }
                else -> DataResult.success(offer)
            }
        }
    }
}

/** Who may take an offer: anyone that accepts its concept, only the Age, or only one part of the world and its members. */
sealed interface Audience {
    data object Anyone : Audience

    /** What only the Age takes — no sun, a template, a ban — and so passes the things it meets on the way. */
    data object TheAge : Audience

    /** A mood word's share for one part: `foreboding`'s colour for the moon. */
    data class Part(val aspect: Aspect) : Audience

    companion object {
        private const val THE_AGE = "age"
        private const val ANYONE = "anyone"

        val CODEC: Codec<Audience> = Codec.STRING.comapFlatMap(::read, ::spell)

        private fun read(spelled: String): DataResult<Audience> {
            if (spelled == ANYONE) return DataResult.success(Anyone)
            if (spelled == THE_AGE) return DataResult.success(TheAge)
            val aspect = Aspect.byPage(spelled) ?: return DataResult.error { "'$spelled' is neither the age nor a part of the world" }
            return DataResult.success(Part(aspect))
        }

        private fun spell(audience: Audience): String = when (audience) {
            Anyone -> ANYONE
            TheAge -> THE_AGE
            is Part -> audience.aspect.page
        }
    }
}
