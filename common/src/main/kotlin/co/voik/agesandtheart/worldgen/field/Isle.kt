package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.StringRepresentable
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Islands in an open sea — **a bounded thing with a shoreline**, which is the whole of what keeps this from
 * being a continent.
 *
 * Vanilla makes land by thresholding a continentalness field, so its coastlines are wherever the noise
 * happens to cross a level: the result has straits, inland seas, peninsulas that are nearly islands and
 * islands that are nearly peninsulas, and no answer to "how big is it". Doing the same here would reinvent
 * that, at best differently tuned.
 *
 * So an island here is an **object**. It has a centre, a [shoreRadius] that says where its coast is, and a
 * profile rising from that coast to its middle. Everything past the shore falls away to the seabed and
 * stays there. The consequences are the point:
 *
 * - the sea is genuinely endless, because land only exists within a radius of somewhere;
 * - an island has a size, so a writer can ask for a small one;
 * - and there is no such thing as a strait, an isthmus or an inland sea, because none of them are
 *   expressible — where vanilla's are unavoidable.
 *
 * What keeps it from reading as a dome is that the shoreline is **noisy** — the radius is perturbed per
 * column, so the coast has bays and headlands — and the interior relief is scaled by the same profile, so
 * the middle is hilly and the beach is flat.
 */
