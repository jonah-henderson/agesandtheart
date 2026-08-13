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
    TERRAIN("landmass"),

    /** What fills the space the shape leaves — sea, lava, nothing. */
    SEA("sea"),

    /** What happens beneath the surface: caves cut back out, and where water stands in the rock. */
    CARVERS("depths"),

    /** Which biomes it grows. */
    BIOMES("biomes"),

    /** The vault itself: what colour it is, what cloud hangs in it, how much light it lets down. */
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

    /**
     * What the air looks like and does — its fog, its tint, what hangs in it.
     *
     * **Its page shadows the block `minecraft:air`**, which is the one name collision the aspect list has.
     * The page wins, deliberately: a writer saying `air` means the part of the world far more often than
     * they mean the block, and the block is still reachable by its full id — every derived word answers to
     * that as well as to its bare path.
     */
    AIR("air"),

    /** What being *in* the water is like. Its own part of the world, where the sea is which fluid. */
    WATERS("waters"),

    /** What falls out of the air. */
    WEATHER("weather"),

    /** Each star this world goes round. */
    SUN("sun"),

    /** Each thing that circles it. */
    MOON("moon"),

    /** The field behind it all. */
    STARS("stars"),

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
     * **What this aspect's own answer holds**, or null where it has none of its own and is a group of
     * properties and nothing else — a climate, a surface and the air are simply where their properties
     * were left (`the-world-model.md` §2).
     *
     * A preset and a referent were never two things: one is a closed catalogue and the other an open one,
     * which [open] answers on its own, so both are [Holds.CATALOGUE] here.
     *
     * Structures is a weighted set that is still *shaped* as a preset pair, `none` against `vanilla`, whose
     * readiness prior is what makes habitation opt-in. It loses the pair when it is converted, not before —
     * declaring it early would skip the draw and build in every Age.
     */
    val holds: Holds?
        get() = when (this) {
            TERRAIN, CARVERS, SKY, SEA -> Holds.CATALOGUE
            BIOMES, STRUCTURES, FEATURES, SPAWNS, PHENOMENA -> Holds.WEIGHTED_SET
            // Bodies are described into being rather than chosen, which is what a population is — and
            // until minting is built they are still read as a count, so nothing draws on this yet.
            SUN, MOON -> Holds.POPULATION
            CLIMATE, SURFACE, AIR, WATERS, WEATHER, STARS -> null
        }

    /**
     * Whether this aspect's value is a registry object rather than a preset written in Kotlin (§3.1) —
     * which decides whether §8's derived vocabulary can reach the aspect at all.
     *
     * Not derivable from [holds], and that is the open-against-closed half of a catalogue: a sea's value
     * *is* a block and a biome's members are registry entries, where a terrain's shapes and a phenomenon's
     * processes are bundles we wrote.
     */
    val open: Boolean
        get() = when (this) {
            SEA, BIOMES, STRUCTURES, FEATURES, SPAWNS, PHENOMENA -> true
            TERRAIN, CARVERS, SKY, CLIMATE, SURFACE, AIR, WATERS, WEATHER, SUN, MOON, STARS -> false
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
            CARVERS -> Carvers.entries
            SKY -> Sky.entries
            // Nothing to choose between: a climate and a surface are where their dials were left, and a
            // biome or a structure set is weighed rather than chosen. See [dials] and [Holds.WEIGHTED_SET].
            PHENOMENA -> Phenomenon.entries
            SEA, BIOMES, STRUCTURES, CLIMATE, SURFACE, FEATURES, SPAWNS, AIR, WATERS, WEATHER,
            SUN, MOON, STARS,
            -> emptyList()
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
            // Water boiling away is what a temperature does, not what the air is like.
            CLIMATE -> ClimateAxis.entries.map { it.parameter } + Atmosphere.EVAPORATION
            // A biome's population is the aspect's answer; `footing` says how it is *worn*, not which.
            BIOMES -> listOf(Biomes.GROWN, Biomes.FOOTING)
            STRUCTURES -> listOf(Structures.BUILT)
            SURFACE -> listOf(Surface.MATERIAL)
            FEATURES -> listOf(Features.PLACES, Features.SIZE, Features.THICKNESS, Features.HEIGHT)
            SPAWNS -> listOf(Spawns.LIVES)
            PHENOMENA -> listOf(Phenomena.HAPPENS)
            AIR -> listOf(Atmosphere.FOG, Atmosphere.TINT, Atmosphere.MOTES, Atmosphere.HAZE)
            WATERS -> listOf(Atmosphere.MURK)
            WEATHER -> listOf(Atmosphere.RAINFALL, Atmosphere.THUNDER)
            SUN -> listOf(Sky.SHINING, Sky.SUNSIZE, Sky.SUNCOLOUR, Sky.RISING)
            MOON -> listOf(Sky.ORBITING, Sky.RISING)
            STARS -> listOf(Sky.STARS)
            // A preset aspect with dials: the two switches that pick the Age's dimension type. They sit
            // here rather than on `Atmosphere` because they are chosen when the Age is *made* and baked
            // into a pre-authored file, where every atmosphere dial is laid over a level that is already
            // open — which is also why these two alone cannot be confined to a biome.
            SKY -> listOf(
                Atmosphere.SKY,
                Atmosphere.CLOUD,
                Atmosphere.CEILING,
                Sky.SEALED,
            )
            TERRAIN, SEA, CARVERS -> emptyList()
        }

    /**
     * Whether a parameter by this name means anything here — its own [dials], or a knob one of its
     * presets offers.
     *
     * Static, and deliberately so: this is asked while the vocabulary is still being built, before there
     * is a corpus to ask. It sees less than [co.voik.agesandtheart.age.word.Vocabulary.turnsAKnob], which
     * can also reach an open aspect's data presets — but every steering knob an open aspect has is one of
     * its dials, so for the question of *which aspect owns a name* the two agree.
     */
    fun ownsParameterNamed(name: String): Boolean =
        dials.any { it.name == name } || authored.any { it.honoursParameterNamed(name) }

    /**
     * Whether [key] is one of *this* aspect's presets, by exact name.
     *
     * Asked of [authored] rather than [presetFor], and that is the whole of why this is safe: an open
     * aspect makes a preset out of any id it is handed, so `Biome.named("alps")` succeeds — a bare path is
     * a valid identifier — and asking every aspect whether it knows `alps` would widen a terrain word into
     * the biomes, the spawns, the features and the structures at once. An authored list cannot do that.
     */
    fun ownsPresetNamed(key: String): Boolean = authored.any { it.key == key }

    /** Whether nothing is ever drawn to fill this aspect: its answer is its members or its properties. */
    val seatsNothing: Boolean get() = holds != Holds.CATALOGUE

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
        TERRAIN, CARVERS, SKY, CLIMATE, SURFACE, PHENOMENA,
        AIR, WATERS, WEATHER, SUN, MOON, STARS,
        -> authored.firstOrNull { it.key == key }
    }

    /**
     * Whether a clause about this may be **confined to one biome** — `spawns only slime in mushroom_fields`.
     * True where any parameter of this aspect can be sited, which is where vanilla resolves the value
     * through the biome.
     *
     * A phenomenon is deliberately not among them: it is *sited* rather than resolved per biome (§5.2), so
     * `in <biome>` would be the wrong scope for it entirely.
     */
    val confinable: Boolean get() = confinableParameters.isNotEmpty()

    /**
     * The parameters of this aspect a claim may be sited on — what [confinable] is the existence of.
     *
     * Derived rather than listed, so a parameter that gains or loses siting says so in one place. This
     * replaced a hand-kept list on `Atmosphere` that had already drifted from the `when` above it.
     */
    val confinableParameters: List<Parameter>
        get() = (dials + authored.flatMap { it.parameters }).filter { it.confinable }

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
            CARVERS, BIOMES, SKY, CLIMATE, FEATURES, SPAWNS, PHENOMENA,
            AIR, WATERS, WEATHER, SUN, MOON, STARS,
            -> false
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
            // The surface *could* follow the terrain's division and does not yet — one skin, Age-wide.
            // Features are per biome in vanilla and per Age here, so nothing divides them yet — `in
            // <biome>` (§4.3.1) is the shape that would.
            // The air divides by *biome* rather than by territory, which is a scope the grammar has and
            // the composition does not — see `in <biome>` (§4.3.1).
            // Phenomena is **sited rather than divided** (§5.2): a process happens at a place and spreads
            // from it, the way §5.1's hostility is a gradient around a wound — which is not a territory
            // with a boundary, and so is not this flag however much it sounds like one.
            SKY, STRUCTURES, BIOMES, SURFACE, FEATURES, SPAWNS, PHENOMENA,
            AIR, WATERS, WEATHER, SUN, MOON, STARS,
            -> false
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
            AIR -> 0.0
            WATERS -> 0.0
            WEATHER -> 0.0
            SUN -> 0.0
            MOON -> 0.0
            STARS -> 0.0
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
 * [Holds.RANGE] parameter never is: a writer says a word, the word carries the span. Named steps were the
 * first reading of that rule and they do not scale — every new word that wants to sit on a different band
 * needs a new step, and steps must then be named on every axis at once.
 *
 * One kind is open-valued: a [material] takes a registry id rather than one of a list.
 */
