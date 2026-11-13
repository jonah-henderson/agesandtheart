package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.age.language.Speech
import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import java.util.Optional

/**
 * A page of the Art: the senses it can be read in. A word of one sense writes it at the top of its file; a word of several
 * lists them under `senses`.
 *
 * ```json
 * { "speech": "adjective", "offers": [ { "concept": "colour", "value": "red" } ] }
 * { "speech": "plural", "senses": [ { … the feature … }, { … the furniture …, "default": true } ] }
 * ```
 */
data class Word(
    /** Where the word was defined. Its path is what a writer says: `agesandtheart:frozen` is "frozen". */
    val id: Identifier,
    val senses: List<Sense>,
    /** How a book's prose speaks it. */
    val speech: Speech? = null,
    /** What it costs to write, where its file says rather than its offers. */
    val price: Int? = null,
) {
    val name: String get() = id.path

    /** The sense read where its neighbours fit more than one: the one marked, or its only one. */
    val defaultSense: Sense? get() = senses.singleOrNull(Sense::isDefault) ?: senses.singleOrNull()

    companion object {
        private val SENSES: MapCodec<Either<List<Sense>, Sense>> =
            Codec.mapEither(Sense.MAP_CODEC.codec().listOf().fieldOf("senses"), Sense.MAP_CODEC)

        fun mapCodec(id: Identifier): MapCodec<Word> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                SENSES.forGetter { word -> word.senses.singleOrNull()?.let { Either.right(it) } ?: Either.left(word.senses) },
                Speech.CODEC.optionalFieldOf("speech").forGetter { Optional.ofNullable(it.speech) },
                Codec.intRange(0, Int.MAX_VALUE).optionalFieldOf("price").forGetter { Optional.ofNullable(it.price) },
            ).apply(instance) { senses, speech, price ->
                Word(id, senses.map({ it }, ::listOf), speech.orElse(null), price.orElse(null))
            }
        }
    }
}
