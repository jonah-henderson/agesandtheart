package co.voik.agesandtheart.age.aspect

import net.minecraft.resources.Identifier
import net.minecraft.util.StringRepresentable

/**
 * The parts an Age is assembled from — one preset each, chosen independently (design §3.1).
 *
 * **A new aspect is appended, never inserted.** [co.voik.agesandtheart.age.AgeCharacter.mapFor] and
 * [co.voik.agesandtheart.age.word.Resolver] both stride their seeds on `ordinal`, so inserting one would
 * silently move the territories and draws of every Age already written.
 */
enum class Aspect(val key: String) : StringRepresentable {
    /** The shape of the rock. */
    TERRAIN("terrain"),

    /** What fills the space the shape leaves — sea, lava, nothing. */
    SEA("sea"),

    /** What happens beneath the surface: caves cut back out, and where water stands in the rock. */
    CARVERS("carvers"),

    /** Which biomes it grows. */
    BIOMES("biomes"),

    /** What is overhead. */
    SKY("sky"),

    /** What may be built here. */
    STRUCTURES("structures"),

    /** The coordinates its biomes are looked up at — how hot it is, and how wet. */
    CLIMATE("climate"),
    ;

    /**
     * Whether this aspect's value is a registry object rather than a preset written in Kotlin (§3.1).
     *
     * Decides whether §8's derived vocabulary can reach the aspect at all.
     */
    val open: Boolean
        get() = when (this) {
            SEA -> true
            // Structures is an open aspect in the design but closed here: it needs to hold several named
            // sets at once rather than one value drawn from a registry. See [Structures].
            TERRAIN, CARVERS, BIOMES, SKY, STRUCTURES, CLIMATE -> false
        }

    /**
     * Every preset for this aspect written in Kotlin — the whole pool for a closed aspect, and none of it
     * for an open one, whose pool is data. **Ask the vocabulary rather than this** unless you mean the
     * authored ones specifically: an open aspect answers `emptyList()` and would resolve to nothing.
     *
     * A computed `when` rather than a stored list, because each of these enums names [Aspect] in its own
     * initialiser and an eager list would have the two classes waiting on each other.
     */
    val authored: List<AspectPreset>
        get() = when (this) {
            TERRAIN -> Terrain.entries
            SEA -> emptyList()
            CARVERS -> Carvers.entries
            BIOMES -> Biomes.entries
            SKY -> Sky.entries
            STRUCTURES -> Structures.entries
            CLIMATE -> Climate.entries
        }

    /**
     * The preset this aspect means by [key], or null where the key names nothing it can hold — the single
     * place a key becomes a preset. An open aspect accepts an id it has never heard of and complains
     * later, where the missing content bites.
     */
    fun presetFor(key: String): AspectPreset? = when (this) {
        SEA -> Sea.named(key)
        TERRAIN, CARVERS, BIOMES, SKY, STRUCTURES, CLIMATE -> authored.firstOrNull { it.key == key }
    }

    /**
     * Whether this aspect is answered per column rather than once for the whole world (design §3.4). A
     * positional aspect can satisfy a contradiction by coexistence; a singular one has nowhere to put a
     * second answer, which is where the harsher registers of instability earn their place.
     *
     * Two claims, not one: a sky *cannot* divide (a world has one sky over it), where structures *need
     * not* (vanilla places each set against the whole dimension, its biome predicates doing the rest).
     */
    val positional: Boolean
        get() = when (this) {
            // Climate divides for a reason the others do not: not two presets that could not be reconciled,
            // but two words bounding one axis to stretches that do not overlap — a fracture at the
            // parameter level, which still needs ground to put each half on.
            TERRAIN, SEA, CARVERS, CLIMATE -> true
            // Biomes never divide: one climate table spans the world however many terrains carve it up.
            SKY, STRUCTURES, BIOMES -> false
        }

    /**
     * How readily this aspect takes on a second preset the sentence merely happened to like as well — the
     * harmonious division, as opposed to the one settling a contradiction (design §3.4).
     *
     * Terrain is the most reluctant because nothing interpolates between a floating island and a plain;
     * they meet at a seam, and a world full of seams reads as broken rather than varied.
     */
    val appetiteForCompany: Double
        get() = when (this) {
            TERRAIN -> 0.12
            SEA -> 0.18
            CARVERS -> 0.25
            BIOMES -> 0.0
            SKY -> 0.0
            STRUCTURES -> 0.0
            // Nothing to be companionable with: climate has one preset, so a second seat only ever arrives
            // from a fracture, which is charged by definition.
            CLIMATE -> 0.0
        }

    override fun getSerializedName(): String = key
}

