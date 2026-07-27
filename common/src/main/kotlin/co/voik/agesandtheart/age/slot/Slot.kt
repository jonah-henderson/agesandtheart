package co.voik.agesandtheart.age.slot

import net.minecraft.resources.ResourceLocation
import net.minecraft.util.StringRepresentable

/**
 * The parts an Age is assembled from — one preset each, chosen independently.
 *
 * This is what turns the preset list from a *menu* into a *grammar*. When every preset was a whole
 * world you picked exactly one and got everything it decided; typed by slot, "Spire islands over a sea,
 * riddled beneath" is an ordinary sentence, and a combination nobody wrote by hand is still sensible
 * because a landform can only ever land in the landform slot.
 *
 * It is nearly free, because [co.voik.agesandtheart.worldgen.FieldChunkGenerator]'s constructor was
 * already this list: `(biomeSource, field, ambient, palette, carvers, waterTable, structures)`.
 *
 * **Expect this list to grow.** The design (`notes/the-art-design.md` §3.1) names two more — *contents*
 * (flora, ores, spawns) and *phenomena* (weather, disasters) — deliberately left out until there is an
 * implementation behind them, because a slot the grammar can name but nothing can fill is worse than a
 * slot that does not exist yet.
 */
enum class Slot(val key: String) : StringRepresentable {
    /** The shape of the rock. */
    LANDFORM("landform"),

    /** What fills the space the shape leaves — sea, lava, nothing. */
    MEDIUM("medium"),

    /** What happens beneath the surface: caves cut back out, and where water stands in the rock. */
    SUBSURFACE("subsurface"),

    /** What it looks and grows like — its biomes and the blocks they are painted in. */
    DRESSING("dressing"),

    /** What is overhead. */
    SKY("sky"),
    ;

    /**
     * Whether this slot's value **is** a registry object rather than a preset written in Kotlin
     * (design §3.1).
     *
     * A closed slot's value is code — a landform is a field tree, a sky is a dimension type, and nothing
     * in a registry entry tells you how to build either. An open slot's value is a thing the game already
     * has, and wrapping it in a preset of our own would add a name to maintain and buy nothing. This is
     * what decides whether §8's derived vocabulary can reach a slot at all.
     */
    val open: Boolean
        get() = when (this) {
            MEDIUM -> true
            LANDFORM, SUBSURFACE, DRESSING, SKY -> false
        }

    /**
     * Every preset for this slot that is written in Kotlin — the whole pool for a closed slot, and none
     * of it for an open one, whose pool is data (§8.2, and [co.voik.agesandtheart.age.word.Vocabulary]).
     *
     * **Ask the vocabulary rather than this** unless you specifically mean the authored ones: an open
     * slot answers `emptyList()` here and would silently resolve to nothing.
     *
     * A computed `when` rather than a stored list, and that is not incidental: each of these enums names
     * [Slot] in its own initialiser, so building the list eagerly here would have the two classes waiting
     * on each other. It is asked once per slot when a sentence is resolved, which is nothing.
     */
    val authored: List<SlotPreset>
        get() = when (this) {
            LANDFORM -> Landform.entries
            MEDIUM -> emptyList()
            SUBSURFACE -> Subsurface.entries
            DRESSING -> Dressing.entries
            SKY -> Sky.entries
        }

    /**
     * The preset this slot means by [key], or null where the key names nothing it can hold.
     *
     * The single place a key becomes a preset, so a recipe, a `preset_tags` file and `/age compose` can
     * never disagree about what one spells. An open slot accepts an id it has never heard of — that is
     * the whole point of being open — and complains later, where the missing content actually bites.
     */
    fun presetFor(key: String): SlotPreset? = when (this) {
        MEDIUM -> Medium.named(key)
        LANDFORM, SUBSURFACE, DRESSING, SKY -> authored.firstOrNull { it.key == key }
    }

    /**
     * Whether this slot is answered **per column** rather than once for the whole world — which is the
     * criterion for everything that follows (design §3.4).
     *
     * A positional slot can satisfy a contradiction *by coexistence*: both terms honoured, in different
     * places, so the world merely gets strange. A singular one has nowhere to put a second answer, and
     * that is where the harsher registers of instability earn their place. Only the sky is singular, and
     * for a technical reason rather than a preference — a sky is a dimension type, registered once for
     * the dimension, so two of them in one world is impossible rather than merely undesirable.
     *
     * It predicts the slots that do not exist yet: *contents* is positional, so it will be set-valued.
     */
    val positional: Boolean get() = this != SKY

    /**
     * How readily this slot takes on a *second* preset that the sentence merely happened to like as well —
     * the harmonious division, as opposed to the one that settles a contradiction (design §3.4).
     *
     * They differ per slot because coexistence is not equally easy to look at. **Landform is the reluctant
     * one**, and deliberately: two shapes in one world is the hardest coexistence to make read well, since
     * nothing interpolates between a floating island and a plain — they meet at a seam, and a world full of
     * seams reads as broken rather than varied. So an Age of two shapes stays a thing you remember.
     *
     * **Dressing used to be the most companionable of them (0.45) and is now the least**, which is Jonah's
     * correction of a real over-reach (design §3.4, "what regions are for"). The old justification was that
     * distributing several kinds of place across a map is what Minecraft's biomes already do — and that is
     * an argument for *biomes* doing it, borrowed to license the *slot* dividing instead. Dressing is the
     * one positional slot with a softer native mechanism underneath it, so variety belongs there and a
     * dressing seam is reserved for two policies that genuinely cannot share a climate table.
     *
     * Not zero, because that softer mechanism cannot express everything: `plasma` is a fixed alien biome
     * and `overworld` is vanilla's whole table, and no weighting reconciles those two.
     *
     * Zero for the sky, which cannot divide at all.
     */
    val appetiteForCompany: Double
        get() = when (this) {
            LANDFORM -> 0.12
            MEDIUM -> 0.18
            SUBSURFACE -> 0.25
            DRESSING -> 0.04
            SKY -> 0.0
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
     * ceremony over something that was never in dispute.
     */
    val kind: Kind = Kind.PREDICATIVE,
) {
    /** See [Parameter.kind]. */
    enum class Kind { PREDICATIVE, POPULATIVE }

    val default: String get() = options.first()

    constructor(name: String, vararg options: String) : this(name, options.toList())

    /**
     * Whether this parameter would understand [option].
     *
     * An open one takes any well-formed id — including one naming content this pack does not have, for the
     * same reason an open *slot* does (§3.1): a save moving between modpacks must keep saying what it said.
     */
    fun accepts(option: String): Boolean {
        val isOneOfTheNamedOptions = option in options
        val looksLikeARegistryId = namesReferent(option) && ResourceLocation.tryParse(option) != null
        return isOneOfTheNamedOptions || (open && looksLikeARegistryId)
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
         * A block a preset is made of — the palette's stone today, a landform's spires and a structure's
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
    }
}

/** A preset that fills a [slot], possibly offering a few [parameters] to steer it. */
interface SlotPreset : StringRepresentable {
    val key: String
    val slot: Slot
    val parameters: List<Parameter> get() = emptyList()

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
     * slot can honour it, the word is charged rather than dropped (§3.3).
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
