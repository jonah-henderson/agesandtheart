package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.worldgen.biome.ClimateAxis
import net.minecraft.resources.Identifier
import net.minecraft.util.StringRepresentable

/**
 * Every parameter in the world whose value is a block, by name — see [Parameter.material].
 *
 * Lazy rather than eager: each preset enum names [Aspect] in its own initialiser, so building this while
 * `Aspect` is still loading would leave the two classes waiting on each other.
 */
val MATERIAL_PARAMETERS: Set<String> by lazy {
    Aspect.entries
        .flatMap { aspect -> aspect.dials + aspect.authored.flatMap { it.parameters } }
        .filter { it.material }
        .map { it.name }
        .toSet()
}

/** What an aspect that cannot divide has, there being nothing to be companionable with. */
private const val NO_APPETITE = 0.0

/**
 * The parts an Age is assembled from — one preset each, chosen independently (design §3.1).
 *
 * **A new aspect is appended, never inserted.** [co.voik.agesandtheart.age.AgeCharacter.mapFor] and
 * [co.voik.agesandtheart.age.word.Resolver] both stride their seeds on `ordinal`, so inserting one would
 * silently move the territories and draws of every Age already written.
 */
enum class Aspect(
    val key: String,
    /**
     * **What this aspect's own answer holds** (`the-world-model.md` §2), or [Holds.NOTHING] where it has
     * none of its own and is a group of properties and nothing else — a surface and the air are simply
     * where their properties were left.
     *
     * A preset and a referent were never two things: one is a closed catalogue and the other an open one,
     * which [open] answers on its own, so both are [Holds.CATALOGUE].
     *
     * Structures is a weighted set that is still *shaped* as a preset pair, `none` against `vanilla`, whose
     * readiness prior is what makes habitation opt-in. It loses the pair when it is converted, not before —
     * declaring it early would skip the draw and build in every Age.
     */
    val holds: Holds = Holds.NOTHING,
    /**
     * Whether this aspect's value is a registry object rather than a preset written in Kotlin (§3.1) —
     * which decides whether §8's derived vocabulary can reach the aspect at all.
     *
     * Stated rather than derived from [holds], and that is the open-against-closed half of a catalogue: a
     * sea's value *is* a block and a biome's members are registry entries, where a terrain's shapes and a
     * phenomenon's processes are bundles we wrote.
     */
    val open: Boolean = false,
    /**
     * Whether an answer here may be **laid across the map** rather than held everywhere at once (§2).
     *
     * A spatial population satisfies a contradiction by holding each claim somewhere; a singular one has
     * nowhere to put a second answer, so one is displaced and charged for (§8). It needs two things a plain
     * population does not, and `Spread` is both: how much ground each member covers, and what the boundary
     * between them looks like.
     *
     * Two claims, not one: a sky *cannot* divide (a world has one sky over it), where structures *need not*
     * (vanilla places each set against the whole dimension, its biome predicates doing the rest). Biomes
     * never divide, one climate table spanning the world however many terrains carve it up; the surface
     * *could* follow the terrain's division and does not yet; the air divides by **biome** rather than by
     * territory, which is a scope the grammar has and the composition does not (`in <biome>`, §4.3.1); and
     * phenomena are **sited rather than divided** (§5.2) — a process happens at a place and spreads from
     * it, which is not a territory with a boundary however much it sounds like one.
     */
    val spatial: Boolean = false,
    /**
     * Whether this part of the world is **made of** something, and so admits a material where a term is
     * wanted — `land of blackstone`, `a sea of ice`.
     *
     * Being made of a substance is shared, which is why a block is its own page class rather than a term
     * per aspect: the *section* decides whether `ice` means a sea or a stone.
     */
    val madeOfSomething: Boolean = false,
    /**
     * How readily this aspect takes on a second preset the sentence merely happened to like as well — the
     * harmonious division, as opposed to the one settling a contradiction (design §3.4).
     *
     * Terrain is the most reluctant because nothing interpolates between a floating island and a plain;
     * they meet at a seam, and a world full of seams reads as broken rather than varied. Anything that
     * cannot divide has none by construction, and a climate's second territory only ever arrives from a
     * fracture, which is charged by definition.
     */
    val appetiteForCompany: Double = NO_APPETITE,
    /**
     * What a **writer** calls this part of the world, where that differs from [key].
     *
     * **Pack content and commands are pages; saves are keys.** A word file's `aspects`, a
     * `preset_tags/<page>.json`, a `derivation/<page>.json` and `/age compose` are all read by people or
     * re-read from the pack on every load, so a better word may replace them at the cost of editing what
     * ships. [key] is what an Age is *recorded* under — the `options` and `spread` map keys a save holds —
     * and must not move once an Age has been written with it.
     *
     * `terrain` became `landmass` and `carvers` became `depths` for readability, and each rename was a
     * save-format change until the two were separated. They are the two entries that carry both.
     */
    page: String? = null,
) : StringRepresentable {
    /** The shape of the rock. */
    TERRAIN("terrain", Holds.CATALOGUE, spatial = true, madeOfSomething = true, appetiteForCompany = 0.12,
        page = "landmass"),

    /** What fills the space the shape leaves — sea, lava, nothing. */
    SEA("sea", Holds.CATALOGUE, open = true, spatial = true, madeOfSomething = true, appetiteForCompany = 0.18),

    /** What happens beneath the surface: caves cut back out, and where water stands in the rock. */
    CARVERS("carvers", Holds.CATALOGUE, spatial = true, appetiteForCompany = 0.25, page = "depths"),

    /** Which biomes it grows. */
    BIOMES("biomes", Holds.WEIGHTED_SET, open = true),

    /** The vault itself: what colour it is, what cloud hangs in it, how much light it lets down. */
    SKY("sky", Holds.CATALOGUE),

    /** What may be built here. */
    STRUCTURES("structures", Holds.WEIGHTED_SET, open = true, madeOfSomething = true),

    /** The coordinates its biomes are looked up at — how hot it is, and how wet. */
    CLIMATE("climate", Holds.POPULATION, spatial = true),

    /** What the ground wears over whatever it is made of. */
    SURFACE("surface", madeOfSomething = true),

    /** What grows and forms in it: ores, flora, lakes, springs. */
    FEATURES("features", Holds.WEIGHTED_SET, open = true),

    /** What lives in it. */
    SPAWNS("spawns", Holds.WEIGHTED_SET, open = true),

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
    SUN("sun", Holds.POPULATION),

    /** Each thing that circles it. */
    MOON("moon", Holds.POPULATION),

    /** The field behind it all. */
    STARS("stars"),

    /**
     * What the grass of this world is coloured.
     *
     * **Its own aspect, and separate from [LEAVES] on purpose.** Nobody saying "purple grass" means the
     * leaves as well, so one word for both would be wrong far more often than it was convenient. A writer
     * who wants the whole world recoloured says both.
     *
     * **Sited, which is most of what it is for.** `purple grass in swamp` is the interesting sentence, and
     * it costs nothing here: the parameter is `perBiome`, and `Atmosphere.cornersOf` already gathers every
     * biome any confined clause named.
     *
     * A level cannot repaint a biome — it borrows the registry's, and repainting `minecraft:forest` would
     * repaint the overworld's too — so this crosses on the look and is answered per level. See
     * `co.voik.ephemeris.client.GroundTints`.
     */
    GRASS("grass"),

    /** What its leaves are coloured, and its litter and dead brush with them. See [GRASS]. */
    LEAVES("leaves"),

    /**
     * The curtain that stands in it on some nights.
     *
     * **Its own aspect rather than a corner of the vault**, for the same reason the stars have one: it is a
     * separate thing overhead, and a writer aiming a clause at it — `red and green aurora` — must be able to
     * say the colours are the *curtain's* and not the sky's. `Vocabulary.aimingPages` mints the page, which
     * is also why there is no `art/word/aurora.json`: an aspect already is its page, and `auroral` is the
     * word that asks for one.
     *
     * **Describing it asserts it.** It holds nothing, so no clause can mint a member the way a clause mints
     * a sun; what it has is dials, and anything set on one is a writer saying the Age has an aurora. See
     * [Sky.auroraIn].
     */
    AURORA("aurora"),

    /**
     * The bow that stands opposite the Age's light when the weather makes one.
     *
     * **Its own aspect for the aurora's reason**: a writer aiming a clause at it — `red and yellow rainbow`
     * — must be able to say the colours are the *bow's* and not the sky's. `Vocabulary.aimingPages` mints
     * the page, which is why there is no `art/word/rainbow.json`; `rainbows` is the word that asks for one.
     *
     * **Describing it asserts it**, likewise. It holds nothing, so no clause can mint a member the way a
     * clause mints a sun; what it has is dials, and anything set on one is a writer saying the Age has
     * bows. See [Sky.rainbowIn].
     *
     * **Almost nothing about where it goes is written here**, which is the whole of what makes it cheap: a
     * bow is a circle about the point opposite whatever lights it, so an Age with two suns has two of them
     * and one whose sun climbs past the arc's own radius has none at midday. Neither is a case anything
     * here or in Ephemeris was taught.
     */
    RAINBOW("rainbow"),

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
    PHENOMENA("phenomena", Holds.WEIGHTED_SET, open = true),

    /**
     * What the clouds over this world are coloured.
     *
     * **Its own aspect rather than a second colour on the vault** (Jonah, 2026-08-31). The sky and the
     * clouds in it are two things a writer means separately — *blue sky, green clouds* has to be sayable —
     * and while they shared an aspect neither could be `colour`, so both had to be named outright in every
     * colour word. The same argument that gave the grass and the leaves one each.
     *
     * **Appended rather than slotted beside [SKY]**, which the note at the top of this file requires: the
     * seeds stride on `ordinal`, so an aspect inserted anywhere but the end moves the draws of every Age
     * already written.
     */
    CLOUD("cloud", page = "clouds"),
    ;

    /** What a writer calls this part of the world — [key] where no other name was given. */
    val page: String = page ?: key

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
            SUN, MOON, STARS, GRASS, LEAVES, CLOUD, AURORA, RAINBOW,
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
            SUN -> listOf(Sky.ABSENT, Sky.SUNSIZE, Sky.SUNCOLOUR, Sky.RISING)
            MOON -> listOf(Sky.ABSENT, Sky.RISING)
            STARS -> listOf(Sky.STARS, Sky.STARGLOW)
            GRASS -> listOf(Atmosphere.GRASSCOLOUR)
            LEAVES -> listOf(Atmosphere.LEAFCOLOUR)
            AURORA -> listOf(Sky.AURORACOLOUR, Sky.AURORAGLOW, Sky.AURORASIZE, Sky.AURORAFREQUENCY)
            RAINBOW -> listOf(
                Sky.RAINBOWCOLOUR,
                Sky.RAINBOWGLOW,
                Sky.RAINBOWSIZE,
                Sky.RAINBOWFREQUENCY,
                Sky.RAINBOWRAIN,
            )
            // A preset aspect with dials: the two switches that pick the Age's dimension type. They sit
            // here rather than on `Atmosphere` because they are chosen when the Age is *made* and baked
            // into a pre-authored file, where every atmosphere dial is laid over a level that is already
            // open — which is also why these two alone cannot be confined to a biome.
            SKY -> listOf(
                Atmosphere.SKY,
                Atmosphere.CEILING,
                Sky.SEALED,
            )
            CLOUD -> listOf(Atmosphere.CLOUD)
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
     * Whether this aspect's members are **described into being** rather than drawn from a pool — so what a
     * composition stores for it *is* the roll, one entry per member (`the-world-model.md` §2).
     *
     * Exactly [Holds.POPULATION], which is what climate becoming one bought: it seats no preset and its
     * territories can only be the entries themselves, each holding a span per axis, so it was already this
     * shape while being spelled as a special case in five places.
     */
    val membersAreDescribed: Boolean get() = holds == Holds.POPULATION

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
        AIR, WATERS, WEATHER, SUN, MOON, STARS, GRASS, LEAVES, CLOUD, AURORA, RAINBOW,
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
    /**
     * Whether this parameter's value is a **block** — the palette's stone, a surface's skin, a minted
     * pattern's substance.
     *
     * A fact about the parameter rather than a name to compare against. `Grammar` asks whether a word is a
     * material and `Resolver` asks what substance it carries, and both did it by looking for `Terrain.STONE`
     * by name — one aspect's knob standing in for "a block", which is true of the corpus today and is not
     * what either of them means.
     */
    val material: Boolean = false,
    /**
     * Whether the order the writer wrote these in is part of what they said.
     *
     * **This knob's values are a sequence, where every other mingling knob's are a set** — and that is the
     * whole of the distinction. `landmass.stone=granite and andesite` is two rocks in one wall and neither
     * is first; an aurora's colours are a ramp from its crown to its hem, and which is the crown is the one
     * thing the writer stated outright.
     *
     * It is read in exactly one place, [co.voik.agesandtheart.age.word.Resolver.contended], which otherwise
     * hands back a mingling in tier-then-seeded order. Stating it here rather than casing on the name there
     * is what keeps that function from having to know about individual knobs.
     *
     * Written order decides one other thing in the whole resolver — which template a book starts from — and
     * `the-art-design.md` §3.5 names both.
     */
    val keepsWrittenOrder: Boolean = false,
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
        return isOneOfTheNamedOptions || looksLikeASpan || (open && looksLikeARegistryId)
    }

    companion object {
        /**
         * What a material parameter reads as when nobody named one: the preset's own substance. A named
         * default rather than an absent value, so every consumer asks the same question.
         */
        const val UNCHANGED = "unchanged"

        /** A block a preset is made of — the palette's stone, a terrain's spires, a structure's walls. */
        fun material(name: String) = Parameter(name, listOf(UNCHANGED), open = true, material = true)

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
         * A property that is simply true or false — **a catalogue of two, and no new shape** (world model
         * §2). A flag is one value drawn from a closed list like any other; what it is not is a scale, and
         * spelling it as one invites a value between the two that does not exist.
         *
         * [FALSE] first, so an unstated flag is off: [default] is the first option.
         */
        fun flag(name: String) = Parameter(name, FALSE, TRUE)

        const val TRUE = "true"
        const val FALSE = "false"

        /**
         * What an option reads as when a writer left it alone — whatever the world would have done.
         *
         * **First in the list wherever it appears, so it is the [default]**, and named for that rather
         * than for what it happens to mean in any one place.
         */
        const val DEFAULT = "default"
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
     * Nothing of its own — the property is a group of other properties, and what it *is* is where they were
     * left. Only an aspect is ever this; a parameter always holds something.
     */
    NOTHING,

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
     * Each member holds properties of its own, so this is the recursive kind. A member is minted by the
     * clause that describes it and there is no number anywhere: `a sun. a sun.` is two suns because it is
     * two clauses, which is what took counts out of the language.
     *
     * Not to be confused with [WEIGHTED_SET], which the *aspects* used to call a population: a jungle is a
     * kind the world has more or less of, where a sun is one of several individuals.
     */
    POPULATION,
    ;

}
