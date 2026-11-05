package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.age.language.Speech
import co.voik.agesandtheart.worldgen.Chambers
import co.voik.agesandtheart.worldgen.FissuresField
import co.voik.agesandtheart.worldgen.GreatHalls
import co.voik.agesandtheart.worldgen.LatticeField
import co.voik.agesandtheart.worldgen.SizeScale
import co.voik.agesandtheart.worldgen.TunnelsField
import co.voik.agesandtheart.worldgen.VerticalWindow
import co.voik.agesandtheart.worldgen.field.Caved
import co.voik.agesandtheart.worldgen.field.HollowedOut
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.worldgen.field.TerrainField
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * What is **built into the rock** beneath the surface — as against [Carvers], which is what has been cut
 * back out of it afterwards.
 *
 * The two are one stage of Minecraft's own generation each, and vanilla runs both. These are *fields*:
 * they are composed into the shape before a block is placed, which is what lets one of them declare
 * itself dry, claim a band that counts as indoors, and be part of the landform rather than an erosion of
 * it. A carver can do none of those — it subtracts a configured shape from rock that already exists.
 *
 * **Only a landform with vertical room carries one** ([Terrain.undergroundCeiling]). One that has none
 * ignores whatever was asked for, the way a preset ignores a material it cannot be made of.
 */
enum class Underground(override val key: String) : AuthoredPreset {

    /** Nothing cut into it: whatever the shape laid down stays there, all the way to the bedrock. */
    NONE("none"),

    /**
     * **Minecraft's own noise caves** — the cheese chambers, the spaghetti tunnels, the entrances that
     * open onto a hillside. Vanilla's density-function cave stack, transcribed in
     * [co.voik.agesandtheart.worldgen.field.Caved], and the ordinary answer for a world with rock under it.
     */
    NOISE_CAVES("noise_caves"),

    /**
     * **A network of even, winding tubes** from one ridged noise — [co.voik.agesandtheart.worldgen.TunnelsField].
     * Nothing like vanilla's chambers and spaghetti, and kept under the ground: it has no entrances, so
     * the way in is a carver, a cliff or a pick.
     */
    TUNNELS("tunnels"),

    /**
     * **Square-cut tunnels on a regular grid**, along X and Z on every storey with shafts standing where
     * they cross — [co.voik.agesandtheart.worldgen.LatticeField]. The made counterpart to [TUNNELS]: the
     * same wet-or-dry rock, but every passage ruled straight and the same distance from the next.
     */
    LATTICE("lattice"),

    /**
     * **No rock at all** between the floor and a crust under the surface: the world is a shell over one
     * void — [co.voik.agesandtheart.worldgen.field.HollowedOut]. Unlike every other underground it reaches
     * up to the surface rather than stopping at the ceiling, which is the point of it. Never wet.
     */
    HOLLOW("hollow"),

    /**
     * Storey upon storey of pillared hall.
     *
     * A value here rather than a landform of its own, so that what stands *over* the halls can be any
     * world at all. Never wet, and the one underground that counts as indoors.
     */
    GREAT_HALLS("great_halls"),

    /**
     * Great vaults with a lake in the bottom of each — the counterpart to [GREAT_HALLS], one being a thing
     * somebody made and this a thing that happened.
     *
     * **The only underground that carries its own water**, and it has to: every landform's underground
     * ceiling sits under its waterline, so a chamber filled by the Age's sea is a drowned one everywhere.
     * The lake is the chambers' own level rather than the world's, which is what lets this be written
     * beneath any shape with room for it. See [co.voik.agesandtheart.worldgen.Chambers].
     */
    CHAMBERED("chambered"),

    /**
     * **Tall, narrow fissures** crossing at every heading, each closing to a point above and below —
     * [co.voik.agesandtheart.worldgen.FissuresField]. The chambers turned on end: as much open rock, and
     * nowhere wide enough to build on. Wet or dry by the water table, as a tunnel is.
     */
    FISSURED("fissured"),

    ;

    override val aspect = Aspect.UNDERGROUND

    /**
     * **A vault is a thing with a name**, and so are a tunnel, a lattice and a fissure, so the pages that mean them are minted here
     * the way a landform's is — `chambered underground` rather than a hopeful pile of adjectives. The other
     * three stay unnamed and are reached by what they are like, there being nothing to a hollow rock but
     * its quality.
     */
    override val writtenWordFor: String? get() = key.takeIf { this in SIZED }

    /** `tunnels` and `lattice` are things a book's depths hold; the other minted pages say what they are like. */
    override val writtenSpeech: Speech? get() = Speech.PLURAL.takeIf { this in SAID_AS_THINGS }

