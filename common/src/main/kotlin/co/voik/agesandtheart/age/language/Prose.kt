package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.Members
import co.voik.agesandtheart.age.resolution.Place
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import java.util.Optional

/**
 * One word of a book's prose: what it is, how it is spoken, and what to call it.
 *
 * **Everything a reader needs and nothing they cannot have.** Reading a sentence takes the whole corpus,
 * which is a server's, so a book carries what its prose is made of rather than the prose itself — and the
 * prose is written where the book is read, in the reader's own language, where a name can be translated and
 * *then* made plural, which a translation key alone can never be.
 */
data class ProseTerm(
    val word: Identifier,
    val speech: Speech,
    val polarity: Polarity = Polarity.ASSERTED,
    /** The rung page the writer laid in front of it, where it asks for more or less than ordinary. */
    val quantifier: String? = null,
    /** Vanilla's own name for the thing, where vanilla has one — a creature's, a biome's, a block's. */
    val nameKey: String? = null,
    /** What it is called where no language file says: its id, made legible. */
    val plain: String,
) {
    companion object {
        val CODEC: Codec<ProseTerm> = RecordCodecBuilder.create { instance ->
            instance.group(
                Identifier.CODEC.fieldOf("word").forGetter(ProseTerm::word),
                Speech.CODEC.fieldOf("speech").forGetter(ProseTerm::speech),
                POLARITY_CODEC.optionalFieldOf("polarity", Polarity.ASSERTED).forGetter(ProseTerm::polarity),
                Codec.STRING.optionalFieldOf("quantifier").forGetter { Optional.ofNullable(it.quantifier) },
                Codec.STRING.optionalFieldOf("name_key").forGetter { Optional.ofNullable(it.nameKey) },
                Codec.STRING.fieldOf("plain").forGetter(ProseTerm::plain),
            ).apply(instance) { word, speech, polarity, quantifier, nameKey, plain ->
                ProseTerm(word, speech, polarity, quantifier.orElse(null), nameKey.orElse(null), plain)
            }
        }
    }
}

/**
 * A shape a clause minted, said as one thing — `teeming basalt arches` is "teeming arches of basalt",
 * since every rung and every material in a minting clause belongs to what it mints.
 */
