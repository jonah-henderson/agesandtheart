package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.Seam
import co.voik.agesandtheart.worldgen.CavernField
import co.voik.agesandtheart.worldgen.ErodedField
import co.voik.agesandtheart.worldgen.NoiseField
import co.voik.agesandtheart.worldgen.PillarField
import co.voik.agesandtheart.worldgen.PyramidField
import co.voik.agesandtheart.worldgen.ShapesField
import co.voik.agesandtheart.worldgen.SpireField
import co.voik.agesandtheart.worldgen.VerticalWindow
import co.voik.agesandtheart.worldgen.field.Palette
import co.voik.agesandtheart.worldgen.field.Raised
import co.voik.agesandtheart.worldgen.field.Substance
import co.voik.agesandtheart.worldgen.field.TerrainField
import net.minecraft.world.level.block.state.BlockState

/**
 * The shape of an Age's rock: a Tier-B field preset plus its [waterline], the one fact a composer needs
 * to place anything else against it. The [Sea] chooses only the substance.
 *
 * Every shape wanting a sea puts it at vanilla's 63 — a convention, not a rule, but a shared waterline
 * is what keeps a seam between two terrains from drowning half the world.
 */
enum class Terrain(
    override val key: String,
    val waterline: Int?,
    private val build: (String, Long) -> TerrainField,
) : AspectPreset {
    /** Floating islands over open air: lobed masses, talons and roots, weathered to ribs. */
    SPIRE_ISLANDS("spire_islands", waterline = 63, build = { _, salt -> SpireField.world(salt) }),

    /** Rolling noise hills breaking a sea — the closest thing here to ordinary ground. */
    HILLS("hills", waterline = 63, build = { _, salt -> NoiseField.hills(salt) }),

    /** Rock riddled by ridged 3D noise: this Age's caves *are* its shape, not something cut from it. */
    CAVERNS("caverns", waterline = 63, build = { _, salt -> CavernField.world(salt) }),

    /** Billowy noise weathered into mesa-like relief, hanging clear above the water. */
    ERODED("eroded", waterline = 63, build = { _, salt -> ErodedField.world(salt) }),

    /** Colossal rectangular monoliths on a jittered grid, standing a hundred blocks out of the sea. */
    PILLARS("pillars", waterline = 63, build = { _, salt -> PillarField.world(salt) }),

    /** Instanced pyramids on a plain — the one terrain with a real parameter, its [ARRANGEMENT]. */
    PYRAMIDS("pyramids", waterline = null, build = { arrangement, salt -> PyramidField.world(arrangement, salt) }),

    /** A walkable sampler of the shape vocabulary and its combinators — a reference, not a world. */
    SHAPES("shapes", waterline = null, build = { _, salt -> ShapesField.world(salt) }),
    ;

    override val aspect = Aspect.TERRAIN

    override val parameters: List<Parameter>
        get() = listOfNotNull(
            ARRANGEMENT.takeIf { this == PYRAMIDS },
            ALTITUDE.takeIf { this == SPIRE_ISLANDS },
            STONE,
            MINGLING,
            SEAM,
        )

    override fun getSerializedName(): String = key

    /**
     * The rock this terrain lays down, steered by whichever [options] it understands.
     *
     * Takes the Age's [window] because altitude is only offerable where there is room for it — see [lift],
     * and a [salt] because two territories of the *same* preset must not build the same rock.
     */
    fun field(options: Options, window: VerticalWindow, salt: Long): TerrainField {
        val shape = build(options.of(ARRANGEMENT), salt)
        val lift = lift(options, window)
        return if (lift == 0) shape else Raised(shape, lift)
    }

    /**
     * How far up the world this terrain sits — see [ALTITUDE].
     *
     * Conditioned on the [window], so a terrain in a band with no room simply sits where it always did:
     * the worst outcome of a mismatch is the old altitude, never a clipped world.
     */
    fun lift(options: Options, window: VerticalWindow): Int {
        val wantsHeight = options.of(ALTITUDE) == "high"
        val hasRoom = window == VerticalWindow.LIFTED
        return if (wantsHeight && hasRoom) HIGH_ALTITUDE_LIFT else 0
    }

    /** How widely this terrain's materials speckle — see [MINGLING]. */
    fun mingling(options: Options): Double =
        if (options.of(MINGLING) == "fine") Substance.FINE_MINGLING else Substance.PATCHY_MINGLING

    /**
     * Which form the faults along this Age's seams take — see [SEAM]. [drawn] is the Age's own character,
     * and the answer unless a recipe pinned one over it. A pin naming nothing this version understands
     * falls back to the draw, so a dropped token cannot silently flatten an Age's geology.
     */
    fun seamIn(options: Options, drawn: Seam): Seam {
        val pinned = options.of(SEAM)
        if (pinned == SEAM_AS_DRAWN) return drawn
        return Seam.named(pinned) ?: drawn
    }

    /**
     * What this terrain is made of — the blocks the fill lays over its own territory, or empty for plain
     * stone. The fill's business rather than a surface rule's, so it sits *under* vanilla's tree and
     * vanilla paints grass on top of whatever we laid (see [Substance]).
     *
     * Several mingle rather than divide (§3.2), and a material never says whether anything lives on it —
     * a copper spire keeps its grass.
     */
    fun substance(options: Options): List<BlockState> = Palette.materialsNamed(options.allOf(STONE))

    companion object {
        val ARRANGEMENT = Parameter("arrangement", "grid", "rings", "varied")

        /** The one material parameter — the whole of what a writer means by "the land is andesite". */
        val STONE = Parameter.material("stone")

        /**
         * How finely several materials speckle together. `fine` brings a patch down to a block or two, for
         * a mixture reading as one mottled rock rather than blotches of two. No word reaches it yet; it
         * exists to be pinned by a recipe.
         */
        val MINGLING = Parameter("mingling", "patches", "fine")

        /**
         * How high up the world an archipelago floats — offered by [SPIRE_ISLANDS] alone, the one terrain
         * hanging in open air. No word reaches it yet; it exists to be pinned by a recipe.
         */
        val ALTITUDE = Parameter("altitude", "low", "high")

        /**
         * Which form the faults along this Age's seams take, overriding what its character drew (§3.4).
         * Exists so all four forms are walkable — an Age's seam is otherwise invisible until chance
         * produces one. `sheared` asks for no fault at all.
         *
         * Age-wide despite living on a per-territory aspect, and read from the first territory like
         * `Sea.DEPTH`: a seam belongs to the boundary rather than to either side, so "riven here and whole
         * there" is not something it could mean.
         */
        val SEAM = Parameter("seam", SEAM_AS_DRAWN, "sheared", "scarp", "rift", "fuzzed")

        /** What [SEAM] reads as when nobody overrode the draw: whatever the Age's character carries. */
        const val SEAM_AS_DRAWN = "drawn"

        /**
         * How far a scarp throws each side of a seam, in blocks — a 64-block cliff where two territories
         * are thrown opposite ways, against terrains standing between about y=63 and y=185.
         *
         * A guess, not a measurement. `./gradlew :common:preview --args=fault` draws it without a server.
         */
        const val SCARP_THROW = 32

        /**
         * The floor a rift cuts down to — about twenty blocks under the sea at 63, so a rift is swimmable
         * and divides an Age without partitioning it. Not the world's floor: a chasm to bedrock along
         * every seam would sever the territories outright.
         */
        const val RIFT_FLOOR = 40

        /**
         * Where a rift stops cutting. **Above the waterline on purpose**: a rift no longer floods by
         * construction, so a sea reaches one only where it actually cuts a coast.
         */
        const val RIFT_RIM = 72

        /**
         * Deep enough to be under any ground the wall crosses. Founded at the surface it floats over
         * every dip, and the gap is only visible in profile.
         */
        const val WALL_FOOTING = 30
        const val WALL_CREST = 108

        /**
         * How far `altitude=high` lifts an archipelago. Measured: the island tops' ninetieth percentile
         * was 175, so this puts it eighteen blocks under the upper cloud deck at 265, with the central
         * spires reaching about 374 — under [VerticalWindow.LIFTED]'s ceiling of 383, which is why it is
         * not larger.
         */
        const val HIGH_ALTITUDE_LIFT = 72
    }
}
