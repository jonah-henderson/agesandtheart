package co.voik.agesandtheart.age.slot

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
     * Every preset that could fill this slot.
     *
     * A computed `when` rather than a stored list, and that is not incidental: each of these enums names
     * [Slot] in its own initialiser, so building the list eagerly here would have the two classes waiting
     * on each other. It is asked once per slot when a sentence is resolved, which is nothing.
     */
    val presets: List<SlotPreset>
        get() = when (this) {
            LANDFORM -> Landform.entries
            MEDIUM -> Medium.entries
            SUBSURFACE -> Subsurface.entries
            DRESSING -> Dressing.entries
            SKY -> Sky.entries
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
     * They differ per slot because coexistence is not equally easy to look at. **Dressing is the natural
     * home of it**: distributing several kinds of place across a map is precisely what Minecraft's biomes
     * already do, so a beach beside a field of flowers reads as richness. **Landform is the reluctant one**,
     * and deliberately: two shapes in one world is the hardest coexistence to make read well, since nothing
     * interpolates between a floating island and a plain — they meet at a seam, and a world full of seams
     * reads as broken rather than varied. So an Age of two shapes stays a thing you remember.
     *
     * Zero for the sky, which cannot divide at all.
     */
    val appetiteForCompany: Double
        get() = when (this) {
            LANDFORM -> 0.12
            MEDIUM -> 0.18
            SUBSURFACE -> 0.25
            DRESSING -> 0.45
            SKY -> 0.0
        }

    override fun getSerializedName(): String = key
}

/**
 * One enumerated choice a preset offers — "sparse / scattered / crowded", never a 0–1 slider.
 *
 * Discreteness is deliberate (design §3.2): it is how writing a symbol should feel — you write a word,
 * not a number — it matches the call already made for instancing, where yaw and scale are finite sets,
 * and it makes cost and mastery gating trivial to express later. We interpret the options liberally at
 * our end; the writer only ever picks one of a handful.
 *
 * The first option is the default, so a preset named with no options at all still resolves.
 */
data class Parameter(val name: String, val options: List<String>) {
    val default: String get() = options.first()

    constructor(name: String, vararg options: String) : this(name, options.toList())

    fun accepts(option: String): Boolean = option in options
}

/** A preset that fills a [slot], possibly offering a few [parameters] to steer it. */
interface SlotPreset : StringRepresentable {
    val key: String
    val slot: Slot
    val parameters: List<Parameter> get() = emptyList()

    override fun getSerializedName(): String
}
