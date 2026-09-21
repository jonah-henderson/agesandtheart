package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Rolling land with a **river system** cut into it: valleys that branch, join, and grow as they descend.
 *
 * The thing neither [Canyon] nor [CellCanyon] can be. A canyon has one axis, and a mosaic's joins are all
 * peers; a drainage network has a **hierarchy** — headwaters that carry little, trunks that carry the lot,
 * and a direction that is always downhill. That is a property of the whole network, and the trick that
 * makes it expressible one column at a time is to build it the other way round:
 *
 * **The tree is derived from the land, not the land from the tree.** A lattice of nodes is laid over the
 * world; each takes its height from the land noise and flows to whichever of its eight neighbours sits
 * lowest. That rule is *local* — it needs nothing but a neighbourhood — yet the edges it produces form a
 * forest that runs downhill by construction and converges, because that is what following the steepest
 * neighbour does. Nothing is accumulated, nothing is iterated, and no two columns can disagree.
 *
 * Two things follow for free. A node nobody flows into is a **headwater**; one several flow into is a
 * **trunk**, and counting inflows is the stream order that decides how wide and deep its valley is. And a
 * node lower than all eight of its neighbours has nowhere to go, so its valley simply ends — which is a
 * basin, and fills with water like any other hollow under the waterline.
 *
 * The cross-section is [CanyonProfile] again, scaled to each reach's own depth, so a trunk valley is the
 * same shape as a headwater's and merely larger.
 *
 * **The one constraint a caller must keep**: the widest [halfWidth] plus the [jitter] must stay inside
 * [spacing], or a valley reaches past the neighbourhood this scans and is clipped where the scan ends.
 */