    /**
     * How big a chamber or a tunnel is — the axis every other size in the language is said on, so
     * `large chambered underground` is the vault a city fits in and nothing below it is.
     */
    override val parameters: List<Parameter>
        get() = listOfNotNull(Terrain.SIZE.takeIf { this in SIZED }, DENSITY.takeIf { this in SIZED })

    /**
     * This underground cut into [uncut], between [floor] and [ceiling].
     *
     * [ceiling] is non-null because only a landform with room under it carries an underground at all, and
     * the caller has already established that.
     */
    fun carve(
        uncut: TerrainField,
        floor: Int,
        ceiling: Int,
        window: VerticalWindow,
        options: Options,
        salt: Long,
        /** Whether noise caves are read on vanilla's grid; see [Caved.gridded]. */
        griddedCaves: Boolean = false,
    ): Terrain.Ground {
        val held = heldBetween(floor, ceiling, options, salt)
        return carveBetween(uncut, held.first, held.last, ceiling, window, options, salt, griddedCaves)
    }

    private fun carveBetween(
        uncut: TerrainField,
        floor: Int,
        ceiling: Int,
        landformCeiling: Int,
        window: VerticalWindow,
        options: Options,
        salt: Long,
        griddedCaves: Boolean,
    ): Terrain.Ground = when (this) {
        NOISE_CAVES -> {
            // Noise caves run to the surface by their own entrance rule, unless a depth held them down.
            val top = if (ceiling < landformCeiling) ceiling else window.topY
            Terrain.Ground(
                Caved.of(uncut, CAVE_SEED xor salt, floor, top, gridded = griddedCaves),
                // A carved cave meets the water table on its way out of the rock, so it answers to one.
                hollows = uncut,
            )
        }
        // Cut between the landform's own floor and ceiling, so the tubes stay under whatever stands over
        // them; wet or dry by the water table, as a noise cave is.
        TUNNELS -> {
            val scale = SizeScale.factorAt(options.steer(Terrain.SIZE, salt))
            Terrain.Ground(
                Subtract(uncut, TunnelsField.tubes(floor, ceiling, TUNNEL_SEED xor salt, scale, densityOf(options, salt))),
                hollows = uncut,
            )
        }
        // Laid like the tunnels, between the landform's floor and ceiling and answering to its water table.
        LATTICE -> {
            val scale = SizeScale.factorAt(options.steer(Terrain.SIZE, salt))
            Terrain.Ground(
                Subtract(uncut, LatticeField.passages(floor, ceiling, LATTICE_SEED xor salt, scale, densityOf(options, salt))),
                hollows = uncut,
            )
        }
        HOLLOW -> {
            val shell = HollowedOut(uncut, CRUST_THICKNESS, floor)
            Terrain.Ground(shell, dry = Subtract(uncut, shell))
        }
        GREAT_HALLS -> {
            // A ceiling has to be named here, unlike [NOISE_CAVES] where the band is the whole world —
            // `Caved` only ever walks rock the base actually has and its own entrance rule keeps the cut
            // away from the surface, so naming a ceiling there would be a second, worse copy of a decision
            // the node already makes better. A slab of halls has no such rule and would happily open onto
            // a hillside.
            val halls = GreatHalls.voidBetween(floor, ceiling, HALL_SEED xor salt)
            Terrain.Ground(Subtract(uncut, halls), dry = halls)
        }
        // **Dry *and* wet**, which is not a contradiction: the vaults are kept out of the Age's own
        // flat fill outright, and the lake standing in each is put back by the field that knows where
        // its own water line is. Handing them to a water table instead would stand a flooded bay
        // against a dry one with nothing between, which is what `GreatHallsWaterCheck` records.
        CHAMBERED -> {
            val size = options.steer(Terrain.SIZE, salt)
            val density = densityOf(options, salt)
            val vaults = Chambers.voidBetween(floor, ceiling, size, CHAMBER_SEED xor salt, density)
            Terrain.Ground(
                Subtract(uncut, vaults),
                dry = vaults,
                wet = Chambers.lakesIn(floor, ceiling, size, CHAMBER_SEED xor salt, density),
            )
        }
        FISSURED -> Terrain.Ground(
            Subtract(
                uncut,
                FissuresField.openings(floor, ceiling, options.steer(Terrain.SIZE, salt), FISSURE_SEED xor salt, densityOf(options, salt)),
            ),
            hollows = uncut,
        )
        NONE -> Terrain.Ground(uncut)
    }

