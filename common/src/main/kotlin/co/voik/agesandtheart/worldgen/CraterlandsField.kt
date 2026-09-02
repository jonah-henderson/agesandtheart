package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.worldgen.carver.Weathering
import co.voik.agesandtheart.worldgen.field.Chance
import co.voik.agesandtheart.worldgen.field.Choose
import co.voik.agesandtheart.worldgen.field.Cone
import co.voik.agesandtheart.worldgen.field.Cylinder
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Ellipsoid
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.Intersect
import co.voik.agesandtheart.worldgen.field.Isle
import co.voik.agesandtheart.worldgen.field.NoiseHeightmap
import co.voik.agesandtheart.worldgen.field.Radial
import co.voik.agesandtheart.worldgen.field.Scatter
import co.voik.agesandtheart.worldgen.field.Spans
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Undulated
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.Variation
import co.voik.agesandtheart.worldgen.field.Weathered
import net.minecraft.core.Direction
import kotlin.math.roundToInt

/**
 * Crater country: one colossal impact structure centred on where the writer arrives, and an ordinary
 * cratered plain beyond its reach.
 *
 * **The one landform here with a centre.** Every other world in this toolkit is either the same
 * everywhere ([NoiseField], [PillarField], [IslandsField], [AlpsField]) or the same along a bearing
 * ([CanyonField], [CliffField]), so walking away from the origin tells you nothing. Here distance from
 * the origin is the only thing that matters: you arrive on the central peak, in a round sea, inside a
 * ring wall, and the country relaxes back into plain over the next two kilometres.
 *
 * **And the one built by removal at a single stroke** rather than by erosion. `alps` raises ground up
 * from its own drainage and the canyons cut theirs away over time; this excavates once and then rebounds.
 * The order is the geology and it is the shape of [world]: the peak stands *because* it came up after the
 * hole was dug, so putting it inside the cut would only bury it.
 *
 * Three constructions carry the whole thing, and none of them is a new node.
 *
 * - **An annulus is [Subtract] of two [Cylinder]s** sharing a vertical extent, so the inner one takes the
 *   whole column out of the middle. [Intersect] that with a broad [Cone] and the result is a ridge with a
 *   sheer inner face and a gentle outer apron, which is what a crater rim is. The same self-validation as
 *   a convex polyhedron being an `Intersect` of half-spaces.
 * - **A crater field is two [Instanced] nodes, not one.** See [Craters].
 * - **Everything the impact raised is unioned over the cratered plain**, rather than into it. See [world].
 *
 * And what stops all of that reading as arithmetic — because analytically it is — is the pair at the end:
 * a noisy ceiling subtracted from the world ([scalloping]), and a weathering whose resistance is read in
 * **world** coordinates, so a perfectly circular crest is worked differently at every bearing and stops
 * being a circle without any node knowing it was one.
 */
object CraterlandsField {

    /**
     * How far a sentence has bent this landform along each of `Terrain`'s three shared axes, from −1 to 1,
     * or **null on an axis no word bounded** — which means this landform's own tuning rather than a draw.
     *
     * Each is read here into several numbers at once, which is the point of it being an axis rather than a
     * parameter per dial. [relief] moves the rim, the bowl, the outer scarps *and* how often a peak ring is
     * drawn; nothing outside can address those separately, and nothing inside has to be told what a block
     * is by the vocabulary.
     */
    data class Steer(val wear: Double? = null, val relief: Double? = null, val spacing: Double? = null) {
        companion object {
            /** No word bent anything: every dial reads as this landform tuned it. */
            val UNSAID = Steer()
        }
    }

    /**
     * What a steer becomes in this landform's own units — [atLeast] at −1, [atMost] at 1, and [tuned]
     * where nobody said anything at all.
     *
     * **The unsteered case is not the midpoint on purpose.** A tuned value is where this landform was
     * walked and judged; putting it at the middle of a range would silently retune every Age nobody wrote
     * a word about, and the range's ends exist to be reachable rather than to be averaged.
     */
    private fun dialled(steer: Double?, tuned: Double, atLeast: Double, atMost: Double): Double {
        if (steer == null) return tuned
        val alongTheAxis = (steer - Span.NATURAL_LEAST) / (Span.NATURAL_MOST - Span.NATURAL_LEAST)
        return atLeast + alongTheAxis * (atMost - atLeast)
    }

    /**
     * **Four layers laid over one another, oldest first**, which is the one arrangement that keeps every
     * column in one piece.
     *
     * A crater bowl is an ellipsoid centred on its own rim crest, so at the crater's *edge* the cut it
     * makes is razor thin — which means the crater field can only be laid over ground within a few blocks
     * of the plain. Put the blanket and the scarps in the same union as the plain and every crater that
     * lands on one takes a lens out of its middle and leaves the surface standing on nothing. Union them
     * *over* the cratered plain instead and the raised ground simply buries the craters it covers, which
     * is what a blanket of ejecta does to the country it lands on anyway.
     */
    fun world(steer: Steer = Steer.UNSAID, salt: Long = 0L): TerrainField =
        Weathered.sculpting(bareWorld(steer, salt), Weathering.CRATERLANDS, SHELTER_REACH)