data class Parameter(
    val name: String,
    val options: List<String>,
    val open: Boolean = false,
    /** What this property holds, which decides what a claim on it can mean — see [Holds]. */
    val holds: Holds = Holds.CATALOGUE,
    /** How many of a thing there may be, where the value is a count — see [counted]. */
    val counts: IntRange? = null,
    /** What naming one of these is worth, where the value is a member of a population — see [population]. */
    val worthOfAMention: Double = Rung.ORDINARY,
    /** How little of a member a word may leave, where the value is a member of a population. */
    val leastKept: Double = Rung.ORDINARY,
    /** What this population calls having none of anything, where it may be emptied at all. */
    val emptiedBy: String? = null,
    /**
     * Whether a claim on this may be sited in one biome — `in <biome>` (design §4.3.1). Set with [perBiome].
     *
     * **Asked per parameter rather than per aspect**, and the difference is not pedantry: the air's fog may
     * be sited and its temperature may not, because temperature is what *chooses* the biome and siting it
     * is circular. An aspect holding both would have no honest answer.
     */
    val confinable: Boolean = false,
) {
    /** This parameter, sited-in-a-biome — see [confinable]. */
    fun perBiome(): Parameter = copy(confinable = true)

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
        val looksLikeASpan = holds == Holds.RANGE && Setting.describes(option)
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
            holds = Holds.WEIGHTED_SET,
            worthOfAMention = worthOfAMention,
            leastKept = leastKept,
            emptiedBy = emptiedBy,
        )

        /**
         * A continuous axis a word may bound — climate's temperature and humidity. Defaults to the whole
         * axis, so an Age told nothing keeps whatever vanilla's noise produced.
         */
        fun ranged(name: String) = Parameter(name, listOf(Span.NATURAL.spelled()), holds = Holds.RANGE)

        /**
         * How many of a thing there are, from none up to [most] — the bodies in a sky.
         *
         * **The one kind of number §3.2 lets near a writer**, because a count is what a person standing in
         * the Age would say about it: "two suns" is a sentence, where a biome's weight is a fact about our
         * arithmetic. Numeral pages will write it directly; until they exist a word like `twinned` carries
         * the number, which is why this is a value and not an enumeration of spellings for it.
         */
        fun counted(name: String, ordinary: Int, most: Int) =
            Parameter(name, listOf(ordinary.toString()), holds = Holds.POPULATION, counts = 0..most)
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

