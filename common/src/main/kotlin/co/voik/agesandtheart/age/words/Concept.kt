package co.voik.agesandtheart.age.words

import co.voik.agesandtheart.age.aspect.Span
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import net.minecraft.util.StringRepresentable

/**
 * What an offer and an acceptance meet on — `colour`, `size`, `block` — the kind of value it carries, and the ink a word
 * pays for offering it for certain. Pack data, one file per concept under `art/concept/`. A concept made of [parts]
 * takes its value a part at a time, each word giving what it gives and the rest its default: wear's way and amount.
 */
data class Concept(
    val id: Identifier,
    val values: ValueType,
    val joined: JoinedPair,
    val price: Int = 0,
    val parts: Map<String, ConceptPart> = emptyMap(),
) {
    /** [value] with every part it does not give at its default, and the part a setting takes as its measure. */
    fun filled(value: Value.Parts): Value.Parts {
        val whole = parts.mapValues { (name, part) -> value.parts[name] ?: part.default }
        val measure = parts.entries.firstOrNull { it.value.values == ValueType.SPAN }?.let { whole[it.key] }
        return Value.Parts(whole, measure)
    }

    /** What is wrong with [value] as one of this concept's, or null where nothing is. */
    fun problemWith(value: Value.Parts): String? {
        val unknown = value.parts.keys - parts.keys
        if (unknown.isNotEmpty()) return "it has no part ${unknown.joinToString()}"
        return value.parts.firstNotNullOfOrNull { (name, given) -> parts.getValue(name).problemWith(given)?.let { "its $name $it" } }
    }

    companion object {
        fun mapCodec(id: Identifier): MapCodec<Concept> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                ValueType.CODEC.fieldOf("value").forGetter(Concept::values),
                JoinedPair.CODEC.optionalFieldOf("joined", JoinedPair.EITHER).forGetter(Concept::joined),
                Codec.intRange(0, Int.MAX_VALUE).optionalFieldOf("price", 0).forGetter(Concept::price),
                Codec.unboundedMap(Codec.STRING, ConceptPart.CODEC).optionalFieldOf("parts", emptyMap()).forGetter(Concept::parts),
            ).apply(instance) { values, joined, price, parts -> Concept(id, values, joined, price, parts) }
        }
    }
}

/** One part of a concept made of parts: a span or one of its [options], and what it is where no word gives it. */
data class ConceptPart(val values: ValueType, val default: String, val options: List<String> = emptyList()) {
    fun problemWith(given: String): String? = when {
        options.isNotEmpty() && given !in options -> "'$given' is none of ${options.joinToString()}"
        !values.admits(Value.Text(given)) -> "'$given' is no ${values.serializedName}"
        else -> null
    }

    companion object {
        val CODEC: Codec<ConceptPart> = RecordCodecBuilder.create { instance ->
            instance.group(
                ValueType.CODEC.fieldOf("value").forGetter(ConceptPart::values),
                Codec.STRING.fieldOf("default").forGetter(ConceptPart::default),
                Codec.STRING.listOf().optionalFieldOf("options", emptyList()).forGetter(ConceptPart::options),
            ).apply(instance, ::ConceptPart)
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

    /** Some of the concept's parts, each word giving what it gives: wear's way and amount. */
    PARTS("parts"),
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
        PARTS -> value is Value.Parts
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