    /** The structure before the weather reaches it — the previewer's other half, and the checks'. */
    fun bareWorld(steer: Steer = Steer.UNSAID, salt: Long = 0L): TerrainField {
        val secondaries = craters(steer, salt)
        val hole = excavation(steer, salt)
        val laid = Union(
            listOf(
                // The old country and everything that has cratered it, with the basin taken out of it.
                Subtract(
                    Union(listOf(plain(salt), secondaries.rims)),
                    Union(listOf(hole, secondaries.bowls)),
                ),
                // What the impact threw out, with the same hole taken out of it.
                Subtract(apron(), hole),
                outerRings(steer, salt),
                rebound(steer, salt),
            ),
        )
        return Subtract(laid, scalloping(steer, salt))
    }

    /**
     * What stops the rim being a circle: a noisy ceiling laid across the world, shaving everything that
     * reaches above it.
     *
     * **The only thing in this landform tall enough to meet it is the rim and the top of the blanket**, so
     * it needs no mask — the central peak, the outer scarps, the craters and the plain all stand below its
     * lowest dip. Where the noise crests the rim keeps its full height; where it dips the crest is cut
     * down to it, which scallops the crest line and opens passes through the wall at irregular bearings.
     * Those are worth having for their own sake: the inner face falls better than two blocks for every one
     * it runs, and without a notch there is no way into the basin that is not a climb.
     *
     * A hanging [NoiseHeightmap] — [NoiseHeightmap.baseY] under its [NoiseHeightmap.flatY], so the rock
     * runs *upward* from the noisy surface. Subtracting it is the cheapest cut in the file: one noise
     * sample per column, against the band walk that any 3D answer would cost.
     */
    private fun scalloping(steer: Steer, salt: Long): TerrainField = NoiseHeightmap(
        seed = SCALLOP_SEED xor salt,
        // Broad scallops at about 128 blocks with detail down to 16, so the notches are passes rather
        // than gullies and the crest between them is never a straight run.
        firstOctave = -7,
        amplitudes = listOf(1.0, 0.55, 0.3, 0.15),
        scaleX = 1.0,
        scaleZ = 1.0,
        baseY = SCALLOP_MEAN_Y,
        relief = dialled(steer.wear, SCALLOP_RELIEF, LEAST_SCALLOP, MOST_SCALLOP),
        flatY = ABOVE_ANY_ROCK,
    )

    /** The country the basin was struck into, and what everything else here measures itself against. */
    private fun plain(salt: Long): TerrainField = NoiseHeightmap(
        seed = PLAIN_SEED xor salt,
        // Detail at roughly 128, 64 and 32 blocks, the same three the hills use.
        firstOctave = -7,
        amplitudes = listOf(1.0, 0.5, 0.25),
        scaleX = 1.0,
        scaleZ = 1.0,
        baseY = PLAIN_Y,
        relief = PLAIN_RELIEF,
        flatY = WORLD_FLOOR,
    )

    /**
     * The rim and the ejecta blanket: one cone from the crest at [RIM_CREST_Y] back down to the plain at
     * [APRON_RADIUS]. It goes on rising inward, and everything inside [RIM_CREST_RADIUS] is taken out
     * again by [excavation] — so the rim's inner face is the *bowl's* wall rather than a face of its own,
     * which is where its steepness comes from and why nothing tunes it.
     */
    private fun apron(): Cone = slopeFrom(WORLD_FLOOR, RIM_CREST_RADIUS, RIM_CREST_Y, APRON_RADIUS)

    /**
     * The hole, **with a floor rather than a surface of revolution.**
     *
     * The [Ellipsoid] is the basin: centred at the crest, so its equator plane *is* the rim — at the crest
     * radius it cuts nothing and a little inside it cuts everything, which puts the wall at the crest with
     * no arithmetic spent on placing it. Its top has to clear [apron]'s apex ([BOWL_DEPTH] against a cone
     * of about 358) or the middle of the basin comes out with that cone still standing in it, which
     * `CraterlandsCheck` asserts rather than leaving to the render.
     *
     * **What is intersected with it is where the floor stops being an ellipsoid.** A hanging
     * [NoiseHeightmap] raises the *bottom* of the cut wherever its surface stands above the smooth floor,
     * so rock is left behind as knolls and shoals — and since the ellipsoid's floor climbs steeply with
     * radius, the roughening concentrates itself on the deep middle and fades out up the wall without
     * anything having to say so. The same trick as [scalloping] read the other way up: there a noisy
     * ceiling takes rock off the top, here a noisy floor leaves rock at the bottom.
     */
    private fun excavation(steer: Steer, salt: Long): TerrainField = Undulated(
        base = Intersect(
            listOf(
                Ellipsoid(
                    centerX = 0,
                    centerY = RIM_CREST_Y,
                    centerZ = 0,
                    radiusXZ = RIM_CREST_RADIUS,
                    radiusY = dialled(steer.relief, BOWL_DEPTH, LEAST_BOWL_DEPTH, MOST_BOWL_DEPTH),
                ),
                NoiseHeightmap(
                    seed = BASIN_FLOOR_SEED xor salt,
                    // Shoal-scale rather than landform-scale: banks a hundred blocks across with detail
                    // on them, so the floor reads as ground under water rather than as one tilted sheet.
                    firstOctave = -6,
                    amplitudes = listOf(1.0, 0.5, 0.25),
                    scaleX = 1.0,
                    scaleZ = 1.0,
                    baseY = BASIN_FLOOR_MEAN_Y,
                    relief = BASIN_FLOOR_RELIEF,
                    flatY = ABOVE_ANY_ROCK,
                ),
            ),
        ),
        seed = UNDULATION_SEED xor salt,
        // Swells about a hundred and twenty blocks across with half that on top, so the basin gets a
        // handful of them rather than one tilt or a rash of lumps.
        firstOctave = -7,
        amplitudes = listOf(1.0, 0.5),
        scaleX = 1.0,
        scaleZ = 1.0,
        amount = dialled(steer.wear, BASIN_UNDULATION, LEAST_UNDULATION, MOST_UNDULATION),
    )