data class ProseShape(
    val shape: ProseTerm,
    /** What the shape is made of, where the clause said. */
    val madeOf: List<ProseTerm> = emptyList(),
    /** Its size and the like — `colossal`. */
    val qualities: List<ProseTerm> = emptyList(),
) {
    companion object {
        val CODEC: Codec<ProseShape> = RecordCodecBuilder.create { instance ->
            instance.group(
                ProseTerm.CODEC.fieldOf("shape").forGetter(ProseShape::shape),
                ProseTerm.CODEC.listOf().optionalFieldOf("made_of", emptyList()).forGetter(ProseShape::madeOf),
                ProseTerm.CODEC.listOf().optionalFieldOf("qualities", emptyList()).forGetter(ProseShape::qualities),
            ).apply(instance, ::ProseShape)
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
data class ProseClause(
    /** The part of the world this is about, or null for the Age itself — the words laid before `age`. */
    val about: Aspect?,
    val terms: List<ProseTerm>,
    /** The biome the clause was confined to with `in`. */
    val confinedTo: ProseTerm? = null,
    val everywhere: Boolean = false,
    /** Which member of a population this describes — the second sun rather than the first. */
    val body: Int? = null,
    /** What the clause minted, where it minted something; its words are then here and not in [terms]. */
    val shape: ProseShape? = null,
) {
    companion object {
        val CODEC: Codec<ProseClause> = RecordCodecBuilder.create { instance ->
            instance.group(
                ASPECT_CODEC.optionalFieldOf("about").forGetter { Optional.ofNullable(it.about) },
                ProseTerm.CODEC.listOf().fieldOf("terms").forGetter(ProseClause::terms),
                ProseTerm.CODEC.optionalFieldOf("confined_to").forGetter { Optional.ofNullable(it.confinedTo) },
                Codec.BOOL.optionalFieldOf("everywhere", false).forGetter(ProseClause::everywhere),
                Codec.INT.optionalFieldOf("body").forGetter { Optional.ofNullable(it.body) },
                ProseShape.CODEC.optionalFieldOf("shape").forGetter { Optional.ofNullable(it.shape) },
            ).apply(instance) { about, terms, confinedTo, everywhere, body, shape ->
                ProseClause(
                    about.orElse(null), terms, confinedTo.orElse(null), everywhere, body.orElse(null), shape.orElse(null),
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
 * **A reading, said as an account of an Age** — several sentences, each about one part of the world, the way a
 * chronicle describes a place rather than the way a writer laid its pages. For where a book is *read*;
 * [Reading.readout] is for where the reading itself is wanted.
 *
 * - **Every word that took pages is a sentence** about where it landed them, the pages it took said with it.
 * - **What the Age took is the Age's sentence**, the pages a writer laid bare and those laid there with nowhere else
 *   to go; it is said whenever the `age` page was laid, even with nothing before it.
 * - **A minting is said as the shape it mints**, and a landform of ours with only qualities before it as that
 *   landform: `basalt arches` is arches of basalt, `large tunnels` the tunnels, never two sentences.
 * - **A quantifier is said as how much there is of what took it**, never as a word of its own; and it says what was
 *   written, never what it will make.
 *
 * Built on the server into [ProseClause]s; written as sentences by [ProseWriting] wherever the book is read.
 */
object Prose {
    /** [reading] as the clauses of its prose, the Age first. Empty where nothing was said. */
    fun of(reading: Reading, lexicon: Lexicon): List<ProseClause> {
        val said = Said(reading, lexicon)
        val theAge = said.ofTheAge()
        val rest = reading.pages.sortedBy { it.index }.flatMap { page ->
            when (val fate = page.fate) {
                is Fate.Took -> listOfNotNull(said.clauseTakenBy(page, fate.place))
                Fate.AssertedAlone -> listOfNotNull(said.alone(page))
                else -> emptyList()
            }
        }
        return listOfNotNull(theAge) + rest
    }

    /** A reading with its words at hand. */
    private class Said(val reading: Reading, val lexicon: Lexicon) {
        private fun wordOf(page: PageRead): Word? = lexicon.word(page.written)

        private fun isAQuantifier(page: PageRead): Boolean = wordOf(page)?.isAQuantifier == true

        fun ofTheAge(): ProseClause? {
            val bare = reading.pages.filter { it.fate == Fate.LaidBare || it.fate == Fate.Moved }
            val ageWasLaid = reading.pages.any { it.fate == Fate.Structural(Structure.AGE) }
            if (bare.isEmpty()) return if (ageWasLaid) ProseClause(about = null, terms = emptyList()) else null
            return ProseClause(about = null, terms = bare.mapNotNull(::termOf))
        }

        fun clauseTakenBy(taker: PageRead, place: Place): ProseClause? {
            val word = wordOf(taker) ?: return null
            val taken = reading.pages.filter { it.fate == Fate.TakenBy(taker.index) }
            val (amounts, described) = taken.partition(::isAQuantifier)
            val landed = reading.landings.filter { it.clause == taker.index }
            val confinedTo = landed.firstNotNullOfOrNull { it.sitedIn }?.let(::biomeTerm)
            val everywhere = landed.any { it.everywhere }
            val body = (place.members as? Members.Body)?.index
            val ownPolarity = reading.landings.firstOrNull { it.page == taker.index && it.polarity != Polarity.ASSERTED }?.polarity
            val counted = termOf(taker)?.copy(quantifier = amounts.firstOrNull()?.written, polarity = ownPolarity ?: Polarity.ASSERTED)
            val isTheAspectsPage = word.id.namespace == Constants.MOD_ID && Aspect.byPage(word.name) == place.aspect
            if (isTheAspectsPage) {
                return ProseClause(place.aspect, taken.mapNotNull(::termOf), confinedTo, everywhere, body)
            }
            val shape = shapeOf(word, counted, described)
            if (shape != null) return ProseClause(place.aspect, emptyList(), confinedTo, everywhere, body, shape)
            return ProseClause(place.aspect, described.mapNotNull(::termOf) + listOfNotNull(counted), confinedTo, everywhere, body)
        }

        /**
         * A minting said as its shape, made of the substances laid before it; or a landform of ours with nothing
         * but qualities before it, said as that landform. Null for anything else.
         */
        private fun shapeOf(word: Word, counted: ProseTerm?, described: List<PageRead>): ProseShape? {
            val shape = counted ?: return null
            val (substances, qualities) = described.partition { wordOf(it)?.block != null }
            if (word.mints != null) {
                return ProseShape(shape, substances.mapNotNull(::termOf), qualities.mapNotNull(::termOf))
            }
            val namesADesign = word.meanings.flatMap { it.effects }.any { effect ->
                effect is Effect.Chooses && !effect.aspect.open && word.partOfSpeech.names
            }
            val onlyQualities = described.all { wordOf(it)?.partOfSpeech == Speech.ADJECTIVE }
            return if (namesADesign && onlyQualities) ProseShape(shape, qualities = described.mapNotNull(::termOf)) else null
        }

        fun alone(page: PageRead): ProseClause? {
            val about = reading.landings.firstOrNull { it.page == page.index }?.place?.aspect ?: return null
            return ProseClause(about, listOfNotNull(termOf(page)))
        }

        private fun termOf(page: PageRead): ProseTerm? {
            val word = wordOf(page) ?: return null
            return ProseTerm(word.id, word.partOfSpeech, nameKey = nameKeyOf(word), plain = plainNameOf(word))
        }
    }

    private fun biomeTerm(biome: Identifier): ProseTerm = ProseTerm(
        word = biome,
        speech = Speech.NOUN,
        nameKey = "biome.${biome.namespace}.${biome.path}",
        plain = biome.path.replace('_', ' '),
    )

    /** Vanilla's name for what a derived word is, where vanilla gives it one. */
    private fun nameKeyOf(word: Word): String? {
        val registries = word.referentRegistries
        return when {
            Registries.BLOCK in registries -> BuiltInRegistries.BLOCK.getOptional(word.id).map { it.descriptionId }.orElse(null)
            Registries.ENTITY_TYPE in registries ->
                BuiltInRegistries.ENTITY_TYPE.getOptional(word.id).map { it.descriptionId }.orElse(null)
            Registries.BIOME in registries -> "biome.${word.id.namespace}.${word.id.path}"
            else -> null
        }
    }

    private fun plainNameOf(word: Word): String =
        if (Registries.PLACED_FEATURE in word.referentRegistries) FeatureNames.legible(word.id.path) else word.id.path.replace('_', ' ')
}

/**
 * A placed feature's id, made into something a sentence can hold.
 *
 * Vanilla names its placed features for the code that places them — `ore_diamond_buried`, `trees_plains`,
 * `seagrass_deep_warm` — and the variants a biome needs are the same thing to anyone reading about it. So
 * the variant is struck and the kind put after what it is of: "diamond deposits", "plains trees". What this
 * gets wrong is overridden by name in the language file, under `prose.<namespace>.<path>`.
 */
internal object FeatureNames {
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
