package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * An alpine range: foothills climbing to a crest, cut into ridges and peaks by its own drainage.
 *
 * **The mountains are what is left standing, not what was piled up.** Every other landform here builds a
 * surface and takes pieces out of it; noise decides a height and erosion answers afterwards. That is why
 * noise mountains read as hills of varying size — a peak made by adding noise has no relationship to the
 * valley beside it, and real ones are defined by exactly that relationship. So this works the other way
 * round: the drainage network is laid down first, and **the ground is the lower envelope of the hillslopes
 * rising away from every channel**. A ridge is not placed anywhere; it is where two hillslopes climbing
 * out of neighbouring valleys happen to meet, and it is sharp because both of them are planar.
 *
 * Four models do the work, and each answers one of the things vanilla's mountains lack:
 *
 * - **Threshold hillslopes.** Above a certain size a hillside in rock sits at the angle landsliding takes
 *   it to — about thirty degrees, whatever the uplift rate — so a slope is a plane rather than a curve.
 *   [hillslopeGrade] is that angle, and it is the whole of why the flanks come out as triangular facets
 *   meeting at knife-edge crests. Relief stops being an amplitude somebody chose and becomes arithmetic:
 *   valleys [spacing] apart at grade *g* put the ridge between them `spacing * g / 2` above the floor.
 * - **A critical-taper wedge.** A range is a belt, thin at its toe and thick at its core, so elevation and
 *   relief both ramp inward from the front — see [RangeProfile]. **A foothill is the toe of the wedge, not
 *   a small mountain**, which is why real foothills are lower *and* gentler *and* rounder all at once.
 * - **Stream order.** A channel that has gathered tributaries cuts deeper and carries a wider valley, so a
 *   trunk sits far below the headwaters draining into it — and a tributary's floor is left standing above
 *   the trunk's, which is a hanging valley and where the waterfalls come from.
 * - **Glaciation.** The forms that say *Alps* rather than merely *mountains* are ice's: U-shaped troughs,
 *   cirques biting back into the crest, and a summit height pinned near the snowline. See [Glaciation].
 *
 * The network itself is [Drainage]'s trick and nothing is new about it: a lattice of nodes each flowing to
 * its lowest neighbour is a *local* rule that yields a globally consistent hierarchy, needing no iteration
 * and no shared state, so a column can be answered on its own. What differs is which way the derivation
 * runs. [Drainage] takes the land as given and cuts valleys into it; here the land the nodes stand on is
 * only a scaffold for deciding where the water goes, and every surface a player walks on is built back up
 * from the channels.
 *
 * **The one constraint a caller must keep**: relief is bounded by [spacing] and [hillslopeGrade] together,
 * so raising a range means moving its valleys apart, not steepening its sides past what rock stands at.
 */