    /**
     * What came back up after the hole was dug: the central peak, and — in about half of these Ages — a
     * broken ring of massifs standing around it.
     *
     * The peak is **not** drawn for, because arrival happens at the origin and the basin floor there is
     * [BOWL_FLOOR_Y], well under the waterline. A world where the writer sometimes arrives at the bottom
     * of a sea is a world with a coin-flip in it, not a variation.
     */
    private fun rebound(steer: Steer, salt: Long): TerrainField =
        Union(listOf(centralIsland(salt), peakRing(steer, salt)))

    /**
     * The island at the origin — the one place in this Age that is somewhere rather than somewhere-ish,
     * and so the one that most has to not look like a shape.
     *
     * **[Isle] for the coast, a noisy ceiling for the top.** The two halves of an island turned out to
     * want different tools, which is the whole of what the pair with [centralIslandFromNoise] settled:
     *
     * - `Isle` draws a **coast** nothing else here can. Its outline is a noisy radius rather than a
     *   threshold crossing, so it is lobed the whole way round with no arc of anything showing through.
     * - And it draws a **mesa**. Its profile is a beach, a shoulder, and then interior at one height — so
     *   the top comes out flat, which is the one thing the cheap version was better at. Taking the lower
     *   of it and a [NoiseHeightmap] sitting on the plateau cuts that back into summits and saddles,
     *   without reaching the climb that made the coast worth having.
     *
     * **It costs nothing to prefer it.** The worry was that `Isle` is expensive — two noise samples on
     * every column, against a node that is otherwise all closed form. It is behind a [fullHeightDisc] in
     * an [Intersect], which sorts its children cheapest first and stops as soon as one is empty, so
     * outside the island it is never sampled at all. Measured over the whole basin, swapping the two
     * moved the field's cost by less than the run-to-run spread.
     *
     * What it does need is talking out of being an archipelago: a spacing of twenty kilometres so it
     * draws no neighbours, a seabed under the basin's own floor so the slab it lays everywhere never
     * shows, and a shelf steepened threefold or its skirt reaches the far shore.
     */
    fun centralIsland(salt: Long = 0L): TerrainField = Intersect(
        listOf(
            // Asked first, being analytic, so outside the island the isle is never sampled at all.
            fullHeightDisc(ISLE_REACH),
            // **The top, clipped.** `Isle`'s profile is flat once it is up — a beach, a shoulder behind it
            // and then interior at height — which gives an excellent coast and a mesa above it. Taking the
            // lower of it and a noisy ceiling cuts that plateau into summits and saddles. The ceiling sits
            // well above where the shoulder is still climbing, so it reaches only the flat part and the
            // coast that made this worth using is left exactly as the isle drew it.
            NoiseHeightmap(
                seed = ISLAND_CLIP_SEED xor salt,
                firstOctave = -6,
                amplitudes = listOf(1.0, 0.5, 0.25),
                scaleX = 1.0,
                scaleZ = 1.0,
                baseY = ISLAND_CLIP_MEAN_Y,
                relief = ISLAND_CLIP_RELIEF,
                flatY = WORLD_FLOOR,
            ),
            Isle(
                floorY = WORLD_FLOOR,
                // Under the basin's own deepest floor, so the seabed an isle lays everywhere is buried.
                seabedY = ISLAND_BASE_Y,
                shoreY = WATERLINE,
                peakRise = ISLE_PEAK_RISE,
                shoreRadius = ISLE_SHORE_RADIUS,
                radiusVariation = 0.0,
                spacing = ISLE_SPACING,
                jitter = 0.0,
                seed = ISLAND_SEED xor salt,
                relief = ISLE_RELIEF,
                // Half the radius rather than a third. The shoulder is the only part of an isle's profile
                // that is a slope; everything past it is interior at one height, so a short one is what
                // makes it a mesa. Widened, the climb reaches most of the way in and the flat is what is
                // left over rather than the subject.
                shoulder = ISLE_SHOULDER,
                // Three times the archipelago's. At its own the skirt runs 236 blocks out and there is no
                // open water left between the island and the basin's shore.
                shelfSlope = ISLE_SHELF_SLOPE,
            ),
        ),
    )

