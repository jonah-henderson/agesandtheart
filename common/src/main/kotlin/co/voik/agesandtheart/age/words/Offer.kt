package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.age.aspect.Aspect
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import java.util.Optional

/**
 * One meaning a sense offers the words after it: a concept, its value, how likely it is to apply, and who may take it.
 *
 * ```json
 * { "concept": "colour", "value": "red" }
 * { "concept": "colour", "value": "dark_red", "for": "moon", "chance": 0.6 }
 * ```
 */
data class Offer(
    val concept: Identifier,
    val value: Value = Value.None,
    val chance: Double = CERTAIN,
    val audience: Audience = Audience.Anyone,
) {
    companion object {
        const val CERTAIN = 1.0

        val CODEC: Codec<Offer> = RecordCodecBuilder.create<Offer> { instance ->
            instance.group(
                PackNames.CODEC.fieldOf("concept").forGetter(Offer::concept),
                Value.CODEC.optionalFieldOf("value").forGetter { Optional.ofNullable(it.value.takeIf { value -> value != Value.None }) },
                Codec.doubleRange(0.0, CERTAIN).optionalFieldOf("chance", CERTAIN).forGetter(Offer::chance),
                Audience.CODEC.optionalFieldOf("for", Audience.Anyone).forGetter(Offer::audience),
            ).apply(instance) { concept, value, chance, audience -> Offer(concept, value.orElse(Value.None), chance, audience) }
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