data class MountainRange(
    /** The bedrock everything stands on. */
    val floorY: Int,
    /** Where the range is, how high it climbs, and which way its grain runs. */
    val profile: RangeProfile,
    /** What the ice did to it. */
    val glaciation: Glaciation,
    /** How far apart the drainage nodes lie — one reach, and so the whole network's scale. */
    val spacing: Double,
    /** How far a node stands off its lattice point, as a share of [spacing]. Without it the valleys are a grid. */
    val jitter: Double,
    val seed: Long,
    /**
     * The tangent of the angle a hillside stands at: rise over run. **A property of rock, not a dial** —
     * bare bedrock slopes cluster near thirty-five degrees and soil-mantled ones near thirty, because that
     * is where landsliding takes over from incision. Scaled down through the foothills by
     * [RangeProfile.reliefScaleAt], since slopes only reach the threshold where the ground is being cut fast.
     */
    val hillslopeGrade: Double = DEFAULT_HILLSLOPE_GRADE,
    /** How far a **headwater** channel sits below the land it drains. */
    val incision: Double = DEFAULT_INCISION,
    /** And how much deeper each stream joining puts it, as a share of [incision]. */
    val incisionPerOrder: Double = DEFAULT_INCISION_PER_ORDER,
    /** How far the flanks are roughened, in blocks — texture on a hillside, never the shape of one. */
    val roughness: Double = DEFAULT_ROUGHNESS,
    /** How wide one lump of that roughness runs. */
    val roughnessStretch: Double = DEFAULT_ROUGHNESS_STRETCH,
    /** How deep the water stands over a **headwater** bed, before the per-order deepening. */
    val waterDepth: Double = DEFAULT_WATER_DEPTH,
    /**
     * Half the width of the channel cut into the valley floor for a **headwater**, in blocks.
     *
     * **Minecraft water is why this exists, and a glacial trough is why it was needed here rather than in
     * [Drainage].** A trough has a wide, nearly flat floor — that is what makes it a trough — so filling it
     * to the water's depth lays down a sheet a hundred blocks across with barely any fall to its edges.
     * Water spreads off a sheet like that, and where two flows meet the game turns flowing water back into
     * sources, which builds mounds of it downstream.
     *
     * A real trough does not carry a sheet either: it carries a **misfit stream** in a channel of its own,
     * far smaller than the valley the ice cut. So the floor keeps its shape and the river gets a trench.
     */
    val channelHalfWidth: Double = DEFAULT_CHANNEL_HALF_WIDTH,
    /** How much wider each stream gathered makes it, as a share of [channelHalfWidth]. */
    val channelPerOrder: Double = DEFAULT_CHANNEL_PER_ORDER,
    /**
     * How far a reach bows off the straight line between its nodes, as a share of its own length — see
     * [measureBend]. Zero rules every valley straight, which is what a lattice looks like.
     */
    val reachBow: Double = DEFAULT_REACH_BOW,
    /** Whether this is the rock or the water in it — the same network asked twice, as [Drainage] is. */
    val describes: FieldYield = FieldYield.GROUND,
) : TerrainField {
    override val kind = FieldKind.MOUNTAIN_RANGE

    // A range runs right across a world, so there is no bounded neighbourhood to scan for.
    override val horizontalReach = Double.POSITIVE_INFINITY

    // The scan, the two the warp costs, this column's own land, the summit cap and the roughness.
    override val samplesPerColumn = SCAN * SCAN + 5

    /**
     * Everything the network needs for one column.
     *
     * **Thread-confined scratch rather than a fresh allocation, and never anything longer-lived than a
     * call.** [Drainage] holds the equivalent in locals because chunk workers evaluate columns
     * concurrently, and a field remembering its last neighbourhood would answer one thread's question with
     * another's. A scan of a hundred and twenty-one nodes is four arrays and some three kilobytes, though,
     * and allocating that per column left megabytes of garbage per chunk — so the arrays are held per
     * thread and overwritten, which keeps the confinement and drops the churn.
     *
     * Positions are in the range's own frame: along the strike and across it, which is where the lattice
     * lives so that the grain and the wedge can both be read straight off a node.
     */
    private class Neighbourhood {
        val along = DoubleArray(SCAN * SCAN)
        val across = DoubleArray(SCAN * SCAN)
        val height = DoubleArray(SCAN * SCAN)
        val flowsTo = IntArray(SCAN * SCAN)

        /** Where [measureBend] left its answer — see there for why it is written rather than returned. */
        var bendDistance = 0.0
    }

    private val scratch = ThreadLocal.withInitial { Neighbourhood() }

    /**
     * **The one field in the toolkit that had to be memoised, and the measurement that finally asked for
     * it.** A general cache over `columnSpans` was designed and deferred three times, each time on evidence:
     * it puts shared mutable state into a pure, thread-safe design, and nothing was paying enough to want
     * it. This node is. A column here costs a scan of a hundred and twenty-one noise samples, and a chunk
     * asks for the same columns from four directions — the fill, `Grounding`'s three probes per biome cell,
     * `ClimateDepth` and the carvers — so **the answer was being derived four times over.** Measured: the
     * bare landform cost 153 ms/chunk, and each consumer that asked again added another sixty or so.
     *
     * A whole chunk's worth of columns and a ring beyond it, indexed straight off the low bits of the block
     * coordinates so a chunk and its neighbours cannot collide with themselves. Thread-confined, because a
     * field is shared across every chunk worker; the same shape [Grounding] and `ClimateDepth.BelowTerrain`
     * already use, one size up.
     */
    private val memo = ColumnMemo(::derive)

    override fun columnSpans(worldX: Int, worldZ: Int): Spans = memo.spansAt(worldX, worldZ)

    private fun derive(worldX: Int, worldZ: Int): Spans {
        // Everything below works in the warped frame, the wedge included, so the range front frays and the
        // reaches bend rather than running as ruled segments between their nodes.
        val atX = worldX + profile.warpedX(worldX, worldZ)
        val atZ = worldZ + profile.warpedZ(worldX, worldZ)
        val along = alongBearing(profile.bearing, atX, atZ)
        val across = profile.acrossStrikeAt(atX, atZ)

        // **One sample answers the whole plan here.** How near a range this column is decides the land it
        // stands on and how hard that land is being worked alike, so both are read off it rather than
        // sampled twice.
        val nearness = profile.nearnessAt(along, across)
        val landHere = profile.landFrom(nearness)
        // Slopes only stand at the threshold angle where the ground is being cut fast, which is on a range —
        // so the same number that lowers a basin also flattens it.
        val reliefScale = profile.reliefScaleFrom(nearness)
        val grade = hillslopeGrade * reliefScale

        val cellAlong = floor(along / spacing).toInt()
        val cellAcross = floor(across / spacing).toInt()
        val around = scanAround(cellAlong, cellAcross)

        // The hillslope envelope, kept apart from the finished ground because it is what the water may
        // stand against: a cirque scooped below its own channel has to be allowed to hold a tarn.
        var hillslopes = Double.POSITIVE_INFINITY
        var scooped = Double.POSITIVE_INFINITY
        // The water a gully on this column could cut down to — see [GULLY_DRAINS_AT] for why it is a bed
        // plus a gradient rather than the lowest bed nearby.
        var drainsTo = Double.POSITIVE_INFINITY
        var channelY = NO_WATER
        var carried = 0.0
        // How far out of its own channel this column stands, for the reach that decided the ground — what
        // says whether there is water here at all.
        var outOfChannel = Double.POSITIVE_INFINITY

        // Every reach whose cone could be the lowest here — see [DRAWN] for why that is a wider set than a
        // drainage network needs, and what goes wrong when it is not wide enough.
        for (row in RING - DRAWN..RING + DRAWN) {
            for (column in RING - DRAWN..RING + DRAWN) {
                val here = row * SCAN + column
                val downhill = around.flowsTo[here]
                if (downhill == NOWHERE) continue
                val joining = inflowsTo(here, around.flowsTo)

                // How far this reach bows, as a pure function of the node it leaves — so the two query cells
                // that can both see it always draw the same curve, and there is no seam between them.
                val bow = cellHash(cellAlong + column - RING, cellAcross + row - RING, BOW_SALT) * reachBow * 2.0
                measureBend(
                    around, along, across,
                    around.along[here], around.across[here], around.along[downhill], around.across[downhill],
                    bow,
                )
                // **How far down the reach this column is, measured on the straight chord and not on the
                // bow.** The bow decides where the valley *is*; this decides how far the bed has fallen, and
                // it has to be continuous. Read off the polyline it is not: on the inside of a bend two
                // chords compete, and the nearest *point* jumps between them the instant the winner changes
                // even though the distance does not — which put a nine-block wall down the concave bank of
                // every meander. A projection onto the chord is smooth everywhere and says the same thing.
                val alongReach = alongSegment(
                    along, across,
                    around.along[here], around.across[here], around.along[downhill], around.across[downhill],
                )
                val fromChannel = around.bendDistance

                // **How much water this reach carries where this column stands, not where it started.** A
                // constant order along a reach leaves a headwater the same narrow V all the way to the trunk
                // it joins, so every junction comes out as a star of straight scratches meeting at a point.
                // Growing it towards the *downstream* node's order is what makes a valley widen as it goes
                // and lets tributaries merge into their trunk instead of arriving beside it.
                val gathering = joining + (inflowsTo(downhill, around.flowsTo) - joining) * alongReach

                // The bed under whichever point of the reach this column is nearest to, so the fall the land
                // has along a reach is the fall its river has. Incision is read at the *reach's* own place in
                // the wedge rather than this column's, or a trunk would deepen and shallow as you walked
                // beside it.
                val cutting = incisionAt(reliefScale, gathering)
                val bed = around.height[here] +
                    (around.height[downhill] - around.height[here]) * alongReach - cutting
                // A cirque belongs to the head of a valley, not to whichever reach happens to run nearest —
                // so it is offered whether or not this reach won the ground, and takes its own depth from a
                // headwater's incision rather than from the interpolated one.
                if (joining == 0.0) {
                    val head = glaciation.cirqueAt(
                        distance(along, across, around.along[here], around.across[here]),
                        around.height[here],
                        incisionAt(reliefScale, 0.0),
                    )
                    if (head != null && head < scooped) scooped = head
                }

                val reachable = bed + fromChannel * GULLY_DRAINS_AT
                if (reachable < drainsTo) drainsTo = reachable

                val standing = bed + riseAt(fromChannel, gathering, grade)
                if (standing >= hillslopes) continue
                hillslopes = standing
                // One reach decides both, exactly as a drainage network's does: taking the ground from one
                // and the water from another stands a wall of water wherever two valleys overlap.
                channelY = bed
                carried = gathering
                outOfChannel = fromChannel / (channelHalfWidth * (1.0 + gathering * channelPerOrder))
            }
        }

        // Nothing in the neighbourhood drains, which the lattice makes vanishingly rare and the land itself
        // answers perfectly well.
        if (hillslopes == Double.POSITIVE_INFINITY) {
            return if (describes == FieldYield.WATER) Spans.EMPTY
            else Spans.of(floorY, landHere.roundToInt())
        }

        if (describes == FieldYield.WATER) {
            if (channelY == NO_WATER) return Spans.EMPTY
            // **Water exists only inside a channel**, and that is what makes containment a property rather
            // than a hope: the ground at the channel's rim stands [CHANNEL_FREEBOARD] over the water in it,
            // so a river is bounded by its own banks by arithmetic. Claiming the whole column below the
            // surface instead let the water find any hollow that happened to lie lower — a cirque floor
            // scooped below its own outlet became a lake two hundred blocks across, with a surface that
            // followed the reach's fall rather than lying level, which is a thing that flows.
            if (outOfChannel >= 1.0) return Spans.EMPTY
            val surface = (channelY + waterDepth * (1.0 + carried * incisionPerOrder)).coerceAtMost(landHere)
            return Spans.of(floorY, surface.roundToInt())
        }

        val capped = glaciation.cappedAt(hillslopes, worldX, worldZ)
        val standing = minOf(capped, scooped)
        val gullied = standing - gullyingAt(worldX, worldZ, standing - drainsTo, grade)
        // Never below the bedrock it stands on: a deep enough trunk over a low enough node would otherwise
        // cut past the floor and leave the column empty, which is a hole through the world rather than a
        // valley. Clamped rather than guarded against, so a range may bottom out on its floor and stop.
        val ground = gullied.coerceAtLeast(floorY.toDouble())
        return Spans.of(floorY, ground.roundToInt())
    }

    /**
     * How far the ground has climbed [fromChannel] blocks out from a reach carrying this much: **the channel
     * first, then the valley above its rim.**
     *
     * The rim stands [CHANNEL_FREEBOARD] blocks over the water this reach carries, which is what makes
     * containment a property of the construction rather than something to tune — the ground at the channel's
     * edge is above the surface of the water in it, by arithmetic, for every order.
     */
    private fun riseAt(fromChannel: Double, gathering: Double, grade: Double): Double {
        val rim = brimFor(gathering)
        val channelHalf = channelHalfWidth * (1.0 + gathering * channelPerOrder)
        if (channelHalf <= 0.0) return glaciation.riseAt(fromChannel, gathering, grade)
        if (fromChannel < channelHalf) return rim * (fromChannel / channelHalf)
        return rim + glaciation.riseAt(fromChannel - channelHalf, gathering, grade)
    }

    /** How far over its bed a reach's channel rim stands — its water, plus a bank to hold it. */
    private fun brimFor(gathering: Double): Double =
        waterDepth * (1.0 + gathering * incisionPerOrder) + CHANNEL_FREEBOARD

    /**
     * How far the nearest point of a **bowed** reach lies from this column — the thing that stops a range
     * reading as a lattice.
     *
     * A reach between two lattice nodes is a straight line, and a straight line is inherited by everything
     * derived from it: the channel down it, the trough either side, and the spurs and ridge lines where its
     * hillslope meets its neighbours'. **A domain warp cannot fix that**, and it was tried: bending a
     * segment needs the warp to swing within the segment's own length, and a displacement that large over
     * that short a wavelength folds the frame back through itself. So the reach bows instead — a quadratic
     * through a control point pushed square to the chord — and the warp goes back to fraying edges, which is
     * what it is good at.
     *
     * Walked as [BEND_STEPS] straight chords rather than solved. The distance to a chord is exact and cheap,
     * so the error is only that the curve is polygonal, which at this scale is a meander with a few soft
     * kinks in it rather than an approximation of one.
     *
     * **Fills the scratch rather than returning a pair**, because this is the hot loop and it is called for
     * every drawn reach of every column; the scratch is thread-confined for the same reason the scan is.
     */
    private fun measureBend(
        around: Neighbourhood,
        atAlong: Double,
        atAcross: Double,
        fromAlong: Double,
        fromAcross: Double,
        toAlong: Double,
        toAcross: Double,
        bow: Double,
    ) {
        val runAlong = toAlong - fromAlong
        val runAcross = toAcross - fromAcross
        // Square to the reach, and scaled by its length, so the bow is a shape rather than a distance.
        val controlAlong = (fromAlong + toAlong) / 2.0 - runAcross * bow
        val controlAcross = (fromAcross + toAcross) / 2.0 + runAlong * bow

        var nearest = Double.POSITIVE_INFINITY
        var lastAlong = fromAlong
        var lastAcross = fromAcross
        for (step in 1..BEND_STEPS) {
            val reached = step.toDouble() / BEND_STEPS
            val chordAlong = bowedAt(fromAlong, controlAlong, toAlong, reached)
            val chordAcross = bowedAt(fromAcross, controlAcross, toAcross, reached)
            val within = alongSegment(atAlong, atAcross, lastAlong, lastAcross, chordAlong, chordAcross)
            val toward = distance(
                atAlong, atAcross,
                lastAlong + (chordAlong - lastAlong) * within,
                lastAcross + (chordAcross - lastAcross) * within,
            )
            if (toward < nearest) nearest = toward
            lastAlong = chordAlong
            lastAcross = chordAcross
        }
        around.bendDistance = nearest
    }

    /**
     * How far below the land a channel carrying this much runs.
     *
     * **Scaled by the relief at the *column* rather than at the reach**, which it was not before: reading
     * how near a range a point is now costs a noise sample, and a reach is looked at twenty-five times a
     * column. The difference is that a trunk deepens and shallows a little as you walk beside it instead of
     * being uniform, and since nearness varies over thousands of blocks the change is far too slow to see —
     * the same approximation [Drainage] makes when it caps its water against the column's own land.
     */
    private fun incisionAt(reliefScale: Double, gathering: Double): Double =
        incision * (1.0 + gathering * incisionPerOrder) * reliefScale

    /**
     * How far this column is cut into its own hillside — **the drainage the lattice is too coarse to hold.**
     *
     * A network of one reach per cell leaves every hillslope a clean cone, and where a deep trunk's cone
     * wins over a wide area the ground comes out as a great planar fan. Real hillsides are not planes at
     * that size, because they carry their own rills and spurs all the way down to the scale of a gully; this
     * is that, and treating it as mere texture is what left the fans showing.
     *
     * **Subtractive, like everything else here.** The noise is folded to `-|n|`, so it only ever cuts — which
     * makes gullies with spurs standing between them rather than lumps, and guarantees the ground can never
     * rise above the hillslope envelope it was measured from.
     *
     * It fades out towards a channel, so valley floors stay walkable and river beds stay smooth, and it
     * scales with [grade], so the foreland is not gullied by a mountain's weather.
     */
    private fun gullyingAt(worldX: Int, worldZ: Int, overTheChannel: Double, grade: Double): Double {
        if (roughness <= 0.0) return 0.0
        // **A gully cannot cut below the water it drains into**, and the room it has is how far this ground
        // stands over that water. Without it the gullying digs through a glacial trough's own floor — which
        // is wide and barely rising, so *horizontal* distance says "well clear of the channel" exactly where
        // the vertical truth is the opposite — and the river then stands in ribbons up the hillside.
        val room = (overTheChannel - DEEPEST_RIVER).coerceAtLeast(0.0)
        val lumps = roughnessNoise.getValue(worldX / roughnessStretch, 0.0, worldZ / roughnessStretch)
        val wanted = abs(lumps).coerceAtMost(1.0) * roughness * (grade / hillslopeGrade).coerceIn(0.0, 1.0)
        return minOf(wanted, room)
    }

    /** Where every node of the scan stands in the range's frame, how high it is, and which way it flows. */
    private fun scanAround(cellAlong: Int, cellAcross: Int): Neighbourhood {
        val around = scratch.get()
        // **Cleared, because the buffer is reused.** Only the inner rings are written below, so the border
        // would otherwise keep whichever column's flow directions were last worked out here.
        around.flowsTo.fill(NOWHERE)
        for (row in 0..<SCAN) {
            for (column in 0..<SCAN) {
                val nodeAlong = cellAlong + column - RING
                val nodeAcross = cellAcross + row - RING
                val at = row * SCAN + column
                around.along[at] = nodeAt(nodeAlong, nodeAcross, ALONG_SALT)
                around.across[at] = nodeAt(nodeAcross, nodeAlong, ACROSS_SALT)
                around.height[at] = profile.landAt(around.along[at], around.across[at])
            }
        }
        // Only where a node's own eight neighbours are inside the scan can its flow be known at all.
        for (row in 1..<SCAN - 1) {
            for (column in 1..<SCAN - 1) {
                around.flowsTo[row * SCAN + column] = lowestNeighbourOf(row, column, around.height, SCAN)
            }
        }
        return around
    }

    /**
     * How many streams join here — the stream order, and the whole of what tells a trunk from a headwater.
     * Counted rather than accumulated, for the reason [Drainage] gives: a true Strahler order would need the
     * network walked to its sources, and one step of it distinguishes a confluence from a beginning.
     */
    private fun inflowsTo(here: Int, flowsTo: IntArray): Double {
        val row = here / SCAN
        val column = here % SCAN
        var joining = 0
        for (upRow in -1..1) {
            for (upColumn in -1..1) {
                if (upRow == 0 && upColumn == 0) continue
                if (flowsTo[(row + upRow) * SCAN + (column + upColumn)] == here) joining++
            }
        }
        // **Clamped, because an inflow count is a stand-in for drainage area and not a measure of it.** A
        // hollow that all eight neighbours drain into does not carry eight times a confluence's water, and
        // reading it that way sinks a shaft where a basin belongs.
        return joining.coerceAtMost(MOST_TRIBUTARIES).toDouble()
    }

    private fun nodeAt(cell: Int, otherCell: Int, salt: Int): Double =
        (cell + HALF + cellHash(cell, otherCell, salt) * jitter) * spacing

    private val roughnessNoise = fieldNoise(seed xor ROUGHNESS_SALT, ROUGHNESS_OCTAVE, ROUGHNESS_AMPLITUDES)

    override fun resized(factor: Double, pivotY: Int) = copy(
        floorY = scaledAbout(floorY, factor, pivotY),
        profile = profile.resized(factor, pivotY),
        glaciation = glaciation.resized(factor, pivotY),
        spacing = spacing * factor,
        // The grade is a ratio, so it survives scaling untouched — which is the whole point of resizing a
        // description rather than its output: a bigger range has the same hillsides, further apart.
        incision = incision * factor,
        roughness = roughness * factor,
        roughnessStretch = roughnessStretch * factor,
        waterDepth = waterDepth * factor,
        channelHalfWidth = channelHalfWidth * factor,
    )

    companion object {
        /**
         * How many nodes across the scan is, and how many rings of them draw a reach. **Eleven and two, and
         * neither is a tuning parameter.** Two things set them, and getting either wrong is not a wrong-looking
         * valley but a **seam** — an answer that depends on which cell the question was asked from, which
         * shows as the terrain stepping along straight lines every [spacing] blocks.
         *
         * **Measured, not reasoned:** dropping to one drawn ring puts a hundred-block wall across the landscape,
         * which `MountainRangeCheck` catches in seconds. Do not narrow this to buy time.
         *
         * **How far reaches must be drawn.** [Drainage] needs only its immediate neighbours, because a valley
         * there is bounded by its own half-width and simply stops. A hillslope does not stop: it climbs at
         * [hillslopeGrade] forever, so a reach a long way off with a low enough bed is still the lowest thing
         * over this column. The radius that matters is however far a bed can be undercut and still win —
         * about the depth range of the beds divided by the grade — which for a range with a hundred blocks of
         * bed variation is a few hundred blocks either side of the nearest channel. Two rings covers it with
         * room to spare; one ring did not, and the picture came out faceted into polygons wherever a deep
         * trunk projected its cone past the edge of the scan.
         *
         * **How wide the scan must be to support that.** A drawn reach needs the order of the node it flows
         * *into*, which needs that node's neighbours' flow, which needs their neighbours' heights: two rings
         * of drawn reaches therefore need five rings of scan.
         */
        private const val SCAN = 11
        private const val RING = 5
        private const val DRAWN = 2

        /**
         * The most tributaries a reach is credited with. Beyond about four the count stops standing in for
         * anything — see [inflowsTo].
         */
        internal const val MOST_TRIBUTARIES = 4

        /** No reach wets this column. */
        private const val NO_WATER = Double.NEGATIVE_INFINITY

        private const val HALF = 0.5

        /**
         * Thirty degrees, as a gradient. Bedrock stands nearer thirty-five and soil-mantled ground nearer
         * twenty-eight; this is the middle of the range that real incising mountains sit in, and it is what
         * decides how much relief a given valley spacing can carry.
         */
        const val DEFAULT_HILLSLOPE_GRADE = 0.577

        /** How deep a headwater cuts, and how much each confluence adds. */
        const val DEFAULT_INCISION = 22.0
        const val DEFAULT_INCISION_PER_ORDER = 0.75

        /** How far the flanks are roughened, and how wide one lump of it runs. */
        const val DEFAULT_ROUGHNESS = 5.0
        const val DEFAULT_ROUGHNESS_STRETCH = 2.5

        /**
         * How much room over the water the gullying leaves. **Must clear the highest channel rim any reach
         * builds**, or a gully nicks the bank that was holding the river in and the water runs out of its
         * own channel — which is the whole thing the channel exists to prevent.
         */
        internal const val DEEPEST_RIVER = 20.0

        /**
         * How steeply a gully has to fall to reach a channel, as a gradient on the distance to it. **The
         * gradient is what makes the answer continuous**, and it is the same reason the height envelope is:
         * a quantity minimised over the reaches in a *local* scan is only well defined if distance penalises
         * the far ones, or a low bed entering the scan drops it by however much it was lower.
         *
         * Read as a plain minimum of beds it did exactly that — a thirteen-block wall ruled across a
         * hillside, hundreds of blocks from the trunk that caused it.
         */
        private const val GULLY_DRAINS_AT = 0.2

        /** Shallow: an alpine river you can wade, deepening as it gathers. */
        const val DEFAULT_WATER_DEPTH = 3.0

        /** Half a headwater's channel, and how much each stream gathered widens it. */
        const val DEFAULT_CHANNEL_HALF_WIDTH = 7.0
        const val DEFAULT_CHANNEL_PER_ORDER = 0.9

        /** How far the channel's rim stands over the water in it. Enough of a bank to hold a river in. */
        private const val CHANNEL_FREEBOARD = 6.0

        /**
         * How far a reach bows, as a share of its length. A quadratic's deviation is half its control
         * offset, so this puts a valley a sixth of its own length off the straight line between its ends.
         */
        const val DEFAULT_REACH_BOW = 0.34

        /** How many chords a bow is walked as. Enough to read as a curve, few enough to stay cheap. */
        private const val BEND_STEPS = 5

        /** So which way a reach bends is decorrelated from where its node sits. */
        private const val BOW_SALT = 0x3_B0DE

        /** A point along a quadratic through [control], at [reached] of the way from [from] to [to]. */
        private fun bowedAt(from: Double, control: Double, to: Double, reached: Double): Double {
            val left = 1.0 - reached
            return left * left * from + 2.0 * left * reached * control + reached * reached * to
        }

        // Gully-scale and down: with a stretch of a few blocks the coarsest of these runs to a hundred or so
        // blocks and the finest to a couple of dozen, which is the band the lattice cannot reach.
        private const val ROUGHNESS_OCTAVE = -4
        private val ROUGHNESS_AMPLITUDES = listOf(1.0, 0.6, 0.35, 0.2)
        private const val ROUGHNESS_SALT = 0x20_0F0FL

        // Separates the two axes' jitters within one cell; the mix itself is [cellHash]'s.
        private const val ALONG_SALT = 0x1_A106
        private const val ACROSS_SALT = 0x2_AC05

        val CODEC: MapCodec<MountainRange> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.INT.fieldOf("floor_y").forGetter(MountainRange::floorY),
                RangeProfile.MAP_CODEC.forGetter(MountainRange::profile),
                Glaciation.MAP_CODEC.forGetter(MountainRange::glaciation),
                Codec.DOUBLE.fieldOf("spacing").forGetter(MountainRange::spacing),
                Codec.DOUBLE.fieldOf("jitter").forGetter(MountainRange::jitter),
                Codec.LONG.fieldOf("seed").forGetter(MountainRange::seed),
                Codec.DOUBLE.optionalFieldOf("hillslope_grade", DEFAULT_HILLSLOPE_GRADE)
                    .forGetter(MountainRange::hillslopeGrade),
                Codec.DOUBLE.optionalFieldOf("incision", DEFAULT_INCISION).forGetter(MountainRange::incision),
                Codec.DOUBLE.optionalFieldOf("incision_per_order", DEFAULT_INCISION_PER_ORDER)
                    .forGetter(MountainRange::incisionPerOrder),
                Codec.DOUBLE.optionalFieldOf("roughness", DEFAULT_ROUGHNESS).forGetter(MountainRange::roughness),
                Codec.DOUBLE.optionalFieldOf("roughness_stretch", DEFAULT_ROUGHNESS_STRETCH)
                    .forGetter(MountainRange::roughnessStretch),
                Codec.DOUBLE.optionalFieldOf("water_depth", DEFAULT_WATER_DEPTH)
                    .forGetter(MountainRange::waterDepth),
                Codec.DOUBLE.optionalFieldOf("channel_half_width", DEFAULT_CHANNEL_HALF_WIDTH)
                    .forGetter(MountainRange::channelHalfWidth),
                Codec.DOUBLE.optionalFieldOf("channel_per_order", DEFAULT_CHANNEL_PER_ORDER)
                    .forGetter(MountainRange::channelPerOrder),
                Codec.DOUBLE.optionalFieldOf("reach_bow", DEFAULT_REACH_BOW)
                    .forGetter(MountainRange::reachBow),
                FieldYield.CODEC.optionalFieldOf("describes", FieldYield.GROUND).forGetter(MountainRange::describes),
            ).apply(instance, ::MountainRange)
        }
    }
}