data class Drainage(
    /** The bedrock this stands on. */
    val floorY: Int,
    /** The mean height of the land, before the valleys are cut into it. */
    val landY: Int,
    /** How far the land rolls above and below [landY], in blocks — and so how much fall the rivers have. */
    val relief: Double,
    /** Blocks per unit of land noise: how far it is between one hill and the next. */
    val landStretch: Double,
    /** How far apart the river nodes lie — the length of one reach, and the network's whole scale. */
    val spacing: Double,
    /** How far a node stands off its lattice point, as a share of [spacing]. Without it the rivers are a grid. */
    val jitter: Double,
    val seed: Long,
    /** What a valley's cross-section looks like — the same object a [Canyon] carries. */
    val profile: CanyonProfile = CanyonProfile.DEFAULT,
    /** How deep a **headwater** cuts below the land it runs through. */
    val valleyDepth: Double = DEFAULT_VALLEY_DEPTH,
    /** How much deeper each stream joining makes it, as a share of [valleyDepth]. */
    val depthPerOrder: Double = DEFAULT_DEPTH_PER_ORDER,
    /** How far either side of a **headwater** its valley reaches. */
    val halfWidth: Double = DEFAULT_HALF_WIDTH,
    /** And how much wider each stream joining makes it, as a share of [halfWidth]. */
    val widthPerOrder: Double = DEFAULT_WIDTH_PER_ORDER,
    /**
     * How far the whole field is warped sideways, in blocks — what makes a reach a **bend** rather than a
     * ruled segment between two nodes.
     *
     * A domain warp rather than a per-reach meander, and that is the cheap part: warping the *question*
     * costs two lookups per column, where bending each of nine reaches would cost nine. It also bends the
     * junctions and the land along with the rivers, so nothing comes out of alignment with anything else —
     * the land is read at the warped point too, which is why the valleys still sit in its low ground.
     */
    val meander: Double = DEFAULT_MEANDER,
    /**
     * How deep the water stands over the channel bed, before the per-order deepening.
     *
     * **A river cannot be a waterline.** [SeaFill] pours one level over the whole Age, and a network runs
     * downhill everywhere — so a single plane wets the lowest trunks and leaves every headwater a dry
     * gully. The water has to follow its own bed, which is what this field and [describes] are for.
     */
    val waterDepth: Double = DEFAULT_WATER_DEPTH,
    /**
     * Whether this is the ground or the water in it. The same network answered twice: the ground goes to
     * the terrain, and the water to `SeaFill.wet`, which pours it wherever the shape left room.
     */
    val describes: FieldYield = FieldYield.GROUND,
) : TerrainField {
    override val kind = FieldKind.DRAINAGE

    override val horizontalReach = Double.POSITIVE_INFINITY

    // The scan, this column's own land, and the two the warp costs.
    override val samplesPerColumn = SCAN * SCAN + 3

    /**
     * **Everything the network needs for one column**, in world X and Z. Thread-confined and overwritten per
     * column: chunk workers evaluate columns concurrently, so a scan shared between them would answer one
     * thread's question with another's neighbourhood.
     */
    private val scratch = ThreadLocal.withInitial { LatticeScan(SCAN) }

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        // Everything below works in warped space, the land included — see [meander].
        val atX = worldX + warpNoise.get(worldX / warpStretch, 0.0, worldZ / warpStretch).toDouble() * meander
        val atZ = worldZ + warpNoise.get(worldZ / warpStretch, WARP_PLANE, worldX / warpStretch).toDouble() * meander
        val landHere = landAt(atX, atZ)
        val cellX = floor(atX / spacing).toInt()
        val cellZ = floor(atZ / spacing).toInt()
        val around = scanAround(cellX, cellZ)

        // **One reach decides both.** The ground takes whichever cut this column deepest, and the water
        // is that same reach's — an earlier version took the lowest ground from one reach and the highest
        // surface from another, which stood a wall of water out over a trunk wherever a tributary's valley
        // overlapped it, at the tributary's higher level.
        var ground = landHere
        var channelY = NO_WATER
        var carried = 0
        val positionsX = around.first
        val positionsZ = around.second
        // Only the middle nine have both a flow of their own and every inflow visible, and only their
        // reaches can come within a valley's width of this column — see the note on [spacing].
        for (row in RING - 1..RING + 1) {
            for (column in RING - 1..RING + 1) {
                val here = row * SCAN + column
                val downhill = around.flowsTo[here]
                if (downhill == NOWHERE) continue
                val inflows = around.inflowsTo(here)
                val widthHere = halfWidth * (1.0 + inflows * widthPerOrder)
                val along = alongSegment(
                    atX, atZ,
                    positionsX[here], positionsZ[here], positionsX[downhill], positionsZ[downhill],
                )
                val nearestX = positionsX[here] + (positionsX[downhill] - positionsX[here]) * along
                val nearestZ = positionsZ[here] + (positionsZ[downhill] - positionsZ[here]) * along
                val fromAxis = distance(atX, atZ, nearestX, nearestZ)
                if (fromAxis >= widthHere) continue

                // The channel bed under whichever point of the reach this column is nearest to, so the
                // fall the land has along the reach is the fall the river has. Two reaches meeting at a
                // node share that node's height, which is what makes a confluence meet rather than step.
                val deepening = valleyDepth * (1.0 + inflows * depthPerOrder)
                val bed = around.height[here] +
                    (around.height[downhill] - around.height[here]) * along - deepening
                // One at the rim, so the cut meets the land it was cut into rather than stepping down to it.
                val cut = bed + (landHere - bed) * profile.climbAt(fromAxis / widthHere)
                if (cut >= ground) continue
                ground = cut
                channelY = bed
                carried = inflows
            }
        }
        if (describes == FieldYield.GROUND) return Spans.of(floorY, ground.roundToInt())
        if (channelY == NO_WATER) return Spans.EMPTY
        // Never over the land the valley was cut into: a river cannot stand higher than its own banks, and
        // capping here is what stops one reading as a sheet of water in mid-air if anything above drifts.
        val surface = (channelY + waterDepth * (1.0 + carried * depthPerOrder)).coerceAtMost(landHere)
        // Everything up to it. The fill only ever puts water where the shape left room, so handing it the
        // whole column below the surface costs nothing and needs no second geometry.
        return Spans.of(floorY, surface.roundToInt())
    }

    /** Where every node of the scan stands, how high it is, and which way it flows. */
    private fun scanAround(cellX: Int, cellZ: Int): LatticeScan {
        val around = scratch.get()
        around.fill(
            cellFirst = cellX,
            cellSecond = cellZ,
            nodeFirst = ::nodeX,
            nodeSecond = ::nodeZ,
            heightAt = ::landAt,
        )
        return around
    }

    /** The land's own surface, before anything is cut into it. */
    private fun landAt(worldX: Double, worldZ: Double): Double =
        landY + landNoise.get(worldX / landStretch, 0.0, worldZ / landStretch).toDouble().coerceIn(-1.0, 1.0) * relief

    // Both axes' jitters are hashed on the cell as (x, z).
    private fun nodeX(cellX: Int, cellZ: Int): Double = latticeNode(cellX, cellX, cellZ, X_SALT, jitter, spacing)

    private fun nodeZ(cellX: Int, cellZ: Int): Double = latticeNode(cellZ, cellX, cellZ, Z_SALT, jitter, spacing)

    private val landNoise = fieldNoise(seed, LAND_OCTAVE, LAND_AMPLITUDES)
    private val warpNoise = fieldNoise(seed xor WARP_SALT, WARP_OCTAVE, WARP_AMPLITUDES)
    private val warpStretch = (spacing * WARP_SHARE_OF_A_REACH).coerceAtLeast(SMALLEST_STRETCH)

    override fun resized(factor: Double, pivotY: Int) = copy(
        floorY = scaledAbout(floorY, factor, pivotY),
        landY = scaledAbout(landY, factor, pivotY),
        relief = relief * factor,
        landStretch = landStretch * factor,
        spacing = spacing * factor,
        valleyDepth = valleyDepth * factor,
        halfWidth = halfWidth * factor,
        meander = meander * factor,
        waterDepth = waterDepth * factor,
    )

    companion object {
        /**
         * How many nodes across the scan is. **Seven, and it is not a tuning parameter**: a reach is drawn for
         * the middle nine, whose stream order needs their neighbours' flow, whose flow needs *their*
         * neighbours' heights. Three rings out, exactly.
         */
        private const val SCAN = 7
        private const val RING = 3

        /** No reach wets this column, and nothing is lower than it. */
        private const val NO_WATER = Double.NEGATIVE_INFINITY

        /** How deep a headwater runs. Shallow: a stream you can wade, deepening as it gathers. */
        const val DEFAULT_WATER_DEPTH = 3.0

        /** How deep a headwater cuts, and how much each confluence adds. */
        const val DEFAULT_VALLEY_DEPTH = 11.0
        const val DEFAULT_DEPTH_PER_ORDER = 0.7

        /** How wide a headwater's valley is, and how much each confluence adds. */
        const val DEFAULT_HALF_WIDTH = 17.0
        const val DEFAULT_WIDTH_PER_ORDER = 0.8

        /** How far the field bends. Getting on for half a reach, so a reach is plainly not a straight line. */
        const val DEFAULT_MEANDER = 48.0

        // A wavelength of a reach or so, so a river bends within its own reach rather than the whole map
        // sliding. The two components are read on different planes, or the warp is a diagonal shear.
        private const val WARP_OCTAVE = -4
        private val WARP_AMPLITUDES = listOf(1.0, 0.5)
        private const val WARP_PLANE = 512.0
        private const val WARP_SHARE_OF_A_REACH = 0.08
        private const val WARP_SALT = 0xBE_4DL

        // Broad and smooth: the land is what the network is derived from, so its hills are the watersheds.
        private const val LAND_OCTAVE = -5
        private val LAND_AMPLITUDES = listOf(1.0, 0.5)

        // Separates the two axes' jitters within one cell; the mix itself is [cellHash]'s.
        private const val X_SALT = 0x1_D15E
        private const val Z_SALT = 0x2_D15E

        val CODEC: MapCodec<Drainage> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.INT.fieldOf("floor_y").forGetter(Drainage::floorY),
                Codec.INT.fieldOf("land_y").forGetter(Drainage::landY),
                Codec.DOUBLE.fieldOf("relief").forGetter(Drainage::relief),
                Codec.DOUBLE.fieldOf("land_stretch").forGetter(Drainage::landStretch),
                Codec.DOUBLE.fieldOf("spacing").forGetter(Drainage::spacing),
                Codec.DOUBLE.fieldOf("jitter").forGetter(Drainage::jitter),
                Codec.LONG.fieldOf("seed").forGetter(Drainage::seed),
                CanyonProfile.MAP_CODEC.forGetter(Drainage::profile),
                Codec.DOUBLE.optionalFieldOf("valley_depth", DEFAULT_VALLEY_DEPTH).forGetter(Drainage::valleyDepth),
                Codec.DOUBLE.optionalFieldOf("depth_per_order", DEFAULT_DEPTH_PER_ORDER)
                    .forGetter(Drainage::depthPerOrder),
                Codec.DOUBLE.optionalFieldOf("half_width", DEFAULT_HALF_WIDTH).forGetter(Drainage::halfWidth),
                Codec.DOUBLE.optionalFieldOf("width_per_order", DEFAULT_WIDTH_PER_ORDER)
                    .forGetter(Drainage::widthPerOrder),
                Codec.DOUBLE.optionalFieldOf("meander", DEFAULT_MEANDER).forGetter(Drainage::meander),
                Codec.DOUBLE.optionalFieldOf("water_depth", DEFAULT_WATER_DEPTH).forGetter(Drainage::waterDepth),
                FieldYield.CODEC.optionalFieldOf("describes", FieldYield.GROUND).forGetter(Drainage::describes),
            ).apply(instance, ::Drainage)
        }
    }
}
