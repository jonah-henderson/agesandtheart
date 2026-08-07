package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.worldgen.biome.ClimateAxis
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

    /** What the ground wears over whatever it is made of. */
    SURFACE("surface"),

    /** What grows and forms in it: ores, flora, lakes, springs. */
    FEATURES("features"),

    /** What lives in it. */
    SPAWNS("spawns"),

    /** What the air does to you. */
    ATMOSPHERE("atmosphere"),

    /**
     * What *happens* here: storms, meteors, a rising sea (design §3.1, §5.2).
     *
     * **Scaffolding — it names nothing and does nothing yet**, deliberately. The shape is here so the
     * aspect is addressable and so every exhaustive `when` already answers for it; the processes themselves
     * are §5's, and the design says plainly this is the aspect most likely to change shape when they land.
     *
     * The one aspect a sentence fills *and* consequences arrive at: a meteor storm you wrote is a hazard
     * you prepared for, and one you did not write is the Age telling you something is wrong (§7.7).
     */
    PHENOMENA("phenomena"),
    ;

    /**
     * **What kind of answer this aspect has**, which is the thing that decides how it resolves.
     *
     * Not a label over a uniform mechanism: each kind wants a different question asked of a sentence, and
     * the resolver dispatches on this rather than treating every aspect as a preset with knobs.
     */
    val kind: Kind
        get() = when (this) {
            // Structures *is* a population and is still shaped as a preset pair, `none` against `vanilla`,
            // whose readiness prior is what makes habitation opt-in. It changes kind when it is converted,
            // not before — declaring it early would skip the draw and build in every Age.
            TERRAIN, CARVERS, SKY -> Kind.PRESET
            SEA -> Kind.REFERENT
            BIOMES, STRUCTURES, FEATURES, SPAWNS, PHENOMENA -> Kind.POPULATION
            CLIMATE, SURFACE, ATMOSPHERE -> Kind.DIALS
        }

    /**
     * Whether this aspect's value is a registry object rather than a preset written in Kotlin (§3.1) —
     * which decides whether §8's derived vocabulary can reach the aspect at all.
     *
     * **The one question [kind] answers on its own.** A referent's value *is* a registry object and a
     * population's members are, where a preset is a bundle we wrote and a dial is a number.
     */
    val open: Boolean get() = kind == Kind.REFERENT || kind == Kind.POPULATION

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
            CARVERS -> Carvers.entries
            SKY -> Sky.entries
            // Nothing to choose between: a climate and a surface are where their dials were left, and a
            // biome or a structure set is weighed rather than chosen. See [dials] and [Kind.POPULATION].
            PHENOMENA -> Phenomenon.entries
            SEA, BIOMES, STRUCTURES, CLIMATE, SURFACE, FEATURES, SPAWNS, ATMOSPHERE -> emptyList()
        }

    /**
     * Parameters belonging to the **aspect itself** rather than to a preset — empty for every aspect whose
     * answer is a preset, since there the preset owns its own knobs.
     *
     * This is what an aspect with no candidates has instead. `Climate` was an enum of one member existing
     * only to hold these, which made a parameter bag wear a preset's clothes and made the composition
     * count its territories by counting a preset it always had exactly one of.
     */
    val dials: List<Parameter>
        get() = when (this) {
            CLIMATE -> ClimateAxis.entries.map { it.parameter }
            // A biome's population is the aspect's answer; `footing` says how it is *worn*, not which.
            BIOMES -> listOf(Biomes.GROWN, Biomes.FOOTING)
            STRUCTURES -> listOf(Structures.BUILT)
            SURFACE -> listOf(Surface.MATERIAL)
            FEATURES -> listOf(Features.PLACES, Features.SIZE, Features.THICKNESS, Features.HEIGHT)
            SPAWNS -> listOf(Spawns.LIVES)
            PHENOMENA -> listOf(Phenomena.HAPPENS)
            ATMOSPHERE -> listOf(
                Atmosphere.DAYLIGHT,
                Atmosphere.SUNBURN,
                Atmosphere.EVAPORATION,
                Atmosphere.SKY,
                Atmosphere.FOG,
                Atmosphere.CLOUD,
                Atmosphere.TINT,
                Atmosphere.MOTES,
                Atmosphere.HAZE,
                Atmosphere.CEILING,
                Atmosphere.MURK,
                Atmosphere.RAINFALL,
                Atmosphere.THUNDER,
            )
            // A preset aspect with dials: the two switches that pick the Age's dimension type. They sit
            // here rather than on `Atmosphere` because they are chosen when the Age is *made* and baked
            // into a pre-authored file, where every atmosphere dial is laid over a level that is already
            // open — which is also why these two alone cannot be confined to a biome.
            SKY -> listOf(Sky.SKYLIGHT, Sky.ROOF)
            TERRAIN, SEA, CARVERS -> emptyList()
        }

    /**
     * What kind of answer an aspect has. The resolver asks a different question of a sentence for each,
     * which is the whole reason this exists rather than one shape with degenerate cases in it.
     */
    enum class Kind {
        /** A curated bundle too large to spell out, drawn between and given ground. */
        PRESET,

        /** A registry object named outright, otherwise as [PRESET]. */
        REFERENT,

        /** Weighted claims that accumulate. Never drawn between: everything is already there. */
        POPULATION,

        /** Spans on continuous axes, and no choice at all. */
        DIALS,
    }

    /** Whether nothing is ever drawn to fill this aspect: its answer is its members or its dials. */
    val seatsNothing: Boolean get() = kind == Kind.POPULATION || kind == Kind.DIALS

    /**
     * The preset this aspect means by [key], or null where the key names nothing it can hold — the single
     * place a key becomes a preset. An open aspect accepts an id it has never heard of and complains
     * later, where the missing content bites.
     */
    fun presetFor(key: String): AspectPreset? = when (this) {
        SEA -> Sea.named(key)
        BIOMES -> Biome.named(key)
        STRUCTURES -> StructureSet.named(key)
        FEATURES -> PlacedFeature.named(key)
        SPAWNS -> Spawn.named(key)
        TERRAIN, CARVERS, SKY, CLIMATE, SURFACE, ATMOSPHERE, PHENOMENA ->
            authored.firstOrNull { it.key == key }
    }

    /**
     * Whether this aspect is answered per column rather than once for the whole world (design §3.4). A
     * positional aspect can satisfy a contradiction by coexistence; a singular one has nowhere to put a
     * second answer, which is where the harsher registers of instability earn their place.
     *
     * Two claims, not one: a sky *cannot* divide (a world has one sky over it), where structures *need
     * not* (vanilla places each set against the whole dimension, its biome predicates doing the rest).
     */
    /**
     * Whether a clause about this may be **confined to one biome** — `in mushroom_fields, spawns only
     * slime`. Vanilla resolves these three through the biome, so they are the three that can be asked
     * about one.
     *
     * A phenomenon is deliberately not among them: it is *sited* rather than resolved per biome (§5.2), so
     * `in <biome>` would be the wrong scope for it entirely.
     */
    val confinable: Boolean
        get() = when (this) {
            FEATURES, SPAWNS, ATMOSPHERE -> true
            TERRAIN, SEA, CARVERS, BIOMES, SKY, STRUCTURES, CLIMATE, SURFACE, PHENOMENA -> false
        }

    /**
     * Whether this part of the world is **made of** something, and so admits a material where a term is
     * wanted — `land of blackstone`, `a sea of ice`.
     *
     * Being made of a substance is shared, which is why a block is its own page class rather than a term
     * per aspect: the *section* decides whether `ice` means a sea or a stone.
     */
    val madeOfSomething: Boolean
        get() = when (this) {
            TERRAIN, SEA, STRUCTURES, SURFACE -> true
            CARVERS, BIOMES, SKY, CLIMATE, FEATURES, SPAWNS, ATMOSPHERE, PHENOMENA -> false
        }

    val positional: Boolean
        get() = when (this) {
            // Climate divides for a reason the others do not: not two presets that could not be reconciled,
            // but two words bounding one axis to stretches that do not overlap — a fracture at the
            // parameter level, which still needs ground to put each half on.
            TERRAIN, SEA, CARVERS, CLIMATE -> true
            // Biomes never divide: one climate table spans the world however many terrains carve it up.
            // The surface *could* follow the terrain's division and does not yet — one skin, Age-wide.
            // Features are per biome in vanilla and per Age here, so nothing divides them yet — `in
            // <biome>` (§4.3.1) is the shape that would.
            // The air divides by *biome* rather than by territory, which is a scope the grammar has and
            // the composition does not — see `in <biome>` (§4.3.1).
            // Phenomena is **sited rather than divided** (§5.2): a process happens at a place and spreads
            // from it, the way §5.1's hostility is a gradient around a wound — which is not a territory
            // with a boundary, and so is not this flag however much it sounds like one.
            SKY, STRUCTURES, BIOMES, SURFACE, FEATURES, SPAWNS, ATMOSPHERE, PHENOMENA -> false
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
            SURFACE -> 0.0
            FEATURES -> 0.0
            SPAWNS -> 0.0
            ATMOSPHERE -> 0.0
            PHENOMENA -> 0.0
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
    /** How many of a thing there may be, where the value is a count — see [counted]. */
    val counts: IntRange? = null,
    /** What naming one of these is worth, where the value is a member of a population — see [population]. */
    val worthOfAMention: Double = Rung.ORDINARY,
    /** How little of a member a word may leave, where the value is a member of a population. */
    val leastKept: Double = Rung.ORDINARY,
    /** What this population calls having none of anything, where it may be emptied at all. */
    val emptiedBy: String? = null,
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
        // Every form a word may ask a ranged axis for, not only a band — see [Setting].
        val looksLikeASpan = kind == Kind.RANGED && Setting.describes(option)
        val isACountItGoesUpTo = option.toIntOrNull()?.let { counts?.contains(it) } == true
        return isOneOfTheNamedOptions || looksLikeASpan || isACountItGoesUpTo || (open && looksLikeARegistryId)
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
         *
         * [worthOfAMention] is how much of it naming one asks for, in multiples of what the Age would have
         * had anyway. It differs by population, and the difference is real: a structure set is **opt-in**,
         * so naming it asks for the ordinary amount of it, where every biome is present already and naming
         * one has to mean *more of that*.
         */
        fun population(
            name: String,
            worthOfAMention: Double = Rung.ORDINARY,
            leastKept: Double = Rung.ORDINARY,
            emptiedBy: String? = null,
            /**
             * Values that are **ours** rather than a registry's, which closes the parameter.
             *
             * Every other population draws from the game — a creature is an entity type, a feature is a
             * placed feature — so it takes any id and complains later, where the missing content bites. A
             * phenomenon has nothing behind it in vanilla, so its values are written down and anything else
             * is a typo rather than an unloaded pack.
             */
            named: List<String> = emptyList(),
        ) = Parameter(
            name,
            listOfNotNull(UNCHANGED, emptiedBy) + named,
            open = named.isEmpty(),
            kind = Kind.POPULATIVE,
            worthOfAMention = worthOfAMention,
            leastKept = leastKept,
            emptiedBy = emptiedBy,
        )

        /**
         * A continuous axis a word may bound — climate's temperature and humidity. Defaults to the whole
         * axis, so an Age told nothing keeps whatever vanilla's noise produced.
         */
        fun ranged(name: String) = Parameter(name, listOf(Span.NATURAL.spelled()), kind = Kind.RANGED)

        /**
         * How many of a thing there are, from none up to [most] — the bodies in a sky.
         *
         * **The one kind of number §3.2 lets near a writer**, because a count is what a person standing in
         * the Age would say about it: "two suns" is a sentence, where a biome's weight is a fact about our
         * arithmetic. Numeral pages will write it directly; until they exist a word like `twinned` carries
         * the number, which is why this is a value and not an enumeration of spellings for it.
         */
        fun counted(name: String, ordinary: Int, most: Int) =
            Parameter(name, listOf(ordinary.toString()), counts = 0..most)
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
