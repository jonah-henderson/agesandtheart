package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.Seam
import co.voik.agesandtheart.worldgen.AlpsField
import co.voik.agesandtheart.worldgen.CanyonField
import co.voik.agesandtheart.worldgen.CanyonlandsField
import co.voik.agesandtheart.worldgen.CavernField
import co.voik.agesandtheart.worldgen.CliffField
import co.voik.agesandtheart.worldgen.ErodedField
import co.voik.agesandtheart.worldgen.IslandsField
import co.voik.agesandtheart.worldgen.NoiseField
import co.voik.agesandtheart.worldgen.PillarField
import co.voik.agesandtheart.worldgen.PyramidField
import co.voik.agesandtheart.worldgen.RiverlandsField
import co.voik.agesandtheart.worldgen.ShapesField
import co.voik.agesandtheart.worldgen.ShatteredField
import co.voik.agesandtheart.worldgen.SpireField
import co.voik.agesandtheart.worldgen.VerticalWindow
import co.voik.agesandtheart.worldgen.biome.Elevation
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
    private val build: (Options, Long) -> TerrainField,
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

    /** Instanced pyramids on a plain, in the [ARRANGEMENT] asked for. */
    PYRAMIDS(
        "pyramids",
        waterline = null,
        build = { options, salt -> PyramidField.world(options.of(ARRANGEMENT), salt) },
    ),

    /**
     * Rock to the height limit with one canyon cut through it, on the [BEARING] asked for — the one
     * terrain where the world is what was taken away. Its waterline is the river at the bottom of the
     * gorge, not a sea: there is no open ground for a sea to stand on.
     */
    CANYON(
        "canyon",
        waterline = CanyonField.RIVER_LEVEL,
        build = { options, salt -> CanyonField.world(options.of(BEARING), salt) },
    ),

    /**
     * A world cut in two on the [BEARING] asked for: ocean one way, plateau the other, one cliff between.
     */
    CLIFFS(
        "cliffs",
        waterline = CliffField.SEA_LEVEL,
        build = { options, salt -> CliffField.world(options.of(BEARING), salt) },
    ),

    /** Mesa country: a tableland under open sky, cut to pieces by canyons running three ways at once. */
    CANYONLANDS(
        "canyonlands",
        waterline = CanyonlandsField.RIVER_LEVEL,
        build = { _, salt -> CanyonlandsField.world(salt) },
    ),

    /**
     * The same table cracked into cells, a gorge down every join — no trunk, no tributary, no downhill.
     * The one landform here that could not have been made by water, and kept for exactly that.
     */
    SHATTERED(
        "shattered",
        waterline = ShatteredField.RIVER_LEVEL,
        build = { _, salt -> ShatteredField.world(salt) },
    ),

    /**
     * Rolling upland carved by a river system — headwaters branching down into trunks, with the trunks
     * running wet and the headwaters dry. The one landform here with a drainage *hierarchy*.
     */
    RIVERLANDS(
        "riverlands",
        waterline = RiverlandsField.WATERLINE,
        build = { _, salt -> RiverlandsField.world(salt) },
    ),

    /**
     * Islands in an endless sea, at the [EXTENT] asked for — one where the writer arrives and the rest a
     * voyage away. Deliberately never a continent; see `Isle` for what makes that a property rather than
     * a tuning.
     */
    ISLANDS(
        "islands",
        waterline = IslandsField.SEA_LEVEL,
        build = { options, salt -> IslandsField.world(options.of(EXTENT), salt) },
    ),

    /**
     * An alpine range at about one to sixteen: a foreland plain, foothills, and a glaciated crest. The one
     * landform here whose surface is built **up from its own drainage** rather than cut into a given one —
     * every ridge is where two hillslopes met.
     */
    ALPS(
        "alps",
        waterline = AlpsField.WATERLINE,
        build = { _, salt -> AlpsField.world(salt) },
    ),

    /** A walkable sampler of the shape vocabulary and its combinators — a reference, not a world. */
    SHAPES("shapes", waterline = null, build = { _, salt -> ShapesField.world(salt) }),
    ;

    override val aspect = Aspect.TERRAIN

    override val parameters: List<Parameter>
        get() = listOfNotNull(
            ARRANGEMENT.takeIf { this == PYRAMIDS },
            BEARING.takeIf { this == CANYON || this == CLIFFS },
            EXTENT.takeIf { this == ISLANDS },
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
        val shape = build(options, salt)
        val lift = lift(options, window)
        return if (lift == 0) shape else Raised(shape, lift)
    }

    /**
     * Water this terrain carries **itself**, or null where a waterline is all it needs.
     *
     * A river system's water follows its own beds, which run downhill everywhere, so no single level can
     * pour it — see `SeaFill.wet`. Raised with the shape for the same reason the shape is raised at all.
     */
    fun standingWater(options: Options, window: VerticalWindow, salt: Long): TerrainField? {
        // Most terrains carry none, and a new one should not have to say so.
        val water = when (this) {
            RIVERLANDS -> RiverlandsField.water(salt)
            ALPS -> AlpsField.water(salt)
            else -> null
        } ?: return null
        val lift = lift(options, window)
        return if (lift == 0) water else Raised(water, lift)
    }

    /**
     * Whether this terrain's coast is meant to be sand the whole way round — see
     * [co.voik.agesandtheart.worldgen.biome.Grounding.hasSandyShores]. Only read by a grounded Age, and
     * only where its ground meets its sea.
     */
    fun hasSandyShores(): Boolean = when (this) {
        ISLANDS -> true
        else -> false
    }

    /**
     * Whether the Age's waterline is this terrain's **river** rather than a sea — see
     * [co.voik.agesandtheart.worldgen.biome.Grounding.waterlineIsRiver].
     *
     * [CANYON] is the case it exists for and may stay the only one: its water is poured by a flat level
     * like any sea's, but there is no open ground for a sea to be, so the level only ever shows along the
     * bottom of the gorge.
     */
    fun waterlineIsRiver(): Boolean = when (this) {
        CANYON -> true
        else -> false
    }

    /**
     * What this terrain's *height* says about what grows on it, or null where it has no relief to speak of
     * — see [co.voik.agesandtheart.worldgen.biome.Elevation]. A landform's own declaration, like
     * [hasSandyShores], because only the landform knows where its floor and its crest are.
     */
    fun elevation(): Elevation? = when (this) {
        // Measured from the basin's *shoulder* rather than its floor: the floor is the bottom of a hollow
        // in the middle of a cell, so datuming there chills the whole country by the depth of its lowest
        // hole and the basins come out snowy. The shoulder is where the plains actually sit.
        ALPS -> Elevation(fromY = AlpsField.PLAIN_Y, toY = ALPINE_CREST_Y)
        else -> null
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

        /**
         * Which way a canyon runs. Words rather than an angle, both because §3.2 keeps numbers away from a
         * writer and because a canyon on an arbitrary bearing is a thing only a composed field tree should
         * be able to ask for.
         */
        val BEARING = Parameter("bearing", "north_south", "east_west", "diagonal")

        /**
         * How big an island is. Words rather than a distance, §3.2 keeping numbers away from a writer —
         * and the largest is deliberately short of anywhere you could lose a coastline on.
         */
        val EXTENT = Parameter(
            "extent",
            IslandsField.Extent.MODEST.key,
            IslandsField.Extent.BROAD.key,
            IslandsField.Extent.VAST.key,
        )

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

        /**
         * The height a column in [ALPS] reads as fully a summit at — around the crest rather than above the
         * tallest massif, so the peak biomes reach the whole crest line and not only its exceptions.
         */
        private const val ALPINE_CREST_Y = 262
    }
}