    /**
     * The island written as **a [Cone] bounding a [NoiseHeightmap]**, taking the lower of the two —
     * **kept as evidence rather than as what generates**, and reached only by the previewer's
     * `craterlands-island-noise` subject. [centralIsland] is what [rebound] uses.
     *
     * It was the cheap answer and it was expected to win: one noise sample against `Isle`'s two, with the
     * cone asked first so outside the island it costs a distance and nothing else. What settled it was the
     * render, and the thing to look at is the **coast**.
     *
     * **A shape bounding a noise field shows through wherever the shape is the tighter of the two.** The
     * noise gives a fine lobed outline where it crosses the waterline first — but round most of the
     * perimeter the cone gets there first, and that arc is a circle, so the island comes out as a disc
     * with bays bitten into one side of it. Raising the noise's mean to push the crossing outward only
     * moves which of the two is binding; it does not stop the cone being a circle when it binds.
     *
     * The profile is the other half of the finding and it goes the other way: this one's *section* is the
     * better of the two — several summits and saddles — where `Isle`'s is a mesa. That is what
     * [ISLAND_CLIP_MEAN_Y] exists to fix, and it is why the two were worth rendering side by side.
     */
    fun centralIslandFromNoise(salt: Long = 0L): TerrainField = Intersect(
        listOf(
            Cone(
                baseX = 0,
                baseZ = 0,
                baseRadius = ISLAND_RADIUS,
                baseY = ISLAND_BASE_Y,
                tipY = ISLAND_CONE_TIP_Y,
            ),
            NoiseHeightmap(
                seed = ISLAND_SEED xor salt,
                // Roughly 64, 32 and 16 blocks against an island a couple of hundred across, so it comes
                // out with a handful of summits and a coast of that many bays.
                firstOctave = -6,
                amplitudes = listOf(1.0, 0.5, 0.25),
                scaleX = 1.0,
                scaleZ = 1.0,
                baseY = ISLAND_MEAN_Y,
                relief = ISLAND_RELIEF,
                flatY = WORLD_FLOOR,
            ),
        ),
    )

    /**
     * A [Radial] ring of massifs around the peak, present or absent for the whole Age.
     *
     * **The rings past the first need no mask, because the ground buries them.** [Radial] places on every
     * ring at a multiple of its spacing, and ring two lands on the rim crest a hundred blocks above
     * anything this template reaches — so the union simply swallows it. Only the rings out past the apron
     * would show, and [Density.radial] fading to nothing at [PEAK_RING_FALLOFF] is what removes those. It
     * fades ring one to about seven massifs of ten in passing, which is the broken arc a peak ring is.
     */
    private fun peakRing(steer: Steer, salt: Long): TerrainField = Chance(
        child = Instanced(
            templates = listOf(
                Cone(
                    baseX = 0,
                    baseZ = 0,
                    baseRadius = PEAK_RADIUS,
                    baseY = PEAK_BASE_Y,
                    tipY = PEAK_TIP_Y,
                ),
            ),
            placement = Radial(
                ringSpacing = PEAK_RING_RADIUS,
                arcSpacing = PEAK_ARC_SPACING,
                jitter = PEAK_JITTER,
                density = Density.radial(
                    atOrigin = 1.0,
                    atEdge = 0.0,
                    falloffRadius = PEAK_RING_FALLOFF,
                ),
            ),
            // Turning a cone buys nothing and costs a draw; sizes are what make an arc read as massifs.
            variation = Variation(
                yawSteps = 1,
                minScale = 0.75,
                maxScale = 1.3,
                scaleSteps = 3,
                pivotY = PEAK_BASE_Y,
            ),
            seed = PEAK_RING_SEED xor salt,
            // Neighbours overlap by about a fifth of their width, and easing the join is what makes them
            // one massif with several summits rather than cones sharing a wall.
            blend = PEAK_BLEND,
        ),
        // Bold relief nearly always rings the peak; subdued relief nearly never does.
        probability = dialled(steer.relief, PEAK_RING_CHANCE, NO_PEAK_RING, ALWAYS_A_PEAK_RING),
        seed = PEAK_RING_DRAW_SEED xor salt,
    )

    /**
     * One or two outer ring scarps, drawn from three.
     *
     * **A constraint across the children, which is why this is [Choose] and not a [Chance] apiece.** Real
     * multiring basins carry a countable number of rings rather than each ring existing independently, and
     * `leastPlaced = 1` is what stops an Age drawing none and coming out as a plain crater.
     *
     * Each is masked to its own annulus and so cuts nothing: an outer ring that excavated as [apron] does
     * would take the main rim away with it, being the wider circle of the two. And each is unioned over
     * the cratered plain rather than into it — see [world].
     */
    private fun outerRings(steer: Steer, salt: Long): TerrainField = Choose(
        alternatives = OUTER_RINGS.map { ring ->
            Choose.Alternative(
                field = ringRidge(
                    ring.crestRadius,
                    dialled(steer.relief, ring.rise.toDouble(), ring.rise * FAINTEST_SCARP, ring.rise * BOLDEST_SCARP).toInt(),
                    ring.width,
                ),
                weight = ring.weight,
            )
        },
        leastPlaced = 1,
        mostPlaced = 2,
        seed = OUTER_RING_SEED xor salt,
    )

    /** Where one outer ring stands, how far it rises over the plain, and how often it is drawn. */
    private data class OuterRing(
        val crestRadius: Double,
        val rise: Int,
        val width: Double,
        val weight: Double,
    )