/**
 * Where the ranges lie and how they rise — the plan of the whole country, and the cross-section of one
 * range in it.
 *
 * **The mountains stand along a network of lines, not across a belt.** One wedge makes one range with a
 * foreland either side, and every acre of it is either mountain or approach — walk anywhere and you are on
 * a slope. What a mountain country actually looks like is *cells*: long ranges that run for tens of
 * kilometres and occasionally meet, with broad basins of gently rolling ground between them.
 *
 * The network is the **zero contour of a noise**, which costs nothing at all: `1 - |n|` is one wherever the
 * noise crosses zero and falls away either side, and the zero contours of a smooth field are exactly a set
 * of long sinuous closed curves enclosing irregular cells. So [nearnessAt] is the whole plan — one on a
 * range axis, nought out in a basin — and it is read from the *same* sample the land already needed. What
 * would have been a Voronoi lattice with its own hash scan is a change of formula.
 *
 * Every range still has its own cross-section, and it is still a **critical-taper wedge**: [landFrom] and
 * [reliefScaleFrom] both ramp with nearness together, so a range's edge is lower *and* gentler *and*
 * rounder at once. **A foothill is the toe of the wedge**, and now there is one at both sides of every
 * range in the network rather than at the two ends of a single belt.
 *
 * The grain of the noise is drawn out along the strike ([grainAlong] against [grainAcross]), which is what
 * makes the cells long rather than round — so the ranges run, as real orogens do, rather than forming a
 * honeycomb.
 */
