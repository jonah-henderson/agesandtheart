package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.roundToInt

/**
 * A canyon along **every boundary of a mosaic** — the plateau cracked into cells, with a gorge down each
 * join. [Canyon] with its axis thrown away and [RegionMap.blocksFromSeamAt] put in its place.
 *
 * The two are siblings rather than variants, which is why they are separate nodes: a canyon has a
 * *direction*, and everything about it follows from that — it meanders along its length, its two flanks
 * differ, its bed runs downstream. A cell boundary has none of those. What survives the swap is the
 * cross-section, which is why [CanyonProfile] is an object: the bed, the gorge and the benches are
 * identical here, and only the plan is different.
 *
 * **Deliberately not a drainage network.** Every boundary of a mosaic is a peer, so there is no trunk and
 * no tributary — water could not run through this. It reads as a cracked plate rather than as country
 * a river made, and that strangeness is the reason to have it.
 *
 * The mosaic is [RegionMap]'s argmax over one noise per member rather than a true Voronoi, which is the
 * better tool here: the cells come out round-cornered and the joins wander, where a true Voronoi's edges
 * are ruled straight. Cells that draw the same member have **no** boundary between them, so about one
 * join in [RegionMap.members] is simply missing and two cells run together — left alone, since an
 * occasional double-sized mesa is the one thing a regular mosaic most needs.
 */