    /**
     * The band this underground is **indoors** in, or null where it claims none — see
     * [co.voik.agesandtheart.worldgen.biome.BiomeBand].
     *
     * Only [GREAT_HALLS] claims one. Noise caves are not indoors in this sense: they are open to the
     * surface by design, they belong to the country they were cut into, and vanilla's own cave biomes
     * describe them exactly.
     *
     * **[CHAMBERED] is enclosed and still does not claim one**, which is a decision rather than an omission
     * (Jonah, 2026-09-08). A band here overrides the climate table with one fixed biome, and what a vault
     * wants is the opposite: the cave biomes are what carry the lush growth the algae rides on, and pinning
     * every chamber to a hall's biome would take its features and its mob list with it. A hall is
     * somebody's architecture and reads as one room however far it runs; a chamber is a place, and places
     * are what biomes are for.
     */
    fun indoorBand(floor: Int, ceiling: Int, options: Options = Options.NONE, salt: Long = 0L): IntRange? =
        if (this == GREAT_HALLS) heldBetween(floor, ceiling, options, salt) else null

    override fun getSerializedName(): String = key

    companion object {
        /** Where in the rock an underground lies: `deep underground` is caves held deeper, never a lower floor. */
        val DEPTH = Parameter.ranged(
            "depth",
            help = "Where in the rock the underground lies: held up under the surface, through all of it, or held deep.",
            landmarks = listOf(
                Parameter.Landmark(-1.0, "held to the top two-fifths of the rock"),
                Parameter.Landmark(0.0, "through the whole of it"),
                Parameter.Landmark(1.0, "held to the bottom two-fifths"),
            ),
        )

        /** How many tunnels, passages, fissures or chambers there are. */
        val DENSITY = Parameter.ranged(
            "density",
            help = "How many tunnels, passages, fissures or chambers the rock holds.",
            landmarks = listOf(
                Parameter.Landmark(-1.0, "a quarter as many"),
                Parameter.Landmark(-0.5, "half as many"),
                Parameter.Landmark(0.0, "as many as ever"),
                Parameter.Landmark(0.5, "twice as many"),
                Parameter.Landmark(1.0, "four times as many"),
            ),
        )

        /** The part of [floor]..[ceiling] an underground is held to by its [DEPTH]. */
        internal fun heldBetween(floor: Int, ceiling: Int, options: Options, salt: Long): IntRange {
            val depth = options.steer(DEPTH, salt) ?: return floor..ceiling
            val band = ceiling - floor
            val kept = (band * (1.0 - MOST_GIVEN_UP * abs(depth))).roundToInt().coerceAtLeast(minOf(band, LEAST_HELD))
            return if (depth > 0.0) floor..(floor + kept) else (ceiling - kept)..ceiling
        }

        /** Where [multiple] times the ordinary count stands on [DENSITY]. */
        fun densityAt(multiple: Double): Double = (log2(multiple) / log2(DENSEST)).coerceIn(-1.0, 1.0)

        /** How many times the ordinary count [DENSITY] asks for. */
        internal fun densityOf(options: Options, salt: Long): Double =
            DENSEST.pow(options.steer(DENSITY, salt) ?: 0.0)

        // At the end of the axis, three-fifths of the band is solid rock.
        private const val MOST_GIVEN_UP = 0.6

        // So a held chamber or hall still has a storey's room.
        private const val LEAST_HELD = 48

        private const val DENSEST = 4.0

        /** The undergrounds that take a size and a page minted for them. `hollow`'s page is authored. */
        private val SIZED = setOf(CHAMBERED, TUNNELS, LATTICE, FISSURED)
        private val SAID_AS_THINGS = setOf(TUNNELS, LATTICE)

        // Deep enough to hold a surface's dirt and a tree's roots over the void.
        private const val CRUST_THICKNESS = 8

        // So an Age's caves are its own, and decorrelated from the rock they are cut into.
        private const val CAVE_SEED = 0xCA_7E5L

        // And its tunnels, so the tubes are not laid along the noise caves.
        private const val TUNNEL_SEED = 0x7_0BE5L

        // And its fissures, so they are not laid where anything else was.
        private const val FISSURE_SEED = 0xF1_55EDL

        // And its lattice, so the grid is not anchored off the same numbers as anything else.
        private const val LATTICE_SEED = 0x1A_771CEL

        // And its halls likewise, decorrelated from both.
        private const val HALL_SEED = 0x4A_115L

        // And its chambers, so a world's vaults are not laid where its caves were.
        private const val CHAMBER_SEED = 0x0C_4A_9BEL
    }
}