data class RangeProfile(
    /** Which way the ranges tend to run, in radians, zero along +Z and a quarter turn along +X. */
    val bearing: Double,
    /** The floor of a basin — where the country sits when it is as far from a mountain as it gets. */
    val basinY: Int,
    /** The ground a range's crest is built on. Not the summit height; the hillslopes and [Glaciation] add. */
    val crestY: Int,
    /**
     * How late the climb out of a basin comes on, as an exponent on nearness. **One is a straight ramp from
     * basin floor to crest, and a straight ramp is the bug this replaced**: it leaves the whole width of a
     * cell at some intermediate height and no basin floor to speak of. Above one the ground stays down
     * across the middle of a cell and does its rising near the range, which is what a basin looks like.
     */
    val riseShape: Double,
    /**
     * How far off zero the noise may stray and still be a range — **the dial that sets how much of the
     * country is mountain.** Small makes narrow ranges with wide basins between; large fills the cells in
     * until it is a belt again.
     */
    val rangeThreshold: Double,
    /**
     * How sharply a range's ground gives way to its basin. Above one the flanks fall off quickly and the
     * basins are broad and flat; at one the whole cell is a slope.
     */
    val rangeSharpness: Double,
    /** Blocks per unit of network noise across the strike — how far it is from one range to the next. */
    val grainAcross: Double,
    /** And along it. **Larger than [grainAcross]**, which is what draws the cells out into long ranges. */
    val grainAlong: Double,
    /**
     * How far the whole frame is warped sideways, in blocks — **the only thing that bends a reach.**
     *
     * A reach is a straight segment between two lattice nodes, and everything derived from one inherits
     * that: the channel down it, the trough either side, and the spurs and ridge lines where its hillslope
     * meets its neighbours'. A range built with no warp is a lattice of straight lines meeting at angles,
     * and it reads as one however good the cross-sections are.
     *
     * Warping the *question* rather than bending each reach is what makes this affordable — two noise
     * samples for the whole column, against one per reach for nine or twenty-five of them — and it is also
     * what keeps everything in agreement, since the land, the water and the wedge are all read at the same
     * warped point.
     */
    val meander: Double,
    /**
     * Over what distance the warp swings that far.
     *
     * **Independent of [meander], and it has to be.** Derived from it, the two moved together and their
     * *ratio* — which is what actually decides how sharply the frame is sheared — could never change: a
     * bigger meander bought a proportionally longer wavelength and bent a reach no further. Held apart, the
     * ratio is a thing to choose, and the reaches can be bent as far as folding allows.
     */
    val warpStretch: Double,
    val seed: Long,
) {
    /** How far across the prevailing strike this point lies. No arc: the network wanders on its own. */
    fun acrossStrikeAt(atX: Double, atZ: Double): Double = acrossBearing(bearing, atX, atZ)

    /**
     * **How much of a range there is here** — one on an axis, nought out in a basin, and the single number
     * the whole plan is made of.
     *
     * `1 - |n|` puts the axes on the noise's zero contours, which for a smooth field are long sinuous closed
     * curves enclosing irregular cells: exactly ranges that run, occasionally meet, and have basins between
     * them. [rangeThreshold] is how far off zero still counts, and so how wide a range is; the sign of the
     * noise is thrown away, which is why each range has two flanks rather than one.
     */
    fun nearnessAt(along: Double, across: Double): Double {
        val network = grainNoise.getValue(along / stretchAlong, 0.0, across / stretchAcross)
        val offAxis = (abs(network) / rangeThreshold.coerceAtLeast(SMALLEST_SHARE)).coerceIn(0.0, 1.0)
        return (1.0 - offAxis).pow(rangeSharpness)
    }

    /**
     * The land a node stands on, given how near a range axis it is: **one curve from the floor of a basin to
     * the crest of a range**, and the shape of that curve is [riseShape].
     *
     * It was two terms — a crest rising with nearness and a basin sagging as it fell — and they fought. The
     * sag was given an exponent below one, so a fifth of the way out from a range it had reached only
     * two-thirds of its depth while the crest was still contributing a tenth of its height, and the ground
     * between basin and mountain sat at some middling elevation across a great deal of the map. One curve
     * cannot disagree with itself.
     */
    fun landFrom(nearness: Double): Double = basinY + (crestY - basinY) * nearness.pow(riseShape)

    /** The two together, which is what the node scan wants — one sample, both answers. */
    fun landAt(along: Double, across: Double): Double = landFrom(nearnessAt(along, across))

    /**
     * How hard the ground is being worked here, nought out in a basin and one on a range axis. It scales the
     * relief, the incision, the gullying and the hillslope angle alike, which is what makes the passage from
     * basin to foothill to mountain one fact about the country rather than four separate ones.
     *
     * **Floored rather than allowed to reach zero**: a basin with no relief at all has no streams either,
     * and comes out as a plane tiled from the lattice rather than as a plain with rivers in it.
     */
    fun reliefScaleFrom(nearness: Double): Double = BASIN_RELIEF + (1.0 - BASIN_RELIEF) * nearness

    fun warpedX(worldX: Int, worldZ: Int): Double =
        warpNoise.getValue(worldX / stretchWarp, 0.0, worldZ / stretchWarp) * meander

    fun warpedZ(worldX: Int, worldZ: Int): Double =
        warpNoise.getValue(worldZ / stretchWarp, WARP_PLANE, worldX / stretchWarp) * meander

    private val grainNoise = fieldNoise(seed, GRAIN_OCTAVE, GRAIN_AMPLITUDES)
    private val warpNoise = fieldNoise(seed xor WARP_SALT, WARP_OCTAVE, WARP_AMPLITUDES)

    private val stretchAcross = grainAcross.coerceAtLeast(SMALLEST_STRETCH)
    private val stretchAlong = grainAlong.coerceAtLeast(SMALLEST_STRETCH)
    private val stretchWarp = warpStretch.coerceAtLeast(SMALLEST_STRETCH)

    fun resized(factor: Double, pivotY: Int) = copy(
        basinY = scaledAbout(basinY, factor, pivotY),
        crestY = scaledAbout(crestY, factor, pivotY),
        grainAcross = grainAcross * factor,
        grainAlong = grainAlong * factor,
        meander = meander * factor,
        warpStretch = warpStretch * factor,
    )

    companion object {
        private const val SMALLEST_SHARE = 1.0e-6

        /**
         * What is left of the relief out in a basin — enough for the plain to roll and to carry its own
         * streams, and far too little for it to read as anything but flat against the range beyond it.
         */
        private const val BASIN_RELIEF = 0.22

        // The network's own scale: how far it is from one range to the next across the strike, and how far
        // along it before the cells close. **Two octaves, not three.** This is only the scaffold the drainage
        // is derived from — what a player sees is built back up from the channels — so a third octave costs a
        // third of the whole field's running time to perturb where the water goes by a few blocks.
        private const val GRAIN_OCTAVE = -6
        private val GRAIN_AMPLITUDES = listOf(1.0, 0.5)

        /**
         * Three octaves, so the frame bends at a reach's own scale *and* below it: one sweep carries a whole
         * valley round, the next puts a kink in it, the finest frays its walls. One octave gives an arc, and
         * an arc is only a straight line that has been turned.
         *
         * **How hard it can be pushed is a ratio, not an amplitude.** Displacement over wavelength much past
         * about a seventh starts to fold the frame back through itself, which is not a bend but a place where
         * the world stops making sense. Each octave here sits comfortably under that.
         */
        private const val WARP_OCTAVE = -4
        private val WARP_AMPLITUDES = listOf(1.0, 0.55, 0.3)

        /** The two components are read on different planes, or the warp is a diagonal shear rather than a bend. */
        private const val WARP_PLANE = 512.0
        private const val WARP_SALT = 0x3E_11DL

        val MAP_CODEC: MapCodec<RangeProfile> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.DOUBLE.fieldOf("bearing").forGetter(RangeProfile::bearing),
                Codec.INT.fieldOf("basin_y").forGetter(RangeProfile::basinY),
                Codec.INT.fieldOf("crest_y").forGetter(RangeProfile::crestY),
                Codec.DOUBLE.fieldOf("rise_shape").forGetter(RangeProfile::riseShape),
                Codec.DOUBLE.fieldOf("range_threshold").forGetter(RangeProfile::rangeThreshold),
                Codec.DOUBLE.fieldOf("range_sharpness").forGetter(RangeProfile::rangeSharpness),
                Codec.DOUBLE.fieldOf("grain_across").forGetter(RangeProfile::grainAcross),
                Codec.DOUBLE.fieldOf("grain_along").forGetter(RangeProfile::grainAlong),
                Codec.DOUBLE.fieldOf("meander").forGetter(RangeProfile::meander),
                Codec.DOUBLE.fieldOf("warp_stretch").forGetter(RangeProfile::warpStretch),
                Codec.LONG.fieldOf("profile_seed").forGetter(RangeProfile::seed),
            ).apply(instance, ::RangeProfile)
        }
    }
}

