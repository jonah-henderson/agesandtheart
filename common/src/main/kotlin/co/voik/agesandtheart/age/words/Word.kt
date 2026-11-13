package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.age.language.Speech
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.DynamicOps
import com.mojang.serialization.MapCodec
import com.mojang.serialization.MapLike
import com.mojang.serialization.RecordBuilder
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import java.util.Optional
import java.util.stream.Stream

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

    /**
     * This word with every sense of [other] the neighbours could tell from its own ([Sense.readsLike]) after them, its own
     * default still the default.
     */
    fun withSensesOf(other: Word): Word {
        val added = other.senses.filter { theirs -> senses.none { it.readsLike(theirs) } }.map { it.copy(isDefault = false) }
        if (added.isEmpty()) return this
        val default = defaultSense
        val own = if (default != null && !default.isDefault) senses.map { it.copy(isDefault = it === default) } else senses
        return copy(senses = own + added, speech = speech ?: other.speech)
    }

    companion object {
        private const val SENSES_KEY = "senses"

        /** Its senses: listed under [SENSES_KEY] where the file has one, so that a list that does not read is reported, else the one at the top. */
        private val SENSES: MapCodec<List<Sense>> = object : MapCodec<List<Sense>>() {
            private val listed = Sense.MAP_CODEC.codec().listOf().fieldOf(SENSES_KEY)

            override fun <T> keys(ops: DynamicOps<T>): Stream<T> = Stream.concat(listed.keys(ops), Sense.MAP_CODEC.keys(ops))

            override fun <T> decode(ops: DynamicOps<T>, input: MapLike<T>): DataResult<List<Sense>> =
                if (input.get(SENSES_KEY) != null) listed.decode(ops, input) else Sense.MAP_CODEC.decode(ops, input).map(::listOf)

            override fun <T> encode(senses: List<Sense>, ops: DynamicOps<T>, prefix: RecordBuilder<T>): RecordBuilder<T> {
                val only = senses.singleOrNull() ?: return listed.encode(senses, ops, prefix)
                return Sense.MAP_CODEC.encode(only, ops, prefix)
            }
        }

        fun mapCodec(id: Identifier): MapCodec<Word> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                SENSES.forGetter(Word::senses),
                Speech.CODEC.optionalFieldOf("speech").forGetter { Optional.ofNullable(it.speech) },
                Codec.intRange(0, Int.MAX_VALUE).optionalFieldOf("price").forGetter { Optional.ofNullable(it.price) },
            ).apply(instance) { senses, speech, price ->
                Word(id, senses, speech.orElse(null), price.orElse(null))
            }
        }
    }
}
