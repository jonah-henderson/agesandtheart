package co.voik.agesandtheart.age.slot

import co.voik.agesandtheart.worldgen.CavernField
import co.voik.agesandtheart.worldgen.ErodedField
import co.voik.agesandtheart.worldgen.NoiseField
import co.voik.agesandtheart.worldgen.PillarField
import co.voik.agesandtheart.worldgen.PyramidField
import co.voik.agesandtheart.worldgen.ShapesField
import co.voik.agesandtheart.worldgen.SpireField
import co.voik.agesandtheart.worldgen.field.TerrainField

/**
 * The shape of an Age's rock — the slot that carries the most meaning, and the one whose family of
 * siblings is the main content workload of the whole design (§3.1).
 *
 * Each entry bundles a pile of tuned constants behind one name, which is exactly what the Tier-B preset
 * objects in `worldgen/` already did. This promotes that existing layer rather than inventing one: a
 * landform *is* `SpireField.world()` plus the two facts a composer needs to place anything else against
 * it.
 *
 * Those two facts are [waterline] and [floor]. A shape knows where its own sea belongs — Spire's
 * islands float above y=0, the pillars stand out of an ocean at y=-40, the hills break a surface at
 * y=63 — and no independent medium slot could guess it. So the landform declares the height and the
 * [Medium] chooses only the *substance*, which is what lets "Spire islands over water rather than
 * plasma" be an ordinary sentence instead of a special case.
 */
enum class Landform(
    override val key: String,
    val waterline: Int,
    private val build: (String) -> TerrainField,
) : SlotPreset {
    /** Floating islands over open air: lobed masses, talons and roots, weathered to ribs. */
    SPIRE_ISLANDS("spire_islands", waterline = 0, build = { SpireField.world() }),

    /** Rolling noise hills breaking a sea — the closest thing here to ordinary ground. */
    HILLS("hills", waterline = 63, build = { NoiseField.hills() }),

    /** Rock riddled by ridged 3D noise: this Age's caves *are* its shape, not something cut from it. */
    CAVERNS("caverns", waterline = 63, build = { CavernField.world() }),

    /** Billowy noise weathered into mesa-like relief, standing out of a low sea. */
    ERODED("eroded", waterline = -30, build = { ErodedField.world() }),

    /** Colossal rectangular monoliths on a jittered grid, over a deep ocean. */
    PILLARS("pillars", waterline = -40, build = { PillarField.world() }),

    /**
     * Instanced pyramids on a plain. The one landform with a real parameter: the same shapes arranged
     * three ways, which used to be three separate presets and reads far better as one preset asked a
     * question.
     */
    PYRAMIDS("pyramids", waterline = NO_SEA, build = { arrangement -> PyramidField.world(arrangement) }),

    /** A walkable sampler of the shape vocabulary and its combinators — a reference, not a world. */
    SHAPES("shapes", waterline = NO_SEA, build = { ShapesField.world() }),
    ;

    override val slot = Slot.LANDFORM

    override val parameters: List<Parameter>
        get() = if (this == PYRAMIDS) listOf(ARRANGEMENT) else emptyList()

    override fun getSerializedName(): String = key

    /** The rock this landform lays down, steered by whichever [options] it understands. */
    fun field(options: Options): TerrainField = build(options.of(ARRANGEMENT))

    /** Whether this shape expects a sea at all, or stands in open air. */
    val wantsMedium: Boolean get() = waterline != NO_SEA

    companion object {
        val ARRANGEMENT = Parameter("arrangement", "grid", "rings", "varied")
    }
}

/**
 * A landform that stands in open air; [Medium] fills nothing regardless of what is named.
 *
 * Top-level rather than in the companion because the enum constants read it in their own constructor
 * arguments, and a companion is initialised *after* the constants it belongs to.
 */
private const val NO_SEA = Int.MIN_VALUE