data class CellCanyon(
    /** Whose boundaries the canyons run along. A one-member map has none, and this claims nothing. */
    val map: RegionMap,
    /** How far either side of a boundary the canyon reaches, so it is twice this across. */
    val halfWidth: Double,
    /** The **mean** level of the floor at the boundary, about which [bedRelief] varies. */
    val floorY: Int,
    /** Where the canyon meets the plateau, and so where it stops cutting. One past the ground it cuts. */
    val rimY: Int,
    val seed: Long,
    /** What the cross-section looks like — **the same object a [Canyon] uses**, and the point of the pair. */
    val profile: CanyonProfile = CanyonProfile.DEFAULT,
    /** How far the wall frays, in blocks. What stops every terrace edge being a drawn contour. */
    val roughness: Double = DEFAULT_ROUGHNESS,
    /** How far the floor stands above and below [floorY], so the bottom is not a poured slab. */
    val bedRelief: Double = DEFAULT_BED_RELIEF,
    /**
     * What share of [halfWidth] one join differs from the next by. Without it every cell is walled the
     * same, which is the failure a mosaic falls into most readily — regular cells *and* regular canyons.
     */
    val widthVariation: Double = DEFAULT_WIDTH_VARIATION,
) : TerrainField {
    override val kind = FieldKind.CELL_CANYON

    // A mosaic covers the world, so there is no bounded neighbourhood to scan — as with [Rift].
    override val horizontalReach = Double.POSITIVE_INFINITY

    // The map asks one per member, and the two noises below are ours.
    override val samplesPerColumn = map.members + 2

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val fromSeam = map.blocksFromSeamAt(worldX, worldZ)
        if (fromSeam.isInfinite()) return Spans.EMPTY
        val frayed = wallNoise.get(worldX / WALL_STRETCH, 0.0, worldZ / WALL_STRETCH).toDouble() * roughness
        val fromAxis = (fromSeam + frayed).coerceAtLeast(0.0)
        val widthHere = widthAt(worldX, worldZ)
        if (fromAxis >= widthHere) return Spans.EMPTY
        val climb = profile.climbAt(fromAxis / widthHere)
        val floorHere = floorY + ((rimY - floorY) * climb).roundToInt() + bedAt(worldX, worldZ, climb)
        return Spans.of(floorHere, Spans.HIGHEST_Y)
    }

    /**
     * How wide the canyon is here. Read on a wavelength of the cells' own, so one whole join is wide where
     * its neighbour is a slot — varying it per column would only fray an edge the [roughness] already frays.
     */
    private fun widthAt(worldX: Int, worldZ: Int): Double {
        if (widthVariation <= 0.0) return halfWidth
        val stands = widthNoise.get(worldX / widthStretch, 0.0, worldZ / widthStretch).toDouble()
            .coerceIn(-1.0, 1.0) * widthVariation
        return halfWidth * (1.0 + stands).coerceAtLeast(CanyonProfile.SMALLEST_SHARE)
    }

    /**
     * How far the floor stands over its mean here — **isotropic, unlike a [Canyon]'s**, which draws its bed
     * out downstream. A join has no downstream to draw it along.
     */
    private fun bedAt(worldX: Int, worldZ: Int, climb: Double): Int {
        if (bedRelief <= 0.0) return 0
        val onTheBed = 1.0 - (climb / profile.gorgeRise.coerceAtLeast(CanyonProfile.SMALLEST_SHARE)).coerceIn(0.0, 1.0)
        if (onTheBed <= 0.0) return 0
        val standing = bedNoise.get(worldX / BED_STRETCH, 0.0, worldZ / BED_STRETCH).toDouble().coerceIn(-1.0, 1.0)
        return (standing * bedRelief * onTheBed).roundToInt()
    }

    private val wallNoise = fieldNoise(seed, WALL_OCTAVE, WALL_AMPLITUDES)
    private val bedNoise = fieldNoise(seed xor BED_SALT, BED_OCTAVE, BED_AMPLITUDES)
    private val widthNoise = fieldNoise(seed xor WIDTH_SALT, WIDTH_OCTAVE, WIDTH_AMPLITUDES)

    /** A cell's own scale, so a join is wide or narrow over its whole length rather than in patches. */
    private val widthStretch = (map.scale * WIDTH_SHARE_OF_A_CELL).coerceAtLeast(SMALLEST_STRETCH)

    override fun resized(factor: Double, pivotY: Int) = copy(
        map = map.resized(factor),
        halfWidth = halfWidth * factor,
        floorY = scaledAbout(floorY, factor, pivotY),
        rimY = scaledAbout(rimY, factor, pivotY),
        roughness = roughness * factor,
        bedRelief = bedRelief * factor,
    )

    companion object {
        const val DEFAULT_ROUGHNESS = 7.0
        const val DEFAULT_BED_RELIEF = 5.0

        /** A third either way, which is plainly visible without leaving a join too narrow to walk. */
        const val DEFAULT_WIDTH_VARIATION = 0.33

        private const val WALL_OCTAVE = -4
        private val WALL_AMPLITUDES = listOf(1.0, 0.5, 0.25)
        private const val WALL_STRETCH = 1.0

        private const val BED_OCTAVE = -4
        private val BED_AMPLITUDES = listOf(1.0, 0.5)
        private const val BED_STRETCH = 2.0
        private const val BED_SALT = 0xCE11_8EDL

        private const val WIDTH_OCTAVE = -4
        private val WIDTH_AMPLITUDES = listOf(1.0, 0.5)
        private const val WIDTH_SALT = 0xCE11_D1CEL

        /** How much of a cell one width reading spans. Under half, so a join varies along itself. */
        private const val WIDTH_SHARE_OF_A_CELL = 0.4

        /**
         * [base] with a canyon opened along every join of [map], or [base] itself where there is nothing
         * to open one along — the same one-member collapse [Rift.opened] enforces.
         */
        fun cut(base: TerrainField, cells: CellCanyon): TerrainField {
            val thereAreNoJoins = cells.map.members <= 1
            val takesNoGround = cells.halfWidth <= 0.0 || cells.rimY <= cells.floorY
            return if (thereAreNoJoins || takesNoGround) base else Subtract(base, cells)
        }

        /**
         * The mosaic is **nested under a key** rather than inlined the way [Rift] inlines its map, which
         * is not a style choice: a `RegionMap` writes a `seed` field of its own, and flattening it beside
         * a node that also has one leaves the two sharing a name. Encoding looks fine and the decode
         * silently takes whichever was written last — here that gave every mosaic the wall noise's seed.
         * [SeaFill] nests its map for the same reason.
         */
        val CODEC: MapCodec<CellCanyon> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                RegionMap.MAP_CODEC.codec().fieldOf("cells").forGetter(CellCanyon::map),
                Codec.DOUBLE.fieldOf("half_width").forGetter(CellCanyon::halfWidth),
                Codec.INT.fieldOf("floor_y").forGetter(CellCanyon::floorY),
                Codec.INT.fieldOf("rim_y").forGetter(CellCanyon::rimY),
                Codec.LONG.fieldOf("seed").forGetter(CellCanyon::seed),
                CanyonProfile.MAP_CODEC.forGetter(CellCanyon::profile),
                Codec.DOUBLE.optionalFieldOf("roughness", DEFAULT_ROUGHNESS).forGetter(CellCanyon::roughness),
                Codec.DOUBLE.optionalFieldOf("bed_relief", DEFAULT_BED_RELIEF).forGetter(CellCanyon::bedRelief),
                Codec.DOUBLE.optionalFieldOf("width_variation", DEFAULT_WIDTH_VARIATION)
                    .forGetter(CellCanyon::widthVariation),
            ).apply(instance, ::CellCanyon)
        }
    }
}
