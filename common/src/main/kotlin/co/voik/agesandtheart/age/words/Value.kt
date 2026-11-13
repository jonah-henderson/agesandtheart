package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.age.aspect.Bars as BarSpelling
import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult

/** A value as a file spells it: a string, a number, a map of numbers, a map of bars, or a map of parts. */
private typealias Spelled = Either<String, Either<Double, Either<Map<String, Double>, Either<Map<String, Double>, Map<String, String>>>>>

/** What an offer carries of its concept: `red` for a colour, `4.0` for an amount, nothing for `porous`. */
sealed interface Value {
    /** A span, an option, a block or a member, or a `#tag` of blocks or members. */
    data class Text(val text: String) : Value {
        val isTag: Boolean get() = text.startsWith(TAG_MARK)
    }

    /** A multiple of what there would be. */
    data class Amount(val multiple: Double) : Value

    /** Tags or members, and how strongly to lean toward each. */
    data class Weights(val weights: Map<String, Double>) : Value

    /** Tags, and the bar each must clear, signed as `Bars` reads one. */
    data class Bars(val bars: Map<String, Double>) : Value

    /**
     * Some of the parts of a concept made of parts ([ValueType.PARTS]) — wear's way and amount — each word giving what it
     * gives, the parts said of one thing combining. [measure] is the part a setting takes, once the concept has filled
     * in its defaults ([Concept.filled]).
     */
    data class Parts(val parts: Map<String, String>, val measure: String? = null) : Value {
        /** Whether one of its parts is [choice]: what an effect chosen by a part asks (`"when": "porous"`). */
        fun names(choice: String): Boolean = choice in parts.values

        /** This and [other] as one, where they give no part twice; null where they would. */
        fun with(other: Parts): Parts? = if (parts.keys.none { it in other.parts }) Parts(parts + other.parts) else null
    }

    /** No value: the concept is the whole of it. */
    data object None : Value

    companion object {
        const val TAG_MARK = "#"

        private val WEIGHTS: Codec<Map<String, Double>> = Codec.unboundedMap(Codec.STRING, Codec.DOUBLE)
        private val BARS: Codec<Map<String, Double>> = Codec.unboundedMap(Codec.STRING, BarSpelling.CODEC)
        private val PARTS: Codec<Map<String, String>> = Codec.unboundedMap(Codec.STRING, Codec.STRING)

        private val SPELLED: Codec<Spelled> =
            Codec.either(Codec.STRING, Codec.either(Codec.DOUBLE, Codec.either(WEIGHTS, Codec.either(BARS, PARTS))))

        /**
         * A value as a file holds it: a string, a number, a map of numbers, a map of bars (`">0.3"`), or a map of parts
         * (`{ "way": "porous" }`) — read in that order, so a map of words that are not bars is parts.
         */
        val CODEC: Codec<Value> = SPELLED.flatComapMap(::read, ::spell)

        private fun read(spelled: Spelled): Value =
            spelled.map(
                { Text(it) },
                { number -> number.map({ Amount(it) }, { maps -> maps.map({ Weights(it) }, { named -> named.map({ Bars(it) }, { Parts(it) }) }) }) },
            )

        private fun spell(value: Value): DataResult<Spelled> =
            when (value) {
                is Text -> DataResult.success(Either.left(value.text))
                is Amount -> DataResult.success(Either.right(Either.left(value.multiple)))
                is Weights -> DataResult.success(Either.right(Either.right(Either.left(value.weights))))
                is Bars -> DataResult.success(Either.right(Either.right(Either.right(Either.left(value.bars)))))
                is Parts -> DataResult.success(Either.right(Either.right(Either.right(Either.right(value.parts)))))
                None -> DataResult.error { "no value is written as no value at all" }
            }
    }
}
