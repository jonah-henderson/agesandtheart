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
 * repaired book's moved pages count one between them (design §5.0).
 */
enum class Register(val key: String) : StringRepresentable {
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
     * A page that placed nothing where it was laid (`the-word-rework.md`): a meaning nothing took, a thing a group refused,
     * a structure word that bound nothing. Its meaning is drawn onto a word that takes it, and every one counts one.
     */
    DANGLES("dangles"),
    ;


    override fun getSerializedName(): String = key

    companion object {
        /** The registers every disagreement between words was once charged under, which saved flaws still name. */
        private val FOLDED_INTO_INCOHERENT = setOf("tension", "opposed", "fracture", "displaced")

        /** A page there was nowhere for in any sentence, or one the old reader moved, which dangle now. */
        private val FOLDED_INTO_DANGLES = setOf("impossible", MOVED)

        fun byKey(key: String): DataResult<Register> {
            val folded = INCOHERENT.takeIf { key in FOLDED_INTO_INCOHERENT } ?: DANGLES.takeIf { key in FOLDED_INTO_DANGLES }
            val register = entries.firstOrNull { it.key == key } ?: folded
            return register?.let { DataResult.success(it) } ?: DataResult.error { "no register is called '$key'" }
        }
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
            Register.UNBACKED -> "nothing in $where can be ${quoted.firstOrNull() ?: "that"} — " +
                "a gap in the vocabulary, not something you did"
            Register.INCOHERENT -> "$both cannot both be what they said in $where$over"
            Register.DANGLES -> danglingDescribed(quoted)
        }
    }

    /** A dangling page, and the word it was drawn onto where one took it. */
    private fun danglingDescribed(quoted: List<String>): String {
        val page = quoted.firstOrNull() ?: "a page"
        val drawnOnto = quoted.getOrNull(1) ?: return "nothing could read $page where you laid it, so it was spent for nothing"
        return "nothing could read $page where you laid it, so the Art said it of $drawnOnto"
    }

    override fun toString(): String = "${register.key}: ${describe()}"

    companion object {
        val CODEC: Codec<Flaw> = SavedFlaw.CODEC.comapFlatMap(SavedFlaw::read) { SavedFlaw(it.register.key, it.words, it.aspect, it.tags) }
    }
}

private val ASPECT_CODEC: Codec<Aspect> = StringRepresentable.fromEnum(Aspect::values)

/** The register a saved Age names for a page the old reader moved. */
private const val MOVED = "rehomed"

/** What a saved Age's moved pages all name, so that every one in a book still counts one between them. */
private const val PAGES_THE_ART_ONCE_MOVED = "pages the Art once moved"

/** A flaw as it was saved, before its register is read. */
private data class SavedFlaw(val key: String, val words: List<String>, val aspect: Aspect?, val tags: List<String>) {
    fun read(): DataResult<Flaw> = Register.byKey(key).map { register ->
        val named = if (key == MOVED) listOf(PAGES_THE_ART_ONCE_MOVED) else words
        Flaw(register, named, aspect, tags)
    }

    companion object {
        val CODEC: Codec<SavedFlaw> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.STRING.fieldOf("register").forGetter(SavedFlaw::key),
                Codec.STRING.listOf().fieldOf("words").forGetter(SavedFlaw::words),
                ASPECT_CODEC.optionalFieldOf("aspect").forGetter { Optional.ofNullable(it.aspect) },
                Codec.STRING.listOf().optionalFieldOf("tags", emptyList()).forGetter(SavedFlaw::tags),
            ).apply(instance) { key, words, aspect, tags -> SavedFlaw(key, words, aspect.orElse(null), tags) }
        }
    }
}

/**
 * How far an Age is at odds with itself, and why. Part of the recipe, and never re-derived (design §4.6).
 *
 * The index is counted off the flaws rather than kept beside them, so the number and the reasons cannot
 * disagree. [pages] is how long the book was, which caps how far its instability may reach.
 */
data class Instability(val flaws: List<Flaw>, val pages: Int = 0) {
    /**
     * **How many distinct things are wrong** (design §5.0). Flaws raised by the same
     * words are one, as a saved Age's tension and fracture between two words are, so a sentence can never be much
     * more wrong than it looks.
     */
    val index: Int get() = flaws.distinctBy { it.words.toSet() }.size

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
            val flaws = (1..index).map { Flaw(Register.DANGLES, listOf("$BY_HAND $it"), null, emptyList()) }
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
