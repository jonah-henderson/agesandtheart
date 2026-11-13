package co.voik.agesandtheart.age.readout

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.words.Speech
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import java.util.Optional
import net.minecraft.resources.Identifier

/**
 * One word of a book's prose: what it is, how it is spoken, and what to call it.
 *
 * **Everything a reader needs and nothing they cannot have.** Reading a sentence takes the whole corpus,
 * which is a server's, so a book carries what its prose is made of rather than the prose itself — and the
 * prose is written where the book is read, in the reader's own language, where a name can be translated and
 * *then* made plural, which a translation key alone can never be.
 */
data class ReadoutTerm(
    val word: Identifier,
    val speech: Speech,
    val polarity: Polarity = Polarity.ASSERTED,
    /** The rung page the writer laid in front of it, where it asks for more or less than ordinary. */
    val quantifier: String? = null,
    /** Vanilla's own name for the thing, where vanilla has one — a creature's, a biome's, a block's. */
    val nameKey: String? = null,
    /** What it is called where no language file says: its id, made legible. */
    val plain: String,
    /** Which share of a part divided between rival words it went to, where one was divided. */
    val share: Int? = null,
) {
    companion object {
        val CODEC: Codec<ReadoutTerm> = RecordCodecBuilder.create { instance ->
            instance.group(
                Identifier.CODEC.fieldOf("word").forGetter(ReadoutTerm::word),
                Speech.CODEC.fieldOf("speech").forGetter(ReadoutTerm::speech),
                POLARITY_CODEC.optionalFieldOf("polarity", Polarity.ASSERTED).forGetter(ReadoutTerm::polarity),
                Codec.STRING.optionalFieldOf("quantifier").forGetter { Optional.ofNullable(it.quantifier) },
                Codec.STRING.optionalFieldOf("name_key").forGetter { Optional.ofNullable(it.nameKey) },
                Codec.STRING.fieldOf("plain").forGetter(ReadoutTerm::plain),
                Codec.INT.optionalFieldOf("share").forGetter { Optional.ofNullable(it.share) },
            ).apply(instance) { word, speech, polarity, quantifier, nameKey, plain, share ->
                ReadoutTerm(word, speech, polarity, quantifier.orElse(null), nameKey.orElse(null), plain, share.orElse(null))
            }
        }
    }
}

/**
 * A shape a clause minted, said as one thing — `teeming basalt arches` is "teeming arches of basalt",
 * since every rung and every material in a minting clause belongs to what it mints.
 */
data class ReadoutShape(
    val shape: ReadoutTerm,
    /** What the shape is made of, where the clause said. */
    val madeOf: List<ReadoutTerm> = emptyList(),
    /** Its size and the like — `colossal`. */
    val qualities: List<ReadoutTerm> = emptyList(),
) {
    companion object {
        val CODEC: Codec<ReadoutShape> = RecordCodecBuilder.create { instance ->
            instance.group(
                ReadoutTerm.CODEC.fieldOf("shape").forGetter(ReadoutShape::shape),
                ReadoutTerm.CODEC.listOf().optionalFieldOf("made_of", emptyList()).forGetter(ReadoutShape::madeOf),
                ReadoutTerm.CODEC.listOf().optionalFieldOf("qualities", emptyList()).forGetter(ReadoutShape::qualities),
            ).apply(instance, ::ReadoutShape)
        }
    }
}

/**
 * **One sentence's worth of a book**: what was said about one part of the world, and where.
 *
 * A clause of the grammar is about one part of the world when it closes on an aiming page, and about as many
 * as its words reach when it closes on a siting alone — so a clause of the grammar can be several of these,
 * and the book reads as several sentences where it said several things.
 */