    /**
     * Nearer rings are both higher and likelier, so the common Age is a basin with one scarp close in and
     * the rare one has a faint circle out on the horizon as well. The innermost stands on the ejecta
     * blanket and has to out-rise it to show at all, which is most of why it is the tallest.
     */
    private val OUTER_RINGS = listOf(
        OuterRing(crestRadius = 380.0, rise = 42, width = 85.0, weight = 3.0),
        OuterRing(crestRadius = 560.0, rise = 27, width = 75.0, weight = 2.0),
        OuterRing(crestRadius = 780.0, rise = 18, width = 65.0, weight = 1.0),
    )

    /**
     * An inward-facing scarp: a ring whose crest stands [rise] over the plain at [crestRadius] and slopes
     * back down to it [width] further out, with nothing at all inside.
     *
     * The annulus is what makes the inner face sheer — the cone would go on climbing inward, and cutting
     * it off at a cylinder wall drops it to whatever the ground beneath is doing.
     */
    private fun ringRidge(crestRadius: Double, rise: Int, width: Double): TerrainField {
        val outerRadius = crestRadius + width
        return Intersect(
            listOf(
                annulus(crestRadius, outerRadius),
                slopeFrom(WORLD_FLOOR, crestRadius, PLAIN_Y + rise, outerRadius),
            ),
        )
    }

    /** Everything between two radii of the origin, at every height — the ring mask. */
    private fun annulus(innerRadius: Double, outerRadius: Double): TerrainField =
        Subtract(fullHeightDisc(outerRadius), fullHeightDisc(innerRadius))

    /**
     * A disc of [radius] reaching further up and down than any world does, so subtracting one from
     * another leaves a clean annulus rather than a ring with a lid or a floor.
     */
    private fun fullHeightDisc(radius: Double): Cylinder = Cylinder(
        axis = Direction.Axis.Y,
        centerX = 0,
        centerY = 0,
        centerZ = 0,
        radius = radius,
        halfLength = Spans.HIGHEST_Y.toDouble(),
    )

    /**
     * A cone standing on [anchorY] whose surface passes through both ([crestRadius], [crestY]) and
     * ([outerRadius], [PLAIN_Y]) — the one piece of arithmetic every slope here is built from.
     *
     * **Anchored well below the plain rather than on it.** A cone based at [PLAIN_Y] exactly would stand
     * as a shelf with daylight under it wherever the plain's own noise dips, since a union takes the
     * higher of two tops and nothing fills the gap between them.
     */
    private fun slopeFrom(anchorY: Int, crestRadius: Double, crestY: Int, outerRadius: Double): Cone {
        val crestAboveAnchor = (crestY - anchorY).toDouble()
        val plainAboveAnchor = (PLAIN_Y - anchorY).toDouble()
        val fallAcrossTheSlope = crestAboveAnchor - plainAboveAnchor
        val slopeWidth = outerRadius - crestRadius
        // Solving `anchor + height · (1 − r / baseRadius)` at both radii at once.
        val height = crestAboveAnchor + crestRadius * fallAcrossTheSlope / slopeWidth
        return Cone(
            baseX = 0,
            baseZ = 0,
            baseRadius = height * slopeWidth / fallAcrossTheSlope,
            baseY = anchorY,
            tipY = anchorY + height.roundToInt(),
        )
    }

    /**
     * The ordinary craters, as the two halves a crater field has to be built from.
     *
     * **A crater cannot be one template.** [Instanced] unions its copies, so a template shaped like a
     * crater would have each rim fill its neighbour's bowl. Splitting them across the top-level
     * [Subtract] — rims into what is solid, bowls into what is taken out of it — is what lets a young
     * crater cut an old rim, and it is why the great rim comes out notched by later impacts for free.
     *
     * **The two halves must draw identically or a bowl lands away from its rim.** Same layout, same seed,
     * the same [Variation] and the templates in the same order, so each instance draws the same numbers in
     * both and picks the same shape at the same size and height. That is the whole coupling, it is why
     * these are built here together rather than by two callers, and why the templates come from one list
     * of [CraterShape] rather than two hand-written lists that could fall out of order.
     * `CraterlandsCheck` asserts the pairing from the far end.
     */
    private data class Craters(val rims: TerrainField, val bowls: TerrainField)

    /**
     * One kind of crater, as the pair of shapes it has to be cut from.
     *
     * The bowl is an [Ellipsoid] centred on the rim crest, so its equator plane is the rim: at
     * [crestRadius] it cuts nothing and a little inside it cuts everything. [bowlReach] is therefore two
     * things at once — the crater's depth going down, and going up what has to clear the rim cone's own
     * apex, which for so broad a taper stands well over the crest.
     */
    private data class CraterShape(val crestRadius: Double, val rimRise: Int, val rimWidth: Double, val bowlReach: Double) {
        private val crestY get() = PLAIN_Y + rimRise

        fun rim(): TerrainField = slopeFrom(CRATER_ANCHOR_Y, crestRadius, crestY, crestRadius + rimWidth)

