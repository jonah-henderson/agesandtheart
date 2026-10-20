package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.StringRepresentable
import java.util.Optional

/**
 * The ways a sentence can be at odds with the world.
 *
 * Registers so a flaw can be said to a writer for what it is: "nothing carries this word" and "an earlier
 * word crowded it out" look identical from outside. They name a flaw and never price it — every flaw but a
 * repaired book's moved pages count one between them (`notes/instability-tiers-plan.md`).
 */
enum class Register(val key: String) : StringRepresentable {
    /**
     * A page could not be read where it was laid, so the Art moved it somewhere it could (§4.3.1).
     *
     * **Every one in a book counts one between them** (Jonah, 2026-10-14): small, but enough that the
     * linking panel shows a book that did not read as written was not written right.
     */
    REHOMED("rehomed"),

    /**
     * Nothing in the aspect could ever be what the word asked for.
     *
     * A content bug rather than a play outcome (design §3.3) — `VocabularyCheck` keeps it from shipping.
     * A register anyway, because a word that slips through must be reported, never silently dropped.
     */
    UNBACKED("unbacked"),

    /**
     * Two words that could not both be what they said where they landed together — what every disagreement between
     * words is charged as (`the-world-model.md` §8), read off what each could do there
     * before anything was drawn.
     */
    INCOHERENT("incoherent"),

    /**
     * A page there was nowhere for **in any sentence at all** — a second `Age`, or an `and` with nothing
     * on one side of it (§4.3.1). The only register that costs a writer a page outright: everything else
     * here is a world at odds with itself, where this is a book that was not one.
     */
    IMPOSSIBLE("impossible"),
    ;


    override fun getSerializedName(): String = key

    companion object {
        /** The registers every disagreement between words was once charged under, which saved flaws still name. */
        private val FOLDED_INTO_INCOHERENT = setOf("tension", "opposed", "fracture", "displaced")

        private fun byKey(key: String): DataResult<Register> {
            val register = entries.firstOrNull { it.key == key } ?: INCOHERENT.takeIf { key in FOLDED_INTO_INCOHERENT }
            return register?.let { DataResult.success(it) } ?: DataResult.error { "no register is called '$key'" }
        }

        val CODEC: Codec<Register> = Codec.STRING.comapFlatMap(::byKey, Register::key)
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
) {
    /** What makes two flaws the same one: the kind, the words whichever way round, and the place. */
    val identity: Triple<Register, List<String>, Aspect?> get() = Triple(register, words.sorted(), aspect)

    /** How to say it to a writer. */
    fun describe(): String {
        val quoted = words.map { "'$it'" }
        val both = quoted.joinToString(" and ")
        val over = if (tags.size == 2) " (${tags[0]} against ${tags[1]})" else ""
        val where = aspect?.page ?: "the sentence"
        return when (register) {
            Register.REHOMED -> "${quoted.firstOrNull() ?: "a word"} could not be read where you laid it, " +
                "so the Art laid it under $where instead"
            Register.UNBACKED -> "nothing in $where can be ${quoted.firstOrNull() ?: "that"} — " +
                "a gap in the vocabulary, not something you did"
            Register.INCOHERENT -> "$both cannot both be what they said in $where$over"
            Register.IMPOSSIBLE -> "there is nowhere in a sentence for ${quoted.firstOrNull() ?: "that page"}, " +
                "so it was spent for nothing"
        }
    }

    override fun toString(): String = "${register.key}: ${describe()}"

    companion object {
        val CODEC: Codec<Flaw> = RecordCodecBuilder.create { instance ->
            instance.group(
                Register.CODEC.fieldOf("register").forGetter(Flaw::register),
                Codec.STRING.listOf().fieldOf("words").forGetter(Flaw::words),
                ASPECT_CODEC.optionalFieldOf("aspect").forGetter { Optional.ofNullable(it.aspect) },
                Codec.STRING.listOf().optionalFieldOf("tags", emptyList()).forGetter(Flaw::tags),
            ).apply(instance) { register, words, aspect, tags -> Flaw(register, words, aspect.orElse(null), tags) }
        }
    }
}

private val ASPECT_CODEC: Codec<Aspect> = StringRepresentable.fromEnum(Aspect::values)

/**
 * How far an Age is at odds with itself, and why. Part of the recipe, and never re-derived (design §4.6).
 *
 * The index is counted off the flaws rather than kept beside them, so the number and the reasons cannot
 * disagree. [pages] is how long the book was, which caps how far its instability may reach.
 */
data class Instability(val flaws: List<Flaw>, val pages: Int = 0) {
    /**
     * **How many distinct things are wrong** (`notes/instability-tiers-plan.md` §2). Flaws raised by the same
     * words are one, as a saved Age's tension and fracture between two words are, so a sentence can never be much
     * more wrong than it looks; and every page a repair moved is one between them.
     */
    val index: Int get() {
        val (moved, rest) = flaws.partition { it.register == Register.REHOMED }
        val repaired = if (moved.isEmpty()) 0 else 1
        return rest.distinctBy { it.words.toSet() }.size + repaired
    }

    val isCoherent: Boolean get() = flaws.isEmpty()

    override fun toString(): String = if (isCoherent) "coherent" else "instability $index"

    companion object {
        /** A sentence the world had no argument with, and every Age not written from one. */
        val NONE = Instability(emptyList())

        /**
         * An index set by hand rather than earned — **for `/age instability <name> index <n>` and nothing
         * else**.
         *
         * It fabricates [index] distinct flaws in a book long enough for any tier, so everything downstream —
         * the spending, the manifestations, the reports — reads a real [Instability] and none of it needs to
         * know. Reaching the verdicts honestly wants a long book opposing many different things, which is a
         * great deal of fighting the vocabulary to test what has nothing to do with vocabulary. **Nothing in
         * the game may build one of these.**
         */
        fun forced(index: Int): Instability = if (index <= 0) {
            NONE
        } else {
            val flaws = (1..index).map { Flaw(Register.IMPOSSIBLE, listOf("$BY_HAND $it"), null, emptyList()) }
            Instability(flaws, pages = LONG_ENOUGH_FOR_ANY_TIER)
        }

        private const val LONG_ENOUGH_FOR_ANY_TIER = 100

        /** What a forced flaw names as its cause, so a report never claims a sentence did it. */
        const val BY_HAND = "an index set by hand"

        val MAP_CODEC: MapCodec<Instability> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Flaw.CODEC.listOf().optionalFieldOf("flaws", emptyList()).forGetter(Instability::flaws),
                Codec.INT.optionalFieldOf("pages", 0).forGetter(Instability::pages),
            ).apply(instance, ::Instability)
        }

        val CODEC: Codec<Instability> = MAP_CODEC.codec()
    }
}
