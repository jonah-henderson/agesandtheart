package co.voik.agesandtheart.age.aspect

import net.minecraft.resources.ResourceLocation
import net.minecraft.util.StringRepresentable

/**
 * The parts an Age is assembled from — one preset each, chosen independently.
 *
 * This is what turns the preset list from a *menu* into a *grammar*. When every preset was a whole
 * world you picked exactly one and got everything it decided; typed by aspect, "Spire islands over a sea,
 * riddled beneath" is an ordinary sentence, and a combination nobody wrote by hand is still sensible
 * because a terrain can only ever land in the terrain aspect.
 *
 * It is nearly free, because [co.voik.agesandtheart.worldgen.AgeChunkGenerator]'s constructor was
 * already this list: `(biomeSource, field, seaFill, palette, carvers, waterTable, structures)`.
 *
 * **Expect this list to grow.** The design (`notes/the-art-design.md` §3.1) names two more — *contents*
 * (flora, ores, spawns) and *phenomena* (weather, disasters) — deliberately left out until there is an
 * implementation behind them, because a aspect the grammar can name but nothing can fill is worse than a
 * aspect that does not exist yet.
 */
enum class Aspect(val key: String) : StringRepresentable {
    /** The shape of the rock. */
    TERRAIN("terrain"),

    /** What fills the space the shape leaves — sea, lava, nothing. */
    SEA("sea"),

    /** What happens beneath the surface: caves cut back out, and where water stands in the rock. */
    CARVERS("carvers"),

    /**
     * Which biomes it grows.
     *
     * **In the dressing's old slot deliberately.** Removing an aspect shifts every later ordinal, and the seeds
     * stride on `ordinal` — so taking the place of what it replaces is what keeps [SKY], [STRUCTURES] and
     * [CLIMATE] exactly where they were, and leaves only the Ages that named a dressing to change.
     */
    BIOMES("biomes"),

    /** What is overhead. */
    SKY("sky"),

    /**
     * What may be built here.
     *
     * **Appended, and every new aspect must be** — [co.voik.agesandtheart.age.AgeCharacter.mapFor] and
     * [co.voik.agesandtheart.age.word.Resolver] both stride their seeds on `ordinal`, so inserting one
     * ahead of an existing aspect would silently move the territories and draws of every Age already
     * written.
     */
    STRUCTURES("structures"),

    /**
     * The coordinates its biomes are looked up at — how hot it is, and how wet.
     *
     * Appended, like [STRUCTURES] and for the same reason: the seeds stride on `ordinal`.
     */
    CLIMATE("climate"),
    ;

    /**
     * Whether this aspect's value **is** a registry object rather than a preset written in Kotlin
     * (design §3.1).
     *
     * A closed aspect's value is code — a terrain is a field tree, a sky is a dimension type, and nothing
     * in a registry entry tells you how to build either. An open aspect's value is a thing the game already
     * has, and wrapping it in a preset of our own would add a name to maintain and buy nothing. This is
     * what decides whether §8's derived vocabulary can reach a aspect at all.
     */
    val open: Boolean
        get() = when (this) {
            SEA -> true
            // Structures is sorted under the *open* aspects in the design, a `StructureSet` being a registry
            // object like any other — and it is closed here anyway, because what opening it needs is a way to
            // hold several named sets at once rather than a value drawn from a registry. See [Structures].
            TERRAIN, CARVERS, BIOMES, SKY, STRUCTURES, CLIMATE -> false
        }

