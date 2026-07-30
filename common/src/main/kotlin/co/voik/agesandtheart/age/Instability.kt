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
 * Registers rather than one severity dial, because they are *different facts about a sentence* and a
 * writer needs to be told which one happened. Two of them are the spike's hardest-won lesson: "nothing
 * carries this word" and "an earlier word crowded it out" look identical from the outside and must not be
 * charged as though they were the same complaint.
 */
enum class Register(
    val key: String,
    /**
     * What this costs before precision is taken into account. Deliberately small — punishment for
     * incoherence is *not harsh* (design §2): a world that tells you something is wrong, not one that
     * takes your afternoon away. §5 and Phase 6 decide what an index of 6 versus 20 actually *does*;
     * these numbers only have to order sensibly against each other until then.
     */
    private val base: Int,
) : StringRepresentable {
    /**
     * Two words meant opposite things and the world honoured both anyway — cherry grove abutting desert.
     *
     * The gentlest register, and pointedly not a punishment: this is the generator doing the most
     * interesting thing it knows how to do. It is charged at all only because a writer *did* write a
     * tension, and §3.5 needs that to cost something even where the world absorbed it.
     */
    TENSION("tension", base = 1),

    /**
     * A aspect had to **break into fragments** to honour everything asked of it.
     *
     * The world is intact and both words are in it, but the sentence described a place that cannot be one
     * place — so the ground shears mid-air at a boundary, or a sea of water meets a sea of lava at one
     * level. Exactly the impossible geometry the set-valued aspects exist to produce.
     *
     * **Called a fracture rather than a division, and the scarcity is the point** (Jonah, 2026-07-29). A aspect
     * divides for two quite different reasons: because it could not reconcile a contradiction, which is this;
     * and because a vague word simply *liked* two answers, which is harmony, charged nothing, and produces no
     * flaw at all (see [co.voik.agesandtheart.age.aspect.Aspect.appetiteForCompany]). Naming both of them
     * "division" made the word describe every divided world including the pretty ones. Reserved for the charged
     * case, "this Age is fragmented" *means* something — you wrote a contradiction the world had to break to
     * honour — which is also the reading §5's consequences want. The mechanism keeps its own neutral names:
     * `region` and `territory` stay what they are.
     */
    FRACTURE("fracture", base = 1),

    /**
     * A word lost outright: it asked for something the aspect could have been, and something else already
     * had the aspect.
     *
     * Where a positional aspect would have divided, a singular one cannot — and a sky can only be one sky.
     * This is the register the harsher consequences of §5 hang off, because the world genuinely does not
     * contain what the writer asked for.
     */
    DISPLACED("displaced", base = 2),

    /**
     * Nothing in the aspect could *ever* be what the word asked for.
     *
     * Per §3.3 this is a **content bug rather than a play outcome** — we only make words we can back up,
     * and `VocabularyCheck` exists to keep it from shipping. It is a register anyway because of
     * the one hard requirement: if a word ever slips through, it must resolve as vacuous and be *reported*,
     * never silently dropped.
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
 * One thing wrong with a sentence, kept in full rather than reduced to a number.
 *
 * **Provenance is the point** (§4.6): an index alone can never be made diagnosable, and diagnosability is
 * the whole difference between this and arbitrary punishment. A wound has to be *sited at* the
 * contradiction (§5.1), which means knowing which words fought, over which tags, in which aspect.
 *
 * Stored structurally, with the English generated at reading time — a save file is no place for prose that
 * will want translating.
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
 * How far an Age is at odds with itself, and why — part of its recipe, because §5's consequences have to
 * read it long after the book was written.
 *
 * It persists for the same reason the resolved composition does (§4.6): re-deriving it would mean keeping
 * the words *and* re-running them through whatever tag data is current, so a retuned weight or an edited
 * datapack could quietly make someone's stable Age unstable. What was written is what it is.
 *
 * The index is the sum of the flaws rather than a field of its own, so the number and the reasons can
 * never disagree about what happened.
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