data class ReadoutClause(
    /** The part of the world this is about, or null for the Age itself — the words laid before `age`. */
    val about: Aspect?,
    val terms: List<ReadoutTerm>,
    /** The biome the clause was confined to with `in`. */
    val confinedTo: ReadoutTerm? = null,
    val everywhere: Boolean = false,
    /** Which member of a population this describes — the second sun rather than the first. */
    val body: Int? = null,
    /** What the clause minted, where it minted something; its words are then here and not in [terms]. */
    val shape: ReadoutShape? = null,
    /** The members it is about, where the word that took it names some — `spiders` — rather than the whole part. */
    val subject: ReadoutTerm? = null,
    /** What `of` said the subject comes in — `herds` in `herds of warden` — with what was said of that. */
    val collective: ReadoutShape? = null,
) {
    companion object {
        val CODEC: Codec<ReadoutClause> = RecordCodecBuilder.create { instance ->
            instance.group(
                ASPECT_CODEC.optionalFieldOf("about").forGetter { Optional.ofNullable(it.about) },
                ReadoutTerm.CODEC.listOf().fieldOf("terms").forGetter(ReadoutClause::terms),
                ReadoutTerm.CODEC.optionalFieldOf("confined_to").forGetter { Optional.ofNullable(it.confinedTo) },
                Codec.BOOL.optionalFieldOf("everywhere", false).forGetter(ReadoutClause::everywhere),
                Codec.INT.optionalFieldOf("body").forGetter { Optional.ofNullable(it.body) },
                ReadoutShape.CODEC.optionalFieldOf("shape").forGetter { Optional.ofNullable(it.shape) },
                ReadoutTerm.CODEC.optionalFieldOf("subject").forGetter { Optional.ofNullable(it.subject) },
                ReadoutShape.CODEC.optionalFieldOf("collective").forGetter { Optional.ofNullable(it.collective) },
            ).apply(instance) { about, terms, confinedTo, everywhere, body, shape, subject, collective ->
                ReadoutClause(
                    about.orElse(null), terms, confinedTo.orElse(null), everywhere, body.orElse(null), shape.orElse(null),
                    subject.orElse(null), collective.orElse(null),
                )
            }
        }
    }
}

private val POLARITY_CODEC: Codec<Polarity> = Codec.STRING.comapFlatMap(
    { named ->
        Polarity.entries.firstOrNull { it.name.lowercase() == named }
            ?.let { DataResult.success(it) }
            ?: DataResult.error { "no polarity is called '$named'" }
    },
    { it.name.lowercase() },
)

private val ASPECT_CODEC: Codec<Aspect> = Codec.STRING.comapFlatMap(
    { named ->
        Aspect.entries.firstOrNull { it.key == named }
            ?.let { DataResult.success(it) }
            ?: DataResult.error { "no part of the world is called '$named'" }
    },
    Aspect::key,
)

/**
 * A placed feature's id, made into something a sentence can hold.
 *
 * Vanilla names its placed features for the code that places them — `ore_diamond_buried`, `trees_plains`,
 * `seagrass_deep_warm` — and the variants a biome needs are the same thing to anyone reading about it. So
 * the variant is struck and the kind put after what it is of: "diamond deposits", "plains trees". What this
 * gets wrong is overridden by name in the language file, under `prose.<namespace>.<path>`.
 */
object FeatureNames {
    /** What a variant's suffix is: where in the world, how deep, how big — never what the thing is. */
    private val VARIANT = Regex(
        "_(checked|lower|upper|middle|extra|buried|normal|small|medium|large|cold|warm|deep|nether|deltas|" +
            "on_snow|decorated|return|river|swamp|taiga|old_growth|birch_forest|meadow|\\d+)$",
    )

    /**
     * The kinds that lead a feature's id, and how each is said after what it is of. `ore` is said as
     * deposits because half of what vanilla calls an ore is a blob of dirt or granite.
     */
    private val KINDS = mapOf(
        "ore" to "deposits",
        "disk" to "disks",
        "patch" to "patches",
        "pile" to "piles",
        "lake" to "lakes",
        "spring" to "springs",
        "flower" to "flowers",
        "trees" to "trees",
    )

    fun legible(path: String): String {
        val kind = path.substringBefore('_')
        val rest = path.substringAfter('_', missingDelimiterValue = "")
        val said = KINDS[kind]
        if (said != null && rest.isNotEmpty()) return "${withoutVariants(rest).replace('_', ' ')} $said"
        return withoutVariants(path).replace('_', ' ')
    }

    /** [path] with every variant suffix struck, so long as something is left to be the thing itself. */
    private fun withoutVariants(path: String): String {
        var left = path
        while (true) {
            val struck = VARIANT.replace(left, "")
            if (struck == left || struck.isEmpty()) return left
            left = struck
        }
    }
}