    /**
     * Every preset for this aspect that is written in Kotlin — the whole pool for a closed aspect, and none
     * of it for an open one, whose pool is data (§8.2, and [co.voik.agesandtheart.age.word.Vocabulary]).
     *
     * **Ask the vocabulary rather than this** unless you specifically mean the authored ones: an open
     * aspect answers `emptyList()` here and would silently resolve to nothing.
     *
     * A computed `when` rather than a stored list, and that is not incidental: each of these enums names
     * [Aspect] in its own initialiser, so building the list eagerly here would have the two classes waiting
     * on each other. It is asked once per aspect when a sentence is resolved, which is nothing.
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
     * The preset this aspect means by [key], or null where the key names nothing it can hold.
     *
     * The single place a key becomes a preset, so a recipe, a `preset_tags` file and `/age compose` can
     * never disagree about what one spells. An open aspect accepts an id it has never heard of — that is
     * the whole point of being open — and complains later, where the missing content actually bites.
     */
    fun presetFor(key: String): AspectPreset? = when (this) {
        SEA -> Sea.named(key)
        TERRAIN, CARVERS, BIOMES, SKY, STRUCTURES, CLIMATE -> authored.firstOrNull { it.key == key }
    }

    /**
     * Whether this aspect is answered **per column** rather than once for the whole world — which is the
     * criterion for everything that follows (design §3.4).
     *
     * A positional aspect can satisfy a contradiction *by coexistence*: both terms honoured, in different
     * places, so the world merely gets strange. A singular one has nowhere to put a second answer, and
     * that is where the harsher registers of instability earn their place.
     *
     * **Two aspects are singular, for two different reasons, and the difference is worth keeping straight.**
     * A sky *cannot* divide, because a world has one sky over it and there is no second place to put another.
     * (**The reason changed on 2026-07-29 and the conclusion did not.** It used to read "it is a dimension type,
     * registered once for the dimension" — true then, false now: a sky is a [co.voik.agesandtheart.sky.SkySpec]
     * sent to the client, and could divide as easily as terrain does. It still must not, and the honest reason is
     * the physical one, not the technical one that happened to enforce it.) Structures *need not*: vanilla holds
     * many of them
     * natively and places each against the whole dimension, its own biome predicates keeping a village out of
     * ground that has no villages, so a territory map would buy nothing (design §3.1, "native multiplicity").
     *
     * It predicts the aspects that do not exist yet: *contents* is positional, so it will be set-valued.
     */
    val positional: Boolean
        get() = when (this) {
            // Climate divides for a reason none of the others do: not because two *presets* could not be
            // reconciled — it has only one — but because two words bounded one of its axes to stretches that do
            // not overlap. That is a fracture at the parameter level, and it needs ground to put each half on.
            TERRAIN, SEA, CARVERS, CLIMATE -> true
            // Biomes never divide: one climate table spans the world however many terrains carve it up, and
            // vanilla's table holds many biomes natively (design §3.1, "native multiplicity").
            SKY, STRUCTURES, BIOMES -> false
        }

    /**
     * How readily this aspect takes on a *second* preset that the sentence merely happened to like as well —
     * the harmonious division, as opposed to the one that settles a contradiction (design §3.4).
     *
     * They differ per aspect because coexistence is not equally easy to look at. **Terrain is the reluctant
     * one**, and deliberately: two shapes in one world is the hardest coexistence to make read well, since
     * nothing interpolates between a floating island and a plain — they meet at a seam, and a world full of
     * seams reads as broken rather than varied. So an Age of two shapes stays a thing you remember.
     *
     * **Dressing used to be the most companionable of them (0.45) and is now the least**, which is Jonah's
     * correction of a real over-reach (design §3.4, "what regions are for"). The old justification was that
     * distributing several kinds of place across a map is what Minecraft's biomes already do — and that is
     * an argument for *biomes* doing it, borrowed to license the *aspect* dividing instead. Dressing is the
     * one positional aspect with a softer native mechanism underneath it, so variety belongs there and a
     * dressing seam is reserved for two policies that genuinely cannot share a climate table.
     *
     * Not zero, because that softer mechanism cannot express everything: `plasma` is a fixed alien biome
     * and `overworld` is vanilla's whole table, and no weighting reconciles those two.
     *
     * Zero for the two aspects that hold one answer at a time, whether because they cannot divide or because
     * they have no need to — see [positional].
     */
    val appetiteForCompany: Double
        get() = when (this) {
            TERRAIN -> 0.12
            SEA -> 0.18
            CARVERS -> 0.25
            BIOMES -> 0.0
            SKY -> 0.0
            STRUCTURES -> 0.0
            // Nothing to be companionable *with*: climate has one preset, so a second seat only ever arrives
            // from a fracture, which is charged by definition and never harmony.
            CLIMATE -> 0.0
        }

