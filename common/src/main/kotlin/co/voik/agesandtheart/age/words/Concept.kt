package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.age.aspect.Span
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import net.minecraft.util.StringRepresentable

/**
 * What an offer and an acceptance meet on — `colour`, `size`, `block` — and the kind of value it carries. Pack data,
 * one file per concept under `art/concept/`.
 */
data class Concept(val id: Identifier, val values: ValueType, val joined: JoinedPair) {
    companion object {
        fun mapCodec(id: Identifier): MapCodec<Concept> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                ValueType.CODEC.fieldOf("value").forGetter(Concept::values),
                JoinedPair.CODEC.optionalFieldOf("joined", JoinedPair.EITHER).forGetter(Concept::joined),
            ).apply(instance) { values, joined -> Concept(id, values, joined) }
        }
    }
}

/** What a concept's values are, so that an offer's value is checked as it loads. */
enum class ValueType(private val key: String) : StringRepresentable {
    /** A number or a range of them: `-0.3`, `0.5..1.0`. */
    SPAN("span"),

    /** One of a parameter's named options: `white`, `north`. */
    OPTION("option"),

    /** A block, or a `#tag` of blocks. */
    BLOCK("block"),

    /** A member of a part of the world, or a `#tag` of them. */
    MEMBER("member"),

    /** Tags or members, and how strongly to lean toward each. */
    WEIGHTS("weights"),

    /** Tags, and the bar each must clear. */
    BARS("bars"),

    /** A multiple of what there would be: `4.0` for teeming. */
    AMOUNT("amount"),

    /** No value: the concept is the whole of it, as `porous` is. */
    FLAG("flag"),
    ;

    /** Whether [value] is one of this type's. */
    fun admits(value: Value): Boolean = when (this) {
        SPAN -> value is Value.Text && (value.text.toDoubleOrNull() != null || Span.read(value.text) != null)
        OPTION -> value is Value.Text && !value.isTag
        BLOCK, MEMBER -> value is Value.Text && Identifier.tryParse(value.text.removePrefix(Value.TAG_MARK)) != null
        WEIGHTS -> value is Value.Weights
        BARS -> value is Value.Bars
        AMOUNT -> value is Value.Amount
        FLAG -> value == Value.None
    }

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<ValueType> = StringRepresentable.fromEnum(ValueType::values)
    }
}

/**
 * What a part does with two values of one concept that `and` joined, where it can hold only one: they divide it between
 * them, blend into one, alternate, or one is drawn and the other dropped.
 */
enum class JoinedPair(private val key: String) : StringRepresentable {
    DIVIDE("divide"),
    BLEND("blend"),
    ALTERNATE("alternate"),
    EITHER("either"),
    ;

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<JoinedPair> = StringRepresentable.fromEnum(JoinedPair::values)
    }
}