        /**
         * The hollow, **and the clear air over it** — which is two nodes rather than one because of what
         * the neighbours do.
         *
         * The ellipsoid alone would be the crater. But its cut thins to a point at the crater's own edge,
         * and two craters that touch rim to rim put one's crest exactly there: the taller rim survives
         * over the thinner cut, standing on the piece of ground the bowl removed beneath it. No inequality
         * between rim heights, sizes and lifts fixes that, because at the bowl's edge the cut is a point
         * whatever its reach.
         *
         * So the bowl carries a cylinder of its own footprint reaching from the crest up past anything a
         * crater can build. It removes nothing an isolated crater has — inside the crest the only rock
         * above the crest line is the rim cone's apex, which the ellipsoid was taking anyway — and it
         * removes exactly the neighbour's rim that would otherwise be left hanging.
         */
        fun bowl(): TerrainField = Union(
            listOf(
                Ellipsoid(
                    centerX = 0,
                    centerY = crestY,
                    centerZ = 0,
                    radiusXZ = crestRadius,
                    radiusY = bowlReach,
                ),
                Cylinder(
                    axis = Direction.Axis.Y,
                    centerX = 0,
                    centerY = crestY + CLEAR_OVER_A_CRATER,
                    centerZ = 0,
                    radius = crestRadius,
                    halfLength = CLEAR_OVER_A_CRATER.toDouble(),
                ),
            ),
        )
    }

    /**
     * Two kinds, because one shape repeated is the thing a crater field most reads as. The sharp one is a
     * young crater — a high rim over a deep bowl; the worn one has had its rim knocked down and its floor
     * filled in, which is what an old crater on a plain looks like and is not something a *scale* can say,
     * scaling changing rim and depth together.
     */
    private val CRATER_SHAPES = listOf(
        CraterShape(crestRadius = 26.0, rimRise = 7, rimWidth = 17.0, bowlReach = 26.0),
        // The flank is kept short despite the low rim. A shallow rise over a long run solves to a very
        // broad cone, and a template's base radius is what the scatter has to scan out to on every column.
        CraterShape(crestRadius = 29.0, rimRise = 5, rimWidth = 16.0, bowlReach = 17.0),
    )

    /**
     * The craters, laid over the whole world and bounded by nothing.
     *
     * They need no mask because of the order [world] lays its layers in: everything the impact raised is
     * unioned *over* this, so a crater under the blanket or under a scarp is simply buried rather than
     * left cutting a hole in ground it could never have reached the surface of. A fresh ejecta blanket
     * being uncratered is also, conveniently, true.
     */
    private fun craters(steer: Steer, salt: Long): Craters {
        val layout = Scatter(
            cellSize = dialled(steer.spacing, CRATER_CELL, LEAST_CRATER_CELL, MOST_CRATER_CELL),
            leastPerCell = 0,
            mostPerCell = CRATERS_PER_CELL,
            // Secondaries crowd around the basin and thin out to a sparse plain — the one gradient here
            // that is genuinely a gradient rather than a band, which is what this node describes well.
            density = Density.radial(
                atOrigin = CRATER_DENSITY_NEAR,
                atEdge = CRATER_DENSITY_FAR,
                falloffRadius = CRATER_FALLOFF,
            ),
        )
        val poses = Variation(
            // Turning a circle buys nothing and costs a draw in both halves.
            yawSteps = 1,
            minScale = SMALLEST_CRATER,
            maxScale = WIDEST_CRATER,
            scaleSteps = 4,
            pivotY = PLAIN_Y,
            // **Upward only.** A lift moves rim and bowl together, so lifting one down buries its rim in
            // the plain and leaves a pit with nothing around it — which is the very thing a bowl without
            // a rim is supposed to mean here. Up, it varies how far a rim stands proud and how deep the
            // floor sits, which is the variety that was wanted, and costs a draw either way.
            minLift = LEAST_CRATER_LIFT,
            maxLift = MOST_CRATER_LIFT,
            liftSteps = 4,
        )
        return Craters(
            rims = Instanced(CRATER_SHAPES.map { it.rim() }, layout, poses, CRATER_SEED xor salt),
            bowls = Instanced(CRATER_SHAPES.map { it.bowl() }, layout, poses, CRATER_SEED xor salt),
        )
    }

    private const val WORLD_FLOOR = -64

    /** The plain, high above the waterline so that only the basin and the crater ponds hold water. */
    const val PLAIN_Y = 84
    private const val PLAIN_RELIEF = 4.0

    /** The convention every shape wanting a sea keeps to. */
    const val WATERLINE = 63

    /** Where the rim crest stands, and how far out its ejecta reaches back down to the plain. */
    const val RIM_CREST_RADIUS = 210.0
    const val APRON_RADIUS = 410.0

    /** The crest itself — a hundred over the plain, and a hundred and twenty over the sea inside it. */
    const val RIM_CREST_Y = 154

    /**
     * How deep the excavating ellipsoid reaches below the crest. Sets the basin floor, and has to leave
     * the top of the ellipsoid clear of [apron]'s apex — see [excavation].
     */
    private const val BOWL_DEPTH = 124.0

    /** The middle of the basin, which is [BOWL_DEPTH] under the crest. Twenty-nine under the sea. */
    const val BOWL_FLOOR_Y = RIM_CREST_Y - BOWL_DEPTH.toInt()

    /**
     * How far the middle of the basin gets filled back in, and how far that wanders — so the floor rises
     * to somewhere between 16 and 68 where the noise beats the ellipsoid's own.
     *
     * The ellipsoid's floor climbs from 33 at the centre through 63 at the shoreline to 101 at radius 350,
     * so a level band like this leaves knolls thickly on the deep floor, the odd shoal breaking the water
     * near the shore, and nothing at all up the wall. That gradient is free: it is the bowl's own shape
     * deciding where a flat noise can still reach.
     */
    private const val BASIN_FLOOR_MEAN_Y = 38
    private const val BASIN_FLOOR_RELIEF = 18.0

