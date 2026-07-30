package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.Tier
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.StringRepresentable
import java.util.Optional

/**
 * The four ways a sentence can be at odds with the world, in ascending order of how much trouble it is.
 *
 * Registers rather than one severity dial: "nothing carries this word" and "an earlier word crowded it
 * out" look identical from outside and must not be charged as the same complaint.
 */
enum class Register(
    val key: String,
    /** What this costs before precision is taken into account. Only has to order sensibly for now. */
    private val base: Int,
) : StringRepresentable {
    /** Two words meant opposite things and the world honoured both anyway. */
    TENSION("tension", base = 1),

    /**
     * An aspect had to break into fragments to honour everything asked of it.
     *
     * A fracture, never a division: an aspect also divides because a vague word simply *liked* two
     * answers, which is harmony and charged nothing (see [Aspect.appetiteForCompany]). Only the charged
     * case is a flaw.
     */
    FRACTURE("fracture", base = 1),

    /** A word lost outright: something else already had the aspect, and the aspect cannot divide. */
    DISPLACED("displaced", base = 2),

    /**
     * Nothing in the aspect could ever be what the word asked for.
     *
     * A content bug rather than a play outcome (design §3.3) — `VocabularyCheck` keeps it from shipping.
     * A register anyway, because a word that slips through must be reported, never silently dropped.
     */
    UNBACKED("unbacked", base = 4),
    ;

    /** What a flaw of this kind costs when the word that caused it was written at [tier]. */
    fun charge(tier: Tier): Int = base * tier.weight

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<Register> = StringRepresentable.fromEnum(Register::values)
    }
}

/**
 * One thing wrong with a sentence, kept in full rather than reduced to a number, so that a wound can be
 * sited at the contradiction that caused it (design §5.1).
 *
 * Stored structurally; the English is generated at reading time.
 */
data class Flaw(
    val register: Register,
    /** The words involved, most responsible first. One for a word that failed alone, two for a fight. */
    val words: List<String>,
    /** Where it happened, or null for a flaw the whole sentence owns. */
    val aspect: Aspect?,
    /** The tags that disagreed, where two did. */
    val tags: List<String>,
    /** What it cost, frozen — so retuning the charges cannot rewrite an Age already written. */
    val severity: Int,
) {
    /** How to say it to a writer. */
    fun describe(): String {
        val quoted = words.map { "'$it'" }
        val both = quoted.joinToString(" and ")
        val over = if (tags.size == 2) " (${tags[0]} against ${tags[1]})" else ""
        val where = aspect?.key ?: "the sentence"
        return when (register) {
            Register.TENSION -> "$both pull opposite ways in $where$over, and the world made room for both"
            Register.FRACTURE -> "the $where fractured so that $both could both stand$over"
            Register.DISPLACED -> "${quoted.firstOrNull() ?: "a word"} was crowded out of $where" +
                quoted.drop(1).firstOrNull().orEmpty().let { if (it.isEmpty()) "" else " by $it" } + over
            Register.UNBACKED -> "nothing in $where can be ${quoted.firstOrNull() ?: "that"} — " +
                "a gap in the vocabulary, not something you did"
        }
    }

    override fun toString(): String = "${register.key} +$severity: ${describe()}"

    companion object {
        val CODEC: Codec<Flaw> = RecordCodecBuilder.create { instance ->
            instance.group(
                Register.CODEC.fieldOf("register").forGetter(Flaw::register),
                Codec.STRING.listOf().fieldOf("words").forGetter(Flaw::words),
                ASPECT_CODEC.optionalFieldOf("aspect").forGetter { Optional.ofNullable(it.aspect) },
                Codec.STRING.listOf().optionalFieldOf("tags", emptyList()).forGetter(Flaw::tags),
                Codec.INT.fieldOf("severity").forGetter(Flaw::severity),
            ).apply(instance) { register, words, aspect, tags, severity ->
                Flaw(register, words, aspect.orElse(null), tags, severity)
            }
        }
    }
}

private val ASPECT_CODEC: Codec<Aspect> = StringRepresentable.fromEnum(Aspect::values)

/**
 * How far an Age is at odds with itself, and why. Part of the recipe, and never re-derived (design §4.6).
 *
 * The index is the sum of the flaws rather than a field of its own, so the number and the reasons cannot
 * disagree.
 */
data class Instability(val flaws: List<Flaw>) {
    val index: Int get() = flaws.sumOf { it.severity }

    val isCoherent: Boolean get() = flaws.isEmpty()

    override fun toString(): String = if (isCoherent) "coherent" else "instability $index"

    companion object {
        /** A sentence the world had no argument with. Also what every Age written before words had. */
        val NONE = Instability(emptyList())

        val MAP_CODEC: MapCodec<Instability> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Flaw.CODEC.listOf().optionalFieldOf("flaws", emptyList()).forGetter(Instability::flaws),
            ).apply(instance, ::Instability)
        }

        val CODEC: Codec<Instability> = MAP_CODEC.codec()
    }
}