    override fun getSerializedName(): String = key
}

/**
 * One choice a preset offers — "sparse / scattered / crowded", never a 0–1 slider.
 *
 * Discreteness is deliberate (design §3.2): it is how writing a symbol should feel — you write a word,
 * not a number — it matches the call already made for instancing, where yaw and scale are finite sets,
 * and it makes cost and mastery gating trivial to express later. We interpret the options liberally at
 * our end; the writer only ever picks one of a handful.
 *
 * The first option is the default, so a preset named with no options at all still resolves.
 *
 * **One kind is open-valued: a [material]** (§3.2). It takes a registry id rather than one of a list, and
 * it is an exception rather than a loophole — a block id is a *word*, with a name and a price, which is
 * what §3.2 was actually protecting. Only the finiteness of the list is given up.
 */
data class Parameter(
    val name: String,
    val options: List<String>,
    val open: Boolean = false,
    /**
     * What a value **claims**, which decides what two of them unjoined mean (design §3.2).
     *
     * [Kind.PREDICATIVE] says something about the whole — "the rock *is* blackstone" — so two of them
     * conflict, contend, and want a conjunction to reconcile. [Kind.POPULATIVE] asserts the presence of a
     * part — "there *are* cherry groves" — so two of them simply accumulate, and a conjunction would be
     * ceremony over something that was never in dispute. [Kind.RANGED] bounds a continuous axis — "the
     * temperature *lies between* these" — so two of them broaden unless they actually disagree.
     */
    val kind: Kind = Kind.PREDICATIVE,
) {
    /** See [Parameter.kind]. */
    enum class Kind {
        PREDICATIVE,
        POPULATIVE,

        /**
         * A [Span] on a continuous axis, and the third combining rule.
         *
         * Two of these **broaden** into the span that holds both, which is the opposite of how a predicative
         * parameter behaves and deliberately so (Jonah, 2026-07-29): a word carrying a span is *evocative* about
         * that axis, and an evocative word tilts rather than narrows, so a world trying to be two things is
         * broader. *"It becomes a technique astute writers can pick up on to add variety to their ages."*
         *
         * They **fracture** instead when the words genuinely disagree, and what decides that is the **antonym
         * table** — the same `oppositionBetween` the instability index already consults. Without an opposition
         * between them, two spans miles apart would silently broaden into exactly the mush §3.3 exists to
         * prevent, which is why `:common:vocabularycheck` enforces that disjoint spans have an antonym between
         * them.
         */
        RANGED,
    }

    val default: String get() = options.first()

    constructor(name: String, vararg options: String) : this(name, options.toList())

    /**
     * Whether this parameter would understand [option].
     *
     * An open one takes any well-formed id — including one naming content this pack does not have, for the
     * same reason an open *aspect* does (§3.1): a save moving between modpacks must keep saying what it said.
     */
    fun accepts(option: String): Boolean {
        val isOneOfTheNamedOptions = option in options
        val looksLikeARegistryId = namesReferent(option) && ResourceLocation.tryParse(option) != null
        val looksLikeASpan = kind == Kind.RANGED && Span.describes(option)
        return isOneOfTheNamedOptions || looksLikeASpan || (open && looksLikeARegistryId)
    }

    companion object {
        /**
         * What a material parameter reads as when nobody named one: the preset's own substance, unchanged.
         *
         * A named default rather than an absent value, so [Options.of] has something to return and every
         * consumer can ask the same question — "is this the default, or a block?" — instead of each
         * inventing its own null handling.
         */
        const val UNCHANGED = "unchanged"

        /**
         * A block a preset is made of — the palette's stone today, a terrain's spires and a structure's
         * walls later (§3.2, "spikes made of copper blocks").
         *
         * Built generally now rather than as a palette feature specifically, which is the whole reason it
         * is worth a named constructor: the later consumers then cost a parameter declaration each instead
         * of a mechanism each.
         */
        fun material(name: String) = Parameter(name, listOf(UNCHANGED), open = true)

        /**
         * A set of registry entries that are *present* here — the biomes an Age draws from today, contents
         * later (design §3.2).
         *
         * Populative, so naming one adds it and naming two adds both: **inclusive by default** (Jonah's
         * call). Fine-grained control is `only` and `except`, which are modifiers and arrive with the
         * grammar — the precision ladder applied to a list rather than a mechanism of its own. The
         * alternative, exclusive-by-default, would have made a beginner's one-word Age monotonous and left
         * richness reachable only by saying nothing.
         */
        fun population(name: String) = Parameter(name, listOf(UNCHANGED), open = true, kind = Kind.POPULATIVE)

        /**
         * A continuous axis a word may bound — climate's temperature and humidity (design §3.1, §3.2's
         * clarification).
         *
         * Its default is the whole axis, so an Age that was told nothing keeps whatever vanilla's noise already
         * produced — which is what makes this migration cost no existing Age a single block.
         */
        fun ranged(name: String) = Parameter(name, listOf(Span.NATURAL.spelled()), kind = Kind.RANGED)
    }
}