/**
 * What the ice did — and the difference between a range that reads as *the Alps* and one that reads as
 * mountains in general.
 *
 * Three effects, each a small change to a hillslope that is otherwise a plane:
 *
 * - **Troughs.** A glacier fills its valley wall to wall and cuts across the bottom rather than down the
 *   middle, so a glaciated valley is a U where a river's is a V. Only reaches that gathered enough water
 *   to carry a glacier get one ([glacialOrder]), which is why the trunks are U-shaped and the side streams
 *   above them still V — and why the tributaries are left hanging, since the trunk was deepened and they
 *   were not.
 * - **Cirques.** Ice biting backwards into the crest at the head of a valley leaves an armchair hollow with
 *   a headwall steeper than any hillslope. Nothing places a peak: **where three cirques eat back into the
 *   same mass, what is left between them is a horn**, and a ridge between two of them is an arête.
 * - **The snowline.** Glacial erosion is fiercest just above the equilibrium line, so a range's summits pile
 *   up near it and few stand far above — the "glacial buzzsaw". [snowlineY] with [summitScatter] is what
 *   keeps a crest from being a uniform sawtooth and lets a handful of massifs tower over the rest.
 */
data class Glaciation(
    /** Where the summits pile up — the equilibrium line, and the cap the hillslopes are cut off at. */
    val snowlineY: Int,
    /** How far individual massifs stand above or below it, in blocks. */
    val summitScatter: Double,
    /** How wide one massif's worth of that is — hundreds of blocks, or every peak is its own height. */
    val summitStretch: Double,
    /** Over how many blocks a summit rounds over into the cap rather than being planed flat by it. */
    val summitRounding: Double,
    /**
     * Half the flat floor **every** glaciated reach has, before it has gathered anything — the width a
     * valley is even at its head.
     *
     * This is what decides how much of a range is *floor* rather than *flank*, and so how much room there
     * is to stand back in. With none of it every valley is a notch and the ground is hillside almost
     * everywhere, which reads as a mass of peaks with no space between them however far apart the ridges
     * are put.
     */
    val troughBase: Double,
    /**
     * And how much more each stream gathered past [glacialOrder] buys, in blocks.
     *
     * **Measured from the threshold rather than from nothing, and that is what keeps the profile
     * continuous** where [troughBase] is zero: a reach that only just carries a glacier gets a floor of no
     * width, which *is* a V, so the two cross-sections meet instead of one replacing the other partway
     * down a reach.
     */
    val troughPerOrder: Double,
    /**
     * How the trough floor curves out to its shoulder. Two is a parabola, and glaciated cross-sections fit
     * powers between about one and a half and two — below one it is a spike and above two a box.
     */
    val troughPower: Double,
    /** How much must be gathering before a valley carried a glacier at all. Zero glaciates the headwaters. */
    val glacialOrder: Double,
    /** How far a cirque reaches out from the head of its valley. */
    val cirqueRadius: Double,
    /** How far below its channel bed a cirque floor is scooped — the overdeepening a tarn stands in. */
    val cirqueDeepening: Double,
    /** How much steeper than a hillslope a headwall stands, as a multiple. One leaves no headwall at all. */
    val headwallSteepening: Double,
    /** The lowest land a cirque forms on. Below the old snowline there were no glaciers to cut one. */
    val cirqueLowestY: Int,
    val seed: Long,
) {
    /**
     * How far the ground has climbed [fromChannel] blocks out from a reach of this order — **the whole of the
     * landform's shape**, since the ground is the lowest of these over every reach nearby.
     *
     * A V is [grade] all the way out. A U is a power curve to the shoulder and then [grade] above it, and
     * the shoulder's rise is set rather than chosen: it is what makes the curve arrive at the hillslope's
     * own gradient, so the trough joins its flanks smoothly instead of creasing at the lip.
     *
     * **Continuous in [gathering] as well as in [fromChannel]**, which is the harder half and was got wrong
     * once. How much a reach carries grows *along* the reach, so a threshold that switched a V for a U would
     * fire partway down one — and the two profiles differ by the trough's whole depth at the shoulder, so
     * the ground fell off a ten-block wall ruled across the valley. Growing the floor out from zero at the
     * threshold means there is no second profile to switch to.
     */
    fun riseAt(fromChannel: Double, gathering: Double, grade: Double): Double {
        val shoulder = troughBase + troughPerOrder * (gathering - glacialOrder).coerceAtLeast(0.0)
        val carriedAGlacier = shoulder > 0.0 && troughPower > 0.0
        if (!carriedAGlacier) return fromChannel * grade
        val atTheShoulder = grade * shoulder / troughPower
        if (fromChannel >= shoulder) return atTheShoulder + (fromChannel - shoulder) * grade
        return atTheShoulder * (fromChannel / shoulder).pow(troughPower)
    }

    /**
     * The floor of the cirque at a headwater standing at [headY], [fromHead] blocks out — or null where
     * there is no cirque here, either because this is too far from the head or because the head is below
     * the height ice ever reached.
     */
    fun cirqueAt(fromHead: Double, headY: Double, incision: Double): Double? {
        if (fromHead >= cirqueRadius || headY < cirqueLowestY) return null
        val floorY = headY - incision - cirqueDeepening
        // Steeper than the hillslope it is cut into, which is what makes it a headwall rather than a dip.
        val out = (fromHead / cirqueRadius).coerceIn(0.0, 1.0)
        return floorY + (cirqueDeepening + cirqueRadius * HEADWALL_GRADE * headwallSteepening) * out * out
    }

    /**
     * [standing], cut back to the height summits pile up at here.
     *
     * **Rounded rather than planed, and that is not a polish.** Taking the plain minimum of a hillslope and
     * a cap leaves every summit that reaches the cap dead flat across however far it overshot — a mesa, and
     * the one shape an alpine crest must not have. Blending the two over [summitRounding] blocks turns the
     * same cut into a dome, which is what a summit worn down from every side actually looks like; ridges
     * that never reach the cap are untouched either way.
     */
    fun cappedAt(standing: Double, worldX: Int, worldZ: Int): Double {
        val cap = capAt(worldX, worldZ)
        val lower = minOf(standing, cap)
        val apart = abs(standing - cap)
        if (apart >= summitRounding || summitRounding <= 0.0) return lower
        val overlap = summitRounding - apart
        return lower - overlap * overlap / (4.0 * summitRounding)
    }

    /** The height summits are cut off at here — the buzzsaw, wandering by massif rather than by peak. */
    fun capAt(worldX: Int, worldZ: Int): Double =
        snowlineY + summitNoise.getValue(worldX / stretchSummit, 0.0, worldZ / stretchSummit) * summitScatter

    private val summitNoise = fieldNoise(seed, SUMMIT_OCTAVE, SUMMIT_AMPLITUDES)
    private val stretchSummit = summitStretch.coerceAtLeast(SMALLEST_STRETCH)

    fun resized(factor: Double, pivotY: Int) = copy(
        snowlineY = scaledAbout(snowlineY, factor, pivotY),
        summitScatter = summitScatter * factor,
        summitStretch = summitStretch * factor,
        summitRounding = summitRounding * factor,
        troughBase = troughBase * factor,
        troughPerOrder = troughPerOrder * factor,
        cirqueRadius = cirqueRadius * factor,
        cirqueDeepening = cirqueDeepening * factor,
        cirqueLowestY = scaledAbout(cirqueLowestY, factor, pivotY),
    )

    companion object {
        /** The gradient a headwall is measured against, before [headwallSteepening] multiplies it. */
        private const val HEADWALL_GRADE = MountainRange.DEFAULT_HILLSLOPE_GRADE

        // Massif-scale and multi-octave, so a capped summit is a broad dome rather than a mesa top: the
        // cap itself has to have shape, or every peak it bites is planed to the same table.
        private const val SUMMIT_OCTAVE = -6
        private val SUMMIT_AMPLITUDES = listOf(1.0, 0.5, 0.25)

        val MAP_CODEC: MapCodec<Glaciation> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.INT.fieldOf("snowline_y").forGetter(Glaciation::snowlineY),
                Codec.DOUBLE.fieldOf("summit_scatter").forGetter(Glaciation::summitScatter),
                Codec.DOUBLE.fieldOf("summit_stretch").forGetter(Glaciation::summitStretch),
                Codec.DOUBLE.fieldOf("summit_rounding").forGetter(Glaciation::summitRounding),
                Codec.DOUBLE.fieldOf("trough_base").forGetter(Glaciation::troughBase),
                Codec.DOUBLE.fieldOf("trough_per_order").forGetter(Glaciation::troughPerOrder),
                Codec.DOUBLE.fieldOf("trough_power").forGetter(Glaciation::troughPower),
                Codec.DOUBLE.fieldOf("glacial_order").forGetter(Glaciation::glacialOrder),
                Codec.DOUBLE.fieldOf("cirque_radius").forGetter(Glaciation::cirqueRadius),
                Codec.DOUBLE.fieldOf("cirque_deepening").forGetter(Glaciation::cirqueDeepening),
                Codec.DOUBLE.fieldOf("headwall_steepening").forGetter(Glaciation::headwallSteepening),
                Codec.INT.fieldOf("cirque_lowest_y").forGetter(Glaciation::cirqueLowestY),
                Codec.LONG.fieldOf("glaciation_seed").forGetter(Glaciation::seed),
            ).apply(instance, ::Glaciation)
        }
    }
}
