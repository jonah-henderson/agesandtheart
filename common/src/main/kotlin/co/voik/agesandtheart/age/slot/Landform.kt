package co.voik.agesandtheart.age.slot

import co.voik.agesandtheart.worldgen.CavernField
import co.voik.agesandtheart.worldgen.ErodedField
import co.voik.agesandtheart.worldgen.NoiseField
import co.voik.agesandtheart.worldgen.PillarField
import co.voik.agesandtheart.worldgen.PyramidField
import co.voik.agesandtheart.worldgen.ShapesField
import co.voik.agesandtheart.worldgen.SpireField
import co.voik.agesandtheart.worldgen.field.Palette
import co.voik.agesandtheart.worldgen.field.TerrainField
import net.minecraft.world.level.levelgen.SurfaceRules

/**
 * The shape of an Age's rock — the slot that carries the most meaning, and the one whose family of
 * siblings is the main content workload of the whole design (§3.1).
 *
 * Each entry bundles a pile of tuned constants behind one name, which is exactly what the Tier-B preset
 * objects in `worldgen/` already did. This promotes that existing layer rather than inventing one: a
 * landform *is* `SpireField.world()` plus the one fact a composer needs to place anything else against
 * it — its [waterline].
 *
 * A shape knows where its own sea belongs, and the [Medium] chooses only the *substance* — which is what
 * lets "Spire islands over water rather than plasma" be an ordinary sentence instead of a special case.
 *
 * Every shape that wants a sea now puts it at vanilla's **63**. That is a convention rather than a rule:
 * each landform was retuned so its own relief sits correctly around that height, precisely so that a
 * future shape wanting its sea somewhere odd can simply say so. Standardising matters because two
 * landforms can now share one Age, and a shared waterline is what keeps a seam between them from
 * drowning half the world.
 */
enum class Landform(
    override val key: String,
    val waterline: Int?,
    private val build: (String) -> TerrainField,
) : SlotPreset {
    /** Floating islands over open air: lobed masses, talons and roots, weathered to ribs. */
    SPIRE_ISLANDS("spire_islands", waterline = 63, build = { SpireField.world() }),

    /** Rolling noise hills breaking a sea — the closest thing here to ordinary ground. */
    HILLS("hills", waterline = 63, build = { NoiseField.hills() }),

    /** Rock riddled by ridged 3D noise: this Age's caves *are* its shape, not something cut from it. */
    CAVERNS("caverns", waterline = 63, build = { CavernField.world() }),

    /** Billowy noise weathered into mesa-like relief, hanging clear above the water. */
    ERODED("eroded", waterline = 63, build = { ErodedField.world() }),

    /** Colossal rectangular monoliths on a jittered grid, standing a hundred blocks out of the sea. */
    PILLARS("pillars", waterline = 63, build = { PillarField.world() }),

    /**
     * Instanced pyramids on a plain. The one landform with a real parameter: the same shapes arranged
     * three ways, which used to be three separate presets and reads far better as one preset asked a
     * question.
     */
    PYRAMIDS("pyramids", waterline = null, build = { arrangement -> PyramidField.world(arrangement) }),

    /** A walkable sampler of the shape vocabulary and its combinators — a reference, not a world. */
    SHAPES("shapes", waterline = null, build = { ShapesField.world() }),
    ;

    override val slot = Slot.LANDFORM

    override val parameters: List<Parameter>
        get() = listOfNotNull(ARRANGEMENT.takeIf { this == PYRAMIDS }, STONE)

    override fun getSerializedName(): String = key

    /** The rock this landform lays down, steered by whichever [options] it understands. */
    fun field(options: Options): TerrainField = build(options.of(ARRANGEMENT))

    /**
     * What this landform is *made of*, as a surface rule painted over its own territory — or
     * [Palette.NOTHING] where the sentence never said, leaving the rock to the dressing.
     *
     * Sits **between the dressing's cover and the dressing's rock** ([Palette.verdantOver]), which is what
     * makes "copper spires and andesite hills" behave the way a writer means it: the spires are copper where
     * they stand, and they still take grass on top in a world that grows things. A material says what the
     * substance is, never whether anything lives on it.
     */
    fun stone(options: Options): SurfaceRules.RuleSource {
        val stones = Palette.materialsNamed(options.allOf(STONE))
        return if (stones.isEmpty()) Palette.NOTHING else Palette.mingled(stones)
    }

    companion object {
        val ARRANGEMENT = Parameter("arrangement", "grid", "rings", "varied")

        /**
         * Deliberately the same name as [Dressing.STONE], so that **one word reaches both**.
         *
         * A writer does not distinguish the rock a landform is made of from the rock a dressing paints, and
         * should not have to (design §4.3.1): "the land is andesite" is one thought. Two parameter names
         * would force every material word to declare both and would leak our internal seam into the
         * vocabulary. Which of them a word actually steers is decided by where it is aimed, not by spelling.
         */
        val STONE = Parameter.material("stone")
    }
}
