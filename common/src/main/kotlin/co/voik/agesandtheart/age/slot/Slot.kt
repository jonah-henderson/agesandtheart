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
