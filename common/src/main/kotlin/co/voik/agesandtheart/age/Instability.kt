package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.word.Tier
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.StringRepresentable
import java.util.Optional

/**
 * The ways a sentence can be at odds with the world, in ascending order of how much trouble it is.
 *
 * Registers rather than one severity dial: "nothing carries this word" and "an earlier word crowded it
 * out" look identical from outside and must not be charged as the same complaint.
 */
enum class Register(
    val key: String,
    /**
     * What a flaw of this kind earns towards the budget before precision is taken into account.
     *
     * **The default, which `art/instability/<key>.json` overrides** (design §5.0). These are the
     * accumulation half of the budget where `art/manifestation/` is the spending half, and both are
     * datapack because retuning them makes a different mod rather than the same mod run differently
     * (`notes/config-research.md`). Shipped values are here so a pack that says nothing still works.
     */
    val base: Int,
) : StringRepresentable {
    /** Two words meant opposite things and the world honoured both anyway, in one aspect. */
    TENSION("tension", base = 1),

    /**
     * Two words meant opposite things and **never met** — they landed in different aspects, so nothing in
     * the world was ever asked to choose between them.
     *
     * The flat charge under the dynamic ones (Jonah, 2026-08-05), and it exists because the others are
     * *mechanical*: [TENSION], [FRACTURE] and [DISPLACED] are all charged for a collision, so two words that
     * contradict each other go free whenever it happens that no single preset, parameter or population had
     * to hold both. `drenched` against `arid` was exactly that — one steers the rain and the other the
     * humidity, so a sentence saying both cost nothing at all. As aspects and dials multiply, so does the
     * chance of that coincidence, and a writer contradicting themselves should not be rescued by where the
     * two happened to land.
     *
     * Charged **once per pair**, and only where nothing else already charged the same two words.
     */
    OPPOSED("opposed", base = 1),

    /**
     * A page could not be read where it was laid, so the Art moved it somewhere it could (§4.3.1).
     *
     * Cheap, because the word still did what it means — `starless` under the land ends up meaning a
     * starless sky. What it is charged for is the writing rather than the outcome: the writer aimed a page
     * at a part of the world that had no use for it, and aiming is a precision lever they chose to pull.
     */
    REHOMED("rehomed", base = 1),

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

    /**
     * A page there was nowhere for **in any sentence at all** — a second `Age`, or an `and` with nothing
     * on one side of it (§4.3.1).
     *
     * The dearest, and the only register that costs a writer a page outright. Everything else here is a
     * world at odds with itself, where this is a book that was not one: repair fits a page in wherever it
     * can, so reaching this means there was no such place anywhere in a complete sentence.
     */
    IMPOSSIBLE("impossible", base = 6),
    ;

    /**
     * What a flaw of this kind costs when the word that caused it was written at [tier] — flat where the
     * page carried no word, since a structural page has no precision to scale by.
     *
     * [earns] is what a pack says this register is worth, defaulting to [base]. Read from the corpus at
     * resolution rather than at generation, because the charge is frozen onto the `Flaw` — retuning it
     * moves what the *next* Age costs and can never rewrite one already written.
     */
    fun charge(tier: Tier?, earns: Int = base): Int = earns * (tier?.weight ?: 1)

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
        val where = aspect?.page ?: "the sentence"
        return when (register) {
            Register.TENSION -> "$both pull opposite ways in $where$over, and the world made room for both"
            Register.OPPOSED -> "$both contradict each other$over, and landed far enough apart that " +
                "nothing in the world had to choose"
            Register.FRACTURE -> "the $where fractured so that $both could both stand$over"
            Register.DISPLACED -> "${quoted.firstOrNull() ?: "a word"} was crowded out of $where" +
                quoted.drop(1).firstOrNull().orEmpty().let { if (it.isEmpty()) "" else " by $it" } + over
            Register.REHOMED -> "${quoted.firstOrNull() ?: "a word"} could not be read where you laid it, " +
                "so the Art laid it under $where instead"
            Register.UNBACKED -> "nothing in $where can be ${quoted.firstOrNull() ?: "that"} — " +
                "a gap in the vocabulary, not something you did"
            Register.IMPOSSIBLE -> "there is nowhere in a sentence for ${quoted.firstOrNull() ?: "that page"}, " +
                "so it was spent for nothing"
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

        /**
         * An index set by hand rather than earned — **for `/age decay <name> unstable <n>` and nothing
         * else**.
         *
         * The index is the sum of the flaws precisely so the number and the reasons cannot disagree, and
         * this is the one place that is deliberately sidestepped: it fabricates a single flaw carrying the
         * whole weight, so everything downstream — the price list, the manifestations, the reports — reads
         * a real [Instability] and none of it needs to know.
         *
         * It exists because reaching the dire registers honestly wants a book of two dozen pages opposing
         * two dozen different things, which is a great deal of fighting the vocabulary to test arithmetic
         * that has nothing to do with vocabulary. **Nothing in the game may build one of these.**
         */
        fun forced(index: Int): Instability = if (index <= 0) {
            NONE
        } else {
            Instability(listOf(Flaw(Register.IMPOSSIBLE, listOf(BY_HAND), null, emptyList(), index)))
        }

        /** What a forced flaw names as its cause, so a report never claims a sentence did it. */
        const val BY_HAND = "an index set by hand"

        val MAP_CODEC: MapCodec<Instability> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Flaw.CODEC.listOf().optionalFieldOf("flaws", emptyList()).forGetter(Instability::flaws),
            ).apply(instance, ::Instability)
        }

        val CODEC: Codec<Instability> = MAP_CODEC.codec()
    }
}