data class Isle(
    /** The bedrock everything stands on. */
    val floorY: Int,
    /** The sea floor between the islands. */
    val seabedY: Int,
    /** Where the shoreline sits — the Age's own waterline, or an island has no beach. */
    val shoreY: Int,
    /** How far the middle of an island stands over its shore. */
    val peakRise: Double,
    /** The mean distance from an island's centre to its coast. **The size parameter.** */
    val shoreRadius: Double,
    /** What share of [shoreRadius] one island differs from the next by. */
    val radiusVariation: Double,
    /** How far apart the islands lie. Large: an open sea is most of this world. */
    val spacing: Double,
    /** How far an island stands off its lattice point, as a share of [spacing]. */
    val jitter: Double,
    val seed: Long,
    /** How far the coast wanders in and out, as a share of the radius — bays and headlands. */
    val coastRoughness: Double = DEFAULT_COAST_ROUGHNESS,
    /**
     * How far the interior rolls above and below its profile. Nothing at the shore, all of it inland.
     *
     * **It is allowed to reach the water, and that is deliberate**: an interior that dips under the shore
     * is a lagoon or a flooded valley, which is a thing worth finding on an island. What it must not do is
     * sever one, and what stops it is the wavelength rather than the amplitude — see [reliefStretch].
     */
    val relief: Double = DEFAULT_RELIEF,
    /** How steeply the ground falls away outside the shore, in blocks per block. */
    val shelfSlope: Double = DEFAULT_SHELF_SLOPE,
    /**
     * What share of the radius lies **flat, just over the water** — the beach.
     *
     * Vanilla's coasts are a consequence of where its terrain noise happens to cross sea level, so they
     * are as steep as whatever made them and a beach is a block or two of sand. Here the shore is a
     * *stated* part of the profile, so it can be a hundred blocks of level sand and still meet a hillside
     * behind it. That is the difference worth having, and it is only expressible because an island knows
     * where its own coast is.
     */
    val beachShare: Double = DEFAULT_BEACH_SHARE,
    /** How far the beach climbs across its whole width. A few blocks: enough to be dry, not to be a bank. */
    val beachRise: Double = DEFAULT_BEACH_RISE,
    /**
     * What share of the radius the ground takes to climb from the **back of the beach** to its full height.
     *
     * **This is what stops an island being a dome.** A profile that rises all the way from the shore to
     * the middle is a bell, and reads as a hill someone put in the sea. A short shoulder gives what an
     * island actually looks like from above: a beach, a slope behind it, and then a broad interior at
     * height for the relief to work on.
     */
    val shoulder: Double = DEFAULT_SHOULDER,
    /** How many islands there are, and whether one of them is where the writer arrives — see [Layout]. */
    val layout: Layout = Layout.ANCHORED,
) : TerrainField {
    override val kind = FieldKind.ISLE

    override val horizontalReach = Double.POSITIVE_INFINITY

    // The lattice is hashed rather than sampled, so only the coast and the relief cost anything.
    override val samplesPerColumn = 2

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        // The coast is read once for the column rather than once per island: it is what makes a shoreline
        // ragged, and two neighbouring islands have no business disagreeing about it where they meet.
        val wandered = 1.0 + coastNoise.getValue(worldX / coastStretch, 0.0, worldZ / coastStretch)
            .coerceIn(-1.0, 1.0) * coastRoughness

        if (layout == Layout.SOLITARY) {
            return Spans.of(floorY, islandAt(0, 0, worldX, worldZ, wandered).roundToInt())
        }

        val cellX = floor(worldX / spacing).toInt()
        val cellZ = floor(worldZ / spacing).toInt()
        var highest = seabedY.toDouble()
        for (aroundX in -1..1) {
            for (aroundZ in -1..1) {
                val standing = islandAt(cellX + aroundX, cellZ + aroundZ, worldX, worldZ, wandered)
                if (standing > highest) highest = standing
            }
        }
        return Spans.of(floorY, highest.roundToInt())
    }

    /**
     * How high the island of this cell stands at this column — the seabed where it does not reach.
     *
     * Past the shore the ground falls away at [shelfSlope] and stops at the seabed, so an island keeps a
     * shallow skirt rather than ending in a wall. Inside it, the profile is flat-topped at the centre and
     * flattens again at the coast, which is what leaves a beach rather than a cone dipping into the water.
     */
    private fun islandAt(cellX: Int, cellZ: Int, worldX: Int, worldZ: Int, wandered: Double): Double {
        val holdsTheOrigin = layout != Layout.SCATTERED && cellX == 0 && cellZ == 0
        val centreX = if (holdsTheOrigin) 0.0 else (cellX + HALF + cellHash(cellX, cellZ, X_SALT) * jitter) * spacing
        val centreZ = if (holdsTheOrigin) 0.0 else (cellZ + HALF + cellHash(cellX, cellZ, Z_SALT) * jitter) * spacing
        val radius = shoreRadius * (1.0 + cellHash(cellX, cellZ, SIZE_SALT) * 2.0 * radiusVariation)
        val coast = (radius * wandered).coerceAtLeast(1.0)

        val runX = worldX - centreX
        val runZ = worldZ - centreZ
        val fromCentre = sqrt(runX * runX + runZ * runZ)
        if (fromCentre >= coast) {
            val shelf = shoreY - (fromCentre - coast) * shelfSlope
            return if (shelf <= seabedY) seabedY.toDouble() else shelf
        }
        val inland = 1.0 - fromCentre / coast
        // The beach: level bar a few blocks over its whole width, and carrying none of the relief, so it
        // comes out as sand rather than as the bottom of a hill.
        if (inland <= beachShare) return shoreY + beachRise * (inland / beachShare.coerceAtLeast(SMALLEST_SHOULDER))
        // And behind it the climb, measured from the back of the beach rather than from the water.
        val past = (inland - beachShare) / shoulder.coerceAtLeast(SMALLEST_SHOULDER)
        val climbing = past.coerceAtMost(1.0)
        // Hermite over the shoulder alone: flat where it meets the beach, flat again once it is up, and
        // everything further in is interior rather than more slope.
        val profile = climbing * climbing * (3.0 - 2.0 * climbing)
        val rolling = reliefNoise.getValue(worldX / reliefStretch, 0.0, worldZ / reliefStretch)
            .coerceIn(-1.0, 1.0) * relief
        return shoreY + beachRise + (peakRise - beachRise) * profile + rolling * profile
    }

    private val coastNoise = fieldNoise(seed, COAST_OCTAVE, COAST_AMPLITUDES)
    private val reliefNoise = fieldNoise(seed xor RELIEF_SALT, RELIEF_OCTAVE, RELIEF_AMPLITUDES)

    /** A wavelength of a fraction of an island, so a coast has bays rather than one lopsided bulge. */
    private val coastStretch = (shoreRadius * COAST_SHARE_OF_AN_ISLAND).coerceAtLeast(SMALLEST_STRETCH)

    /**
     * And the interior's, **a share of the island rather than a fixed distance** — which is what decides
     * whether a big island reads as country or as texture.
     *
     * It was sixty-four blocks whatever the island, and on a two-kilometre one that is a rumple: the same
     * hill over and over for an hour's walk, with nothing at the scale a person navigates by (Jonah,
     * walked 2026-09-08, "a little samey, especially on the large islands"). At an eighth of the radius a
     * continent gets ridges and basins hundreds of blocks across and a rock keeps its texture, because the
     * floor holds the small end exactly where it was.
     *
     * **The wavelength is also what keeps [relief] from severing an island.** A basin a quarter of the
     * island wide is a lagoon; the same depth at the same scale as the island would be a strait.
     */
    private val reliefStretch =
        (shoreRadius * RELIEF_SHARE_OF_AN_ISLAND / NOISE_WAVELENGTH).coerceAtLeast(SMALLEST_RELIEF_STRETCH)

    override fun resized(factor: Double, pivotY: Int) = copy(
        floorY = scaledAbout(floorY, factor, pivotY),
        seabedY = scaledAbout(seabedY, factor, pivotY),
        shoreY = scaledAbout(shoreY, factor, pivotY),
        peakRise = peakRise * factor,
        shoreRadius = shoreRadius * factor,
        spacing = spacing * factor,
        relief = relief * factor,
        beachRise = beachRise * factor,
    )

    /**
     * **How many islands there are, and where the first one is.** One fact rather than two flags, because
     * the two questions are not independent: an island alone that is not the one you arrive on is a world
     * with no land you can reach.
     */
    enum class Layout : StringRepresentable {
        /** A lattice of them, every cell jittered off its own point — including the one holding the origin. */
        SCATTERED,

        /**
         * The same lattice with the origin cell centred on the world origin.
         *
         * **Not a tidiness.** `Ages.findFooting` walks out 288 blocks looking for somewhere over the
         * waterline and then gives up; islands lie thousands apart, so a jittered origin cell puts the
         * spawn in open ocean with no land in any direction it can see.
         */
        ANCHORED,

        /**
         * One island, on the origin, and open sea everywhere else however far you sail.
         *
         * [spacing] and [jitter] go unread, there being no second cell for either to place, and
         * [radiusVariation] wants to be zero beside it: `cellHash` is a hash of the cell rather than of
         * the seed, so cell `(0, 0)` draws the same value in every world and a variation here is a fixed
         * offset on the size that was asked for rather than a difference between one Age and the next.
         */
        SOLITARY,
        ;

        override fun getSerializedName(): String = name.lowercase()

        companion object {
            val CODEC: Codec<Layout> = StringRepresentable.fromEnum(Layout::values)
        }
    }

    companion object {
        private const val HALF = 0.5

        /** How far the coast wanders. A third is plainly bays and headlands without severing anything. */
        const val DEFAULT_COAST_ROUGHNESS = 0.28

        /** How far the interior rolls. Against a low crown, enough for country without being mountains. */
        const val DEFAULT_RELIEF = 20.0

        /** How much of the radius is beach. Large on purpose — this is the thing vanilla cannot do. */
        const val DEFAULT_BEACH_SHARE = 0.2

        /** And how far it climbs across all of that. Four blocks over a hundred is level to walk on. */
        const val DEFAULT_BEACH_RISE = 4.0

        /** What share of the radius the climb takes. A third leaves two thirds of it as interior. */
        const val DEFAULT_SHOULDER = 0.33

        private const val SMALLEST_SHOULDER = 0.02

        /**
         * How steeply the seabed falls away outside the shore. **Very gentle**, so the sand carries on
         * under the water as shallows a long way out rather than dropping off the end of the beach.
         */
        const val DEFAULT_SHELF_SLOPE = 0.14

        // Four octaves from a moderate first one, for the same reason `Escarpment` uses four: spreading the
        // amplitude over more of them normalises every one down and the coast comes out smoother, not
        // rougher.
        private const val COAST_OCTAVE = -5
        private val COAST_AMPLITUDES = listOf(1.0, 0.6, 0.3, 0.15)

        /**
         * How much of an island one bay spans — **enough bays to count, and no more**.
         *
         * Both ends of this were walked into. A twentieth of the radius put a single lobe across the whole
         * coast and the island came out a smooth blob; a hundred and twenty-fifth made the largest feature
         * smaller than the amplitude swinging it, and the coast came out as fuzz rather than as bays. This
         * puts eight or so around a circumference, which is a coastline.
         */
        private const val COAST_SHARE_OF_AN_ISLAND = 0.024

        private const val RELIEF_OCTAVE = -4

        /**
         * **Weighted hard onto the first octave**, because the amplitude the whole stack is scaled by is
         * now large enough for the harmonics to matter. At a continent's relief the third octave was
         * thirty-three blocks of rise and fall across sixty, which is scree rather than country and read
         * from above as static laid over the landforms underneath. An archipelago's relief is a third of
         * that and never noticed, which is why this went unseen until the ladder grew.
         */
        private val RELIEF_AMPLITUDES = listOf(1.0, 0.5, 0.18)

        /**
         * How much of an island one ridge or basin spans, and the shortest that may get — see
         * [reliefStretch]. The floor is what the whole ladder used to be, so the smallest islands are
         * untouched and only the ones with room to spare grow features to match.
         */
        private const val RELIEF_SHARE_OF_AN_ISLAND = 0.125
        private const val SMALLEST_RELIEF_STRETCH = 4.0

        /** Blocks per unit of noise at [RELIEF_OCTAVE], which is what turns a wanted width into a stretch. */
        private const val NOISE_WAVELENGTH = 16.0

        private const val RELIEF_SALT = 0x15_1E_5L

        // Three separate draws from one cell: where it sits, and how big it is.
        private const val X_SALT = 0x15_1A
        private const val Z_SALT = 0x15_1B
        private const val SIZE_SALT = 0x15_1C

        val CODEC: MapCodec<Isle> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.INT.fieldOf("floor_y").forGetter(Isle::floorY),
                Codec.INT.fieldOf("seabed_y").forGetter(Isle::seabedY),
                Codec.INT.fieldOf("shore_y").forGetter(Isle::shoreY),
                Codec.DOUBLE.fieldOf("peak_rise").forGetter(Isle::peakRise),
                Codec.DOUBLE.fieldOf("shore_radius").forGetter(Isle::shoreRadius),
                Codec.DOUBLE.fieldOf("radius_variation").forGetter(Isle::radiusVariation),
                Codec.DOUBLE.fieldOf("spacing").forGetter(Isle::spacing),
                Codec.DOUBLE.fieldOf("jitter").forGetter(Isle::jitter),
                Codec.LONG.fieldOf("seed").forGetter(Isle::seed),
                Codec.DOUBLE.optionalFieldOf("coast_roughness", DEFAULT_COAST_ROUGHNESS)
                    .forGetter(Isle::coastRoughness),
                Codec.DOUBLE.optionalFieldOf("relief", DEFAULT_RELIEF).forGetter(Isle::relief),
                Codec.DOUBLE.optionalFieldOf("shelf_slope", DEFAULT_SHELF_SLOPE).forGetter(Isle::shelfSlope),
                Codec.DOUBLE.optionalFieldOf("beach_share", DEFAULT_BEACH_SHARE).forGetter(Isle::beachShare),
                Codec.DOUBLE.optionalFieldOf("beach_rise", DEFAULT_BEACH_RISE).forGetter(Isle::beachRise),
                Codec.DOUBLE.optionalFieldOf("shoulder", DEFAULT_SHOULDER).forGetter(Isle::shoulder),
                Layout.CODEC.optionalFieldOf("layout", Layout.ANCHORED).forGetter(Isle::layout),
            ).apply(instance, ::Isle)
        }
    }
}