    /**
     * How far the whole hole is moved up or down per column — see [Undulated], and note it is the *hole*
     * that undulates rather than the world around it.
     *
     * **This is what the basin's smoothness needed and a level noise could not give.** A ceiling or a
     * floor bites at the height it sits at, so on a wall climbing a hundred blocks it reaches one band
     * and leaves the rest exactly as the ellipsoid drew it. Shifting the cut moves the surface with
     * itself, so the wall keeps its gradient and its *contours* wander instead — by about this much where
     * the wall is steep and by many times it across the floor, which is where a surface of revolution
     * stops looking like one.
     *
     * The blanket outside is deliberately left unwrapped: it stays a clean cone, and `CraterlandsCheck`
     * keeps being able to make an exact claim about it.
     */
    private const val BASIN_UNDULATION = 10.0

    /**
     * The ends of each axis this landform honours — where `Terrain.WEAR`, `RELIEF` and `SPACING` reach
     * when a word pushes them all the way.
     *
     * **Read these as the landform's own claim about how far it can be bent and still be itself.** The
     * floors are not zero and the ceilings are not absurd: no undulation at all is a pristine strike and
     * is a thing worth having, but a rim of nothing is not a crater, and a crater field a thousand blocks
     * apart is a plain with an anecdote on it.
     */
    private const val LEAST_UNDULATION = 0.0
    private const val MOST_UNDULATION = 26.0
    private const val LEAST_SCALLOP = 8.0
    private const val MOST_SCALLOP = 46.0
    private const val LEAST_BOWL_DEPTH = 88.0
    private const val MOST_BOWL_DEPTH = 150.0
    private const val LEAST_CRATER_CELL = 58.0
    private const val MOST_CRATER_CELL = 190.0

    /** What the outer scarps' own rises are multiplied by at each end of [Terrain.RELIEF]. */
    private const val FAINTEST_SCARP = 0.35
    private const val BOLDEST_SCARP = 1.6

    /** And the ends of the peak ring's draw, which [Terrain.RELIEF] also moves. */
    private const val NO_PEAK_RING = 0.05
    private const val ALWAYS_A_PEAK_RING = 0.95

    /** How far out the central island's bound reaches, and where it stands. */
    private const val ISLAND_RADIUS = 82.0

    /** Below the basin's own floor, so the island grows out of it rather than standing on it. */
    private const val ISLAND_BASE_Y = 26

    /**
     * Where the bounding cone would come to a point, which is **above anything the noise draws**. The cone
     * is a bound and not a summit: it is meant to be the lower of the two only out at the island's edge,
     * where it takes the footprint away and stops the blob sprawling into the basin.
     */
    private const val ISLAND_CONE_TIP_Y = 112

    /**
     * The island's own surface — a mean thirteen blocks over the waterline, wandering thirty-four.
     *
     * **The mean is what decides how much of it is land.** Most of the interior stands clear of the water
     * and the coast is where the noise happens to fall back through it, which is a lobed outline with bays
     * in it rather than a circle. Raise it and the island fills its bound and comes out round again.
     */
    private const val ISLAND_MEAN_Y = 73
    private const val ISLAND_RELIEF = 22.0

    /** The isle's own parameters — see [centralIsland]. */
    private const val ISLE_SHORE_RADIUS = 45.0
    private const val ISLE_PEAK_RISE = 34.0
    private const val ISLE_RELIEF = 20.0
    private const val ISLE_SHOULDER = 0.5
    private const val ISLE_SHELF_SLOPE = 1.0
    private const val ISLE_SPACING = 20_000.0
    private const val ISLE_REACH = 110.0

    /**
     * Where the ceiling that breaks the isle's plateau sits.
     *
     * **On the plateau, not over it.** At a mean of 118 against an interior standing at 115 the ceiling
     * spent four bearings in five above the rock and changed nothing — a noise's *typical* swing is far
     * narrower than its extremes, so a ceiling that only reaches its subject at the tail of the
     * distribution does not reach it at all. At 105 it cuts most of the plateau most of the time.
     *
     * Its floor still clears the beach: the isle puts the shore around 67 and this bottoms out at 75, so
     * the coast that was the whole reason for using `Isle` is never touched.
     */
    private const val ISLAND_CLIP_MEAN_Y = 90
    private const val ISLAND_CLIP_RELIEF = 18.0

    /**
     * Out at the waterline rather than in against the central peak, which is where it read as a handful
     * of hills in a bowl. The basin floor stands about level with the sea here, so the massifs come out
     * as headlands on a shore instead of as islands in the middle of one.
     */
    const val PEAK_RING_RADIUS = 125.0

    /** Closer than a massif is wide, so the survivors merge into arcs and the gaps read as gaps. */
    private const val PEAK_ARC_SPACING = 58.0
    private const val PEAK_JITTER = 15.0
    private const val PEAK_RADIUS = 48.0