/**
 * One choice a preset offers — "sparse / scattered / crowded", or a stretch of a continuous axis. The
 * first option is the default, so a preset named with no options still resolves.
 *
 * **Numbers are allowed here and always were.** §3.2 forbids them being exposed to the *player*, and a
 * [Kind.RANGED] parameter never is: a writer says a word, the word carries the span. Named steps were the
 * first reading of that rule and they do not scale — every new word that wants to sit on a different band
 * needs a new step, and steps must then be named on every axis at once.
 *
 * One kind is open-valued: a [material] takes a registry id rather than one of a list.
 */
data class Parameter(
    val name: String,
    val options: List<String>,
    val open: Boolean = false,
    /** What a value claims, which decides what two of them unjoined mean — see [Kind]. */
    val kind: Kind = Kind.PREDICATIVE,
) {
    enum class Kind {
        /** Says something about the whole — "the rock *is* blackstone". Two of them conflict and contend. */
        PREDICATIVE,

        /** Asserts a part is present — "there *are* cherry groves". Two of them accumulate. */
        POPULATIVE,

        /**
         * Bounds a continuous axis with a [Span]. Two of these **broaden** into the span holding both,
         * since a word carrying a span is evocative about that axis and evocative words tilt rather than
         * narrow.
         *
         * They **fracture** instead when the antonym table says the words disagree — without which two
         * spans miles apart would silently broaden into mush. `VocabularyCheck` enforces that disjoint
         * spans have an antonym between them.
         */
        RANGED,
    }

    val default: String get() = options.first()

    constructor(name: String, vararg options: String) : this(name, options.toList())

    /**
     * Whether this parameter would understand [option]. An open one takes any well-formed id, including
     * one naming content this pack does not have (§3.1).
     */
    fun accepts(option: String): Boolean {
        val isOneOfTheNamedOptions = option in options
        val looksLikeARegistryId = namesReferent(option) && Identifier.tryParse(option) != null
        val looksLikeASpan = kind == Kind.RANGED && Span.describes(option)
        return isOneOfTheNamedOptions || looksLikeASpan || (open && looksLikeARegistryId)
    }

    companion object {
        /**
         * What a material parameter reads as when nobody named one: the preset's own substance. A named
         * default rather than an absent value, so every consumer asks the same question.
         */
        const val UNCHANGED = "unchanged"

        /** A block a preset is made of — the palette's stone, a terrain's spires, a structure's walls. */
        fun material(name: String) = Parameter(name, listOf(UNCHANGED), open = true)

        /**
         * A set of registry entries present here — the biomes an Age draws from. Populative, so naming
         * one adds it and naming two adds both: inclusive by default, with `only` and `except` as the
         * modifiers that narrow it.
         */
        fun population(name: String) = Parameter(name, listOf(UNCHANGED), open = true, kind = Kind.POPULATIVE)

        /**
         * A continuous axis a word may bound — climate's temperature and humidity. Defaults to the whole
         * axis, so an Age told nothing keeps whatever vanilla's noise produced.
         */
        fun ranged(name: String) = Parameter(name, listOf(Span.NATURAL.spelled()), kind = Kind.RANGED)
    }
}

/** A preset that fills an [aspect], possibly offering a few [parameters] to steer it. */
interface AspectPreset : StringRepresentable {
    val key: String
    val aspect: Aspect
    val parameters: List<Parameter> get() = emptyList()

    /**
     * Whether a sentence may ask for this preset, as opposed to only a pinned recipe naming it outright.
     *
     * Almost every preset is askable and `VocabularyCheck` insists on it, since one no word can reach is
     * content nobody can use. Declared here rather than inferred from a missing `preset_tags` entry,
     * because an omission and an intention look identical — the check separately insists that anything
     * answering `false` really is pinned somewhere.
     */
    val askableInASentence: Boolean get() = true

    /**
     * Whether this preset would actually *do* anything with [parameter], as opposed to recognising the
     * name. Every dressing declares `stone` and `biomes` so naming one is not reported as a typo, but
     * `overworld` cannot wear a material and `bare_rock` has no biome table to enrich.
     *
     * The resolver reads this to prefer a preset that can honour what the sentence asked for. Where
     * nothing in the aspect can, the word is charged rather than dropped (§3.3).
     */
    fun honours(parameter: Parameter): Boolean = parameters.any { it.name == parameter.name }

    /**
     * The same question asked by name, which is how the resolver has it. False for a name never declared,
     * so "does not offer it" and "offers it but ignores it" answer alike — telling those apart is
     * [Options.unknownTo]'s job.
     */
    fun honoursParameterNamed(name: String): Boolean {
        val declared = parameters.firstOrNull { it.name == name } ?: return false
        return honours(declared)
    }

    override fun getSerializedName(): String
}
