package co.voik.agesandtheart.age.word.grammar

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.word.Speech
import co.voik.agesandtheart.age.word.Word
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.registries.BuiltInRegistries
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
 * **A parse, said as an account of an Age** — several sentences, each about one part of the world, the way
 * a chronicle describes a place rather than the way a writer laid its pages. This is for where a book is
 * *read*; [Readout] is for where the parse itself is wanted — `/age write`, the desk, the workshop.
 *
 * The two rules [Readout] keeps, this keeps too:
 *
 * - **It never launders.** A page the Art moved is said in the sentence about where it went, and the Art's
 *   own pages are never said at all.
 * - **It says what you said, never what it will make** (§7.5). `teeming zombies` is said as teeming, not as
 *   however many arrived.
 *
 * Built here, on the server, into [ProseClause]s; written as sentences by [ProseWriting] wherever the book
 * is read.
 */
object Prose {
    /** [sentence] as the clauses of its prose, the Age first. Empty where nothing was said. */
    fun of(sentence: Sentence): List<ProseClause> {
        val clauses = sentence.phrases.flatMap(::clausesOf)
        // The Age is said whenever its page was laid, even with nothing in front of it: "An Age." is what a
        // book that opens on a bare `age` says first, and every sentence after is about it.
        val theAge = clauses.filter { it.about == null }
        val rest = clauses.filter { it.about != null }
        val opening = theAge.ifEmpty {
            if (Production.NUCLEUS in sentence.structural) listOf(ProseClause(about = null, terms = emptyList()))
            else emptyList()
        }
        return opening + rest
    }

    /**
     * One phrase of the parse, as one clause per part of the world it speaks of.
     *
     * The aiming page is never said as a word — it is what the sentence is *about*, "its land" — and nor is
     * anything the Art laid, a book showing only what its writer wrote (§4.3.1). A subject the Art supplied
     * still decides what the sentence is about wherever the writer's words hang off it, because that is the
     * whole of what says where a re-homed page landed.
     */
    private fun clausesOf(phrase: Phrase): List<ProseClause> {
        mintedIn(phrase)?.let { return listOf(it) }
        val aimingPage = phrase.subject?.takeIf { it.word.aims }
        val written = phrase.said.filterNot { it.latent || it === aimingPage }
        if (written.isEmpty()) return emptyList()
        val confinedTo = phrase.confinedTo?.let(::biomeTerm)
        val body = phrase.said.firstNotNullOfOrNull { it.describes }
        val isTheAge = phrase.subject == null && phrase.confinedTo == null && !phrase.everywhere
        if (isTheAge) return listOf(ProseClause(about = null, terms = written.map(::termOf)))

        val aimedAt = aimingPage?.word?.aspects?.minByOrNull { it.ordinal }
        val byAspect = if (aimedAt != null) mapOf(aimedAt to written) else written.groupBy(::aspectOf)
        return byAspect.map { (about, said) ->
            ProseClause(about, said.map(::termOf), confinedTo, phrase.everywhere, body)
        }
    }

    /**
     * A clause closing on a word that **mints** a shape, said as that one shape — or null where it mints
     * nothing. Such a word looks like an aiming page, but it is the thing itself: `basalt arches` says
     * "arches of basalt", never "what is in it is made of basalt" with the arches gone.
     */
    private fun mintedIn(phrase: Phrase): ProseClause? {
        val subject = phrase.subject ?: return null
        if (subject.word.mints == null || subject.latent) return null
        val written = phrase.modifiers.filterNot { it.latent }
        val (substances, rest) = written.partition { it.word.material != null }
        // Every rung in the clause counts the shape, as the resolver reads it.
        val rung = subject.quantifier ?: written.firstNotNullOfOrNull { it.quantifier }
        val counted = termOf(subject).copy(quantifier = rung.takeUnless { rung == null || isOrdinaryIn(phrase) })
        val shape = ProseShape(
            shape = counted,
            madeOf = substances.map { termOf(it).copy(quantifier = null) },
            qualities = rest.map { termOf(it).copy(quantifier = null) },
        )
        return ProseClause(
            about = Aspect.FEATURES,
            terms = emptyList(),
            confinedTo = phrase.confinedTo?.let(::biomeTerm),
            everywhere = phrase.everywhere,
            shape = shape,
        )
    }

    /** Whether every rung laid in [phrase] asks for the ordinary amount, which is the same as none. */
    private fun isOrdinaryIn(phrase: Phrase): Boolean = phrase.said.all { Rung.isOrdinary(it.density) }

    /** What a word in a clause with no aiming page is about: where it landed, else where it can. */
    private fun aspectOf(constraint: Constraint): Aspect? =
        constraint.aimedAt.minByOrNull { it.ordinal } ?: constraint.word.aspects.minByOrNull { it.ordinal }

    private fun termOf(constraint: Constraint): ProseTerm = ProseTerm(
        word = constraint.word.id,
        speech = constraint.word.partOfSpeech,
        polarity = constraint.polarity,
        quantifier = constraint.quantifier?.takeUnless { Rung.isOrdinary(constraint.density) },
        nameKey = nameKeyOf(constraint.word),
        plain = plainNameOf(constraint.word),
    )

    private fun biomeTerm(biome: Identifier): ProseTerm = ProseTerm(
        word = biome,
        speech = Speech.NOUN,
        nameKey = "biome.${biome.namespace}.${biome.path}",
        plain = biome.path.replace('_', ' '),
    )

    /**
     * Vanilla's name for what a derived word is, where vanilla gives it one. A structure set and a placed
     * feature have none, which is why [plainNameOf] has to work for them.
     */
    private fun nameKeyOf(word: Word): String? = when {
        word.entryOf != null -> BuiltInRegistries.BLOCK.getOptional(word.id).map { it.descriptionId }.orElse(null)
        Aspect.SPAWNS in word.chooses ->
            BuiltInRegistries.ENTITY_TYPE.getOptional(word.id).map { it.descriptionId }.orElse(null)
        Aspect.BIOMES in word.chooses -> "biome.${word.id.namespace}.${word.id.path}"
        else -> null
    }

    private fun plainNameOf(word: Word): String =
        if (Aspect.FEATURES in word.chooses) FeatureNames.legible(word.id.path) else word.id.path.replace('_', ' ')
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