/** A preset that fills a [aspect], possibly offering a few [parameters] to steer it. */
interface AspectPreset : StringRepresentable {
    val key: String
    val aspect: Aspect
    val parameters: List<Parameter> get() = emptyList()

    /**
     * Whether a *sentence* may ask for this preset, as opposed to only a **pinned recipe** naming it outright.
     *
     * Almost every preset is askable, and `:common:vocabularycheck` insists on it: a preset no word can reach is
     * content nobody can use, since it will still turn up when the seed draws an unconstrained aspect but a writer
     * who wants it has no way to say so.
     *
     * **The exception arrived with the Spire (2026-07-29), and it is a category rather than a special case.** Jonah's
     * design for bespoke Ages is *"carefully pinned presets and occasional special exceptions, like the sky/star
     * effects"* — so a preset can exist purely to be pinned by `AgeRecipe.worldFor`, and being unaskable is then the
     * *point*: it is what keeps an easter egg an easter egg. [Sky.SPIRE] is the first.
     *
     * Saying so here rather than letting the check infer it from a missing `preset_tags` entry is deliberate. An
     * omission and an intention look identical from the outside, and the check would have to stop distinguishing
     * them — so it asks this instead, and separately insists that anything answering `false` really is pinned
     * somewhere. A careless `false` therefore still fails.
     */
    val askableInASentence: Boolean get() = true

    /**
     * Whether this preset would actually *do* anything with [parameter], as opposed to merely recognising
     * the name.
     *
     * The two come apart more often than they look. Every dressing declares `stone` and `biomes` so that a
     * writer naming one is not told it is a typo — but `overworld` cannot wear a material and `bare_rock`
     * has no biome table to enrich, so for each of them one of those knobs is inert.
     *
     * The resolver reads this to **prefer a preset that can honour what the sentence asked for**, which is
     * what stops "a cherry grove Age" quietly drawing a dressing with no biomes in it. Where nothing in the
     * aspect can honour it, the word is charged rather than dropped (§3.3).
     */
    fun honours(parameter: Parameter): Boolean = parameters.any { it.name == parameter.name }

    /**
     * The same question asked by name, which is how the resolver has it — a recipe carries parameter names,
     * not [Parameter] objects.
     *
     * False for a name this preset never declared, so "does not offer it" and "offers it but does nothing
     * with it" answer alike here. The two are only worth telling apart when reporting a *typo*, which is
     * [Options.unknownTo]'s job.
     */
    fun honoursParameterNamed(name: String): Boolean {
        val declared = parameters.firstOrNull { it.name == name } ?: return false
        return honours(declared)
    }

    override fun getSerializedName(): String
}