    /**
     * Under the deepest the basin floor ever reaches, like [ISLAND_BASE_Y] and for the same reason:
     * a massif whose skirt is authored above the floor it stands on leaves a one-block disc hanging over
     * it, and the basin floor at the far side of a massif is lower than the floor under its middle.
     */
    private const val PEAK_BASE_Y = 26
    private const val PEAK_TIP_Y = 84
    private const val PEAK_BLEND = 5.0

    /**
     * Short of the rim, so no ring past the first can reach ground low enough to stand out of.
     *
     * **Bounded above by where the blanket falls to [PEAK_TIP_Y], which is radius 682.** Any further and
     * the fourth ring lands on ground the massifs would stand proud of, and the basin acquires a
     * mysterious outer ring of hills a kilometre from anything.
     */
    private const val PEAK_RING_FALLOFF = 350.0
    private const val PEAK_RING_CHANCE = 0.55

    /** How far the widest crater reaches from its middle, which is what the pairing check searches. */
    val CRATER_REACH = CRATER_SHAPES.maxOf { it.crestRadius + it.rimWidth }

    /** How far under the plain's lowest a template anchors, on top of everything that can raise it. */
    private const val CLEAR_OF_THE_PLAIN = 4

    /** The smallest and largest a crater is drawn at, which is also how far its rim stands from its middle. */
    const val SMALLEST_CRATER = 0.85
    const val WIDEST_CRATER = 1.45

    /**
     * How far a crater sits over where its template puts it, least and most.
     *
     * **The floor is not zero on purpose.** The shallowest shape at the smallest size stands about five
     * blocks over the plain, which is the plain's own relief — so at no lift at all its rim is drowned in
     * the ground it sits on and it comes out as a pit with nothing around it. That reads as a sinkhole,
     * and it is indistinguishable from the two crater layers having come apart.
     */
    private const val LEAST_CRATER_LIFT = 2
    private const val MOST_CRATER_LIFT = 7

    /**
     * How far over its own crest a bowl keeps the air clear — see [CraterShape.bowl]. Comfortably past the
     * tallest rim any crater draws, which is about twenty-four blocks over the plain.
     */
    private const val CLEAR_OVER_A_CRATER = 45

    /**
     * Where a crater's rim cone stands before anything moves it, and **the number every dial that raises
     * a crater has to be paid out of.** A cone based above the ground beside it is a disc with daylight
     * under it — the trap [slopeFrom] already warns about, and one that a lift added later walks straight
     * back into: at the greatest lift the base rises eleven blocks while the plain under it can be five
     * down, and the smallest size shrinks what clearance is left toward the pivot rather than away from it.
     *
     * So it is derived from the three of them rather than written down. The cost of reaching this far
     * below is a broader cone — a base radius is what a shallow rise over a long run solves to — and so a
     * wider scatter scan per column, which is the price of the shape being a cone at all.
     */
    private val CRATER_ANCHOR_Y = PLAIN_Y -
        ((PLAIN_RELIEF + CLEAR_OF_THE_PLAIN + MOST_CRATER_LIFT) / SMALLEST_CRATER).roundToInt()

    private const val CRATER_CELL = 95.0
    private const val CRATERS_PER_CELL = 2

    /** How thickly craters lie against the basin, out on the plain, and how far apart those two are. */
    private const val CRATER_DENSITY_NEAR = 0.95
    private const val CRATER_DENSITY_FAR = 0.35
    private const val CRATER_FALLOFF = 1500.0

    /**
     * Where the scalloping ceiling sits on average, and how far it wanders either way — so it runs
     * between 134 and 210 against a crest at 184.
     *
     * **The mean sits under the crest, not at it.** Level with the crest, three bearings in four are cut
     * and none of them by much, which is a rim that measures as scalloped and still reads as a circle.
     * Under it, most of the crest is genuinely taken down and the bearings that keep their full height
     * are the exception — which is the way round that looks like a rim.
     *
     * **The floor is what keeps this maskless**, and is the one relation to check before raising anything
     * else here: the tallest outer scarp reaches 144, the widest crater about 108 and the central peak
     * 126. The scarp is deliberately *inside* the floor's reach — it is another perfect circle and it
     * benefits from the same treatment.
     */
    private const val SCALLOP_MEAN_Y = 144
    private const val SCALLOP_RELIEF = 28.0

    /** Higher than any column in this world, so the shaved-off part is a ceiling and not a lid. */
    private const val ABOVE_ANY_ROCK = 400

    /** How far into an exposed face the weather works. Read against a rim wall a hundred blocks tall. */
    private const val SHELTER_REACH = 16

    // Each node draws its own numbers: two handed one seed agree every time, which reads as coincidence.
    private const val PLAIN_SEED = 0x91A_1DL
    private const val CRATER_SEED = 0xC2A_7E4L
    private const val PEAK_RING_SEED = 0x9EA_C216L
    private const val PEAK_RING_DRAW_SEED = 0xD2A_4EL
    private const val OUTER_RING_SEED = 0x21_65L
    private const val SCALLOP_SEED = 0x5CA_110L
    private const val ISLAND_SEED = 0x15_1A_2DL
    private const val BASIN_FLOOR_SEED = 0xB0_11_0DL
    private const val UNDULATION_SEED = 0x1D_5E_1L
    private const val ISLAND_CLIP_SEED = 0xC_11_9DL
}