/**
 * **What one property holds** — the whole of the world model, and everything else falls out of it
 * (`the-world-model.md` §2).
 *
 * The three kinds decide what a claim on the property can mean, which operators are legal there, and
 * whether it needs tagging — so nothing anywhere has to author those rules separately.
 */
enum class Holds {
    /**
     * One value on a continuous axis — a temperature, a fog distance, how large a vein is.
     *
     * **Self-describing, so no range is ever tagged**: a word bounds it and nothing has to be told which
     * temperatures are hot. Two claims broaden into the span holding both, since a word carrying a span is
     * evocative about that axis, and they fracture instead where the antonym table says the two disagree —
     * without which two spans miles apart would silently average into mush.
     */
    RANGE,

    /**
     * One value drawn from a list — a terrain's shape, a colour, a block. Closed (three skies) or open
     * (every block in the game), which the property says separately.
     *
     * **The kind that needs tags** (world model §7): a vague word choosing among seventeen shapes has
     * nothing to go on, where a range answers for itself.
     */
    CATALOGUE,

    /**
     * A distribution the template ships and claims skew — the biomes, the things placed in the ground, the
     * structures, the creatures.
     *
     * Members are usually named from a registry, and may also be brought into being by description
     * (`gold block veins`). Never counted: `teeming jungles` is a weight, since there is one jungle and the
     * world has more or less of it.
     */
    WEIGHTED_SET,

    /**
     * Individuals that exist only because somebody described them — the suns, the moons.
     *
     * Each member holds properties of its own, so this is the recursive kind. **Not built**: `suns` and
     * `moons` are still read as counts, and turning them into a population is what removes numbers from
     * the language entirely.
     *
     * Not to be confused with [WEIGHTED_SET], which the *aspects* used to call a population: a jungle is a
     * kind the world has more or less of, where a sun is one of several individuals.
     */
    POPULATION,
    ;

    /** Whether this holds a single answer — what `and` forces two of, and what may fracture. */
    val isOneValue: Boolean get() = this == RANGE || this == CATALOGUE
}
