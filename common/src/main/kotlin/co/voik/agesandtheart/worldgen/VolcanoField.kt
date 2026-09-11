package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Cone
import co.voik.agesandtheart.worldgen.field.Cylinder
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Ellipsoid
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.Intersect
import co.voik.agesandtheart.worldgen.field.Placement
import co.voik.agesandtheart.worldgen.field.Scatter
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Undulated
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.Variation
import co.voik.agesandtheart.worldgen.field.Warped
import net.minecraft.core.Direction
import kotlin.math.roundToInt

/**
 * Volcanic cones, scattered over whatever landform the Age already has (design §7.1.2).
 *
 * **A layer rather than a landform.** Volcanoes stand *on* hills or cliffs or plains rather than replacing
 * them, so this is unioned over the shape the terrain aspect built — the same way a roof is.
 *
 * Three things carry the shape, and each answers a different half of "this reads as geometry":
 *
 * - **The profile is two cones, not one.** A single cone has one slope, so it is a spike whatever its
 *   size. Taking the union of a steep narrow one and a shallow wide one gives a *concave* silhouette —
 *   steep at the summit, flattening into a long skirt — which is what a stratovolcano's flank does.
 * - **The summit is truncated and the caldera is cut into the flat.** The peak cone is clipped below its
 *   own point ([Intersect] with a [Slab]), so there is a plateau to put a crater in; without that a cone
 *   is one block wide where the crater wants to be and the cut only decapitates it.
 * - **The finished mountain is warped, then rolled, then rippled.** [Warped] moves the outline, which is
 *   the half undulation cannot reach on anything steep; the two [Undulated] layers move the surface at a
 *   long wavelength and a short one.
 */
object VolcanoField {

    /**
     * Where the volcanoes of an Age stand.
     *
     * **The one value the terrain and the lava tubes both read**, so the cones and the vents inside them
     * agree by construction rather than by coincidence. [Placement] stores nothing per instance and draws
     * from a positional factory, so asking it twice from two places gives the same answer — which is what
     * lets a feature find a summit the noise raised.
     */
    fun sites(seed: Long): Placement = Scatter(
        cellSize = CELL,
        leastPerCell = NONE_AT_ALL,
        mostPerCell = ONE,
        density = Density(
            atOrigin = SPARSE,
            atEdge = SPARSE,
            falloffRadius = CELL,
            // Volcanic country comes in fields with quiet ground between, which is what an even sprinkle
            // over the whole world could never read as.
            patchiness = CLUSTERED,
            patchScale = REGION,
            patchSeed = seed,
        ),
    )

    /**
     * The cones and the lava standing in them — **two fields that have to describe one mountain**.
     *
     * They are built here together rather than by two calls because agreeing is not optional: a lake
     * poured into a crater some other instance drew is a slab of lava hanging in the air. What makes them
     * agree is that [Instanced] takes its per-instance random from the cell alone
     * (`random.at(cellX, 0, cellZ)`), so two layers sharing a seed, a placement and a variation pick the
     * same template at the same pose in every cell — provided their template lists stay the same length
     * and the same order, which is why both are built from [SHAPES] in one place.
     *
     * The lake reaches only as far as a crater does where the cones reach a skirt's whole foot, and
     * [Instanced] prices its cell scan off the templates' own reach — so the second layer costs a small
     * fraction of the first, and a column nowhere near a summit pays only for the cell walk.
     */
    fun over(seed: Long): Volcanoes {
        val mountains = layerOf(SHAPES, sites(seed), seed, SHOULDERS)

        return Volcanoes(
            cones = mountains.cones,
            lakes = mountains.lakes,
        )
    }

    /**
     * One scattering of one set of shapes, as the pair of fields that describe it.
     *
     * **The two layers have to agree, and building them side by side is what makes them.** A lake poured
     * into a crater some other instance drew is a slab of lava hanging in the air. What makes them agree
     * is that [Instanced] takes its per-instance random from the cell alone (`random.at(cellX, 0, cellZ)`),
     * so two layers sharing a seed, a placement and a variation pick the same template at the same pose in
     * every cell — provided their template lists stay the same length and the same order, which is why
     * they are mapped from one list here.
     *
     * A lake reaches only as far as a crater does where a cone reaches a skirt's whole foot, and
     * [Instanced] prices its cell scan off the templates' own reach — so the lake layer costs a small
     * fraction of the rock layer, and a column nowhere near a summit pays only for the cell walk.
     */
    private fun layerOf(shapes: List<Mountain>, placement: Placement, seed: Long, blend: Double): Volcanoes {
        val variation = Variation(
            // Turned, because four shapes repeated unturned is still four shapes: a warped mountain has
            // a recognisable outline, and seeing the same one twice on a walk is what gives it away.
            yawSteps = TURNS,
            minScale = SMALLEST,
            maxScale = LARGEST,
            scaleSteps = SIZES,
            pivotY = BASE_Y,
        )
        return Volcanoes(
            cones = Instanced(shapes.map { it.built(seed) }, placement, variation, seed, blend),
            // A lake is never blended: easing two flat surfaces at different heights into one another
            // smears them, where easing two flanks is what makes a massif.
            lakes = Instanced(shapes.map { it.lake(seed) }, placement, variation, seed, Instanced.NO_BLEND),
        )
    }

    /** An Age's volcanoes: the rock they are made of, and the lava standing in their craters. */
    data class Volcanoes(val cones: TerrainField, val lakes: TerrainField)

    /**
     * **The magma chambers, deep under everything** (design §7.1.2, Jonah 2026-09-09) — a hollow with a
     * pool of lava standing in its bottom, and the underground half of what a volcanic Age is.
     *
     * The same pair of fields as a crater and for the same reason: the cavity is taken out of the rock and
     * the pool is poured into it at generation, so what a player finds is level and complete rather than
     * something that began filling as they watched.
     *
     * **One shape gives both halves of what the design asks for, and which half you get is where you dig.**
     * A chamber a carver happens to open is a lava lake in a cavern with headroom over it and a fall where
     * the cave comes in above; the same chamber sealed in rock is a mining hazard that floods the tunnel
     * the moment a pick reaches it. Nothing has to decide which — the caves and these are scattered
     * independently, so the world sorts them.
     *
     * Deep on purpose: a chamber that reached daylight would be a lava lake on a hillside, which is what
     * the small craters are for and what these are emphatically not.
     */
    fun chambers(seed: Long): Volcanoes {
        val placement = Scatter(
            cellSize = CHAMBER_CELL,
            leastPerCell = NONE_AT_ALL,
            mostPerCell = ONE,
            density = Density(
                atOrigin = CHAMBERS_LIKELY,
                atEdge = CHAMBERS_LIKELY,
                falloffRadius = CHAMBER_CELL,
                patchiness = CLUSTERED,
                patchScale = REGION,
                patchSeed = seed + CHAMBER_SALT,
            ),
        )
        // Pivoted on the rock rather than on a cone's foot, which is the best one pivot can do for six
        // templates at six depths — and it is not free: scaling about y=0 moves a chamber's middle by
        // `(scale - 1) × at`, so a deep one grows *downward* as well as outward. See [CHAMBERS], where the
        // deepest is authored to clear bedrock once that drag is paid.
        val variation = Variation(
            yawSteps = TURNS,
            minScale = SMALLEST,
            maxScale = LARGEST,
            scaleSteps = SIZES,
            pivotY = ON_AXIS,
        )
        val salt = seed + CHAMBER_SALT
        return Volcanoes(
            cones = Instanced(CHAMBERS.map { it.hollow() }, placement, variation, salt, Instanced.NO_BLEND),
            lakes = Instanced(CHAMBERS.map { it.pool() }, placement, variation, salt, Instanced.NO_BLEND),
        )
    }

    /**
     * One chamber, at its own depth — **the depth is per template because [Instanced] moves a copy across
     * the world and never up or down**, so a single shape would put every chamber in an Age on one level.
     */
    private data class Chamber(val at: Int, val across: Double, val tall: Double, val flooded: Double) {

        fun hollow(): TerrainField = Ellipsoid(ON_AXIS, at, ON_AXIS, across, tall)

        /** The lava in the bottom of it, level on top, with the rest left as headroom. */
        fun pool(): TerrainField = Intersect(
            listOf(hollow(), Slab(at - tall.roundToInt(), at - ((ONE_WHOLE - flooded) * tall).roundToInt())),
        )
    }

    /**
     * One mountain, **written down as the part of it anybody sees**.
     *
     * A cone's own numbers are the wrong ones to author in, because a cone here is based a hundred blocks
     * underground and only its last stretch clears the land — so its radius and its tip say almost nothing
     * about how big the mountain looks, and rescaling by eye means refitting four numbers per template and
     * getting the skirt wrong. These are measured against [GROUND_RISE] instead, where [rise] and [foot]
     * are the height and the radius of what stands above ordinary land, and the cones are solved for.
     * Halving a volcano is then halving these.
     *
     * The four below are the whole variety of volcano an Age has, so they are meant to differ in kind — a
     * broad cone, a steep one, a shield, a big-crater one — rather than by a few blocks.
     */
    private data class Mountain(
        /** How far the summit plateau stands above ordinary land. */
        val rise: Int,
        /** How wide the peak is where it meets ordinary land. */
        val foot: Double,
        /** The flat summit the crater is cut into. Must leave a rim ring outside [calderaRadius]. */
        val plateau: Double,
        val calderaRadius: Double,
        val calderaFloorRadius: Double,
        val calderaDepth: Int,
        /** How high the skirt stands at the axis — below [rise], or it would swallow the peak. Zero: none. */
        val skirtRise: Int,
        val skirtFoot: Double,
        /**
         * How far under the rim the lava stands, and how hard the flanks are roughened and the outline
         * warped — **per shape, because a small crater cannot wear a mountain's noise**. A roll of seven
         * over a cone that clears the ground by six is not a rough volcano, it is gravel.
         */
        val freeboard: Int,
        val roll: Double,
        val warp: Double,
        /**
         * How deeply the summit is eaten into — **the only thing that can make a crater spill**, and the
         * reason is worth knowing before turning it. A lake is bounded by the crater's own cut, which is
         * clean geometry, so its edge always meets the wall at exactly its own height however the flanks
         * are roughened. For lava to face open air, the *body* of the mountain has to be lower than the
         * lava somewhere beside it — which on a summit means eroding the ring the crater is cut into.
         */
        val erosion: Double,
        /** Separates this shape's noise from the others', so four templates are four mountains. */
        val salt: Long,
    ) {

        /**
         * The mountain, roughened, with the crater cut out of it **after** the roughening rather than
         * before.
         *
         * That ordering is what a flat lake needs. [Undulated] shifts a whole column, so a crater cut
         * before it moves with the rock: the floor and the rim of one crater then differ by as much as
         * the roll on either side of it, and no level surface can both cover such a floor and stay inside
         * such a rim — the two constraints have no overlap at the depths a caldera is. Cutting afterwards
         * leaves a floor and an inner wall at absolute heights while the flank and the rim keep every bit
         * of their noise, so the lake is bounded by geometry that cannot wander.
         */
        fun built(seed: Long): TerrainField =
            warped(Subtract(Subtract(roughened(mountain(), seed), eatenSummit(seed)), craterCut()), seed)

        /**
         * What is taken off the top of the cone, so a rim is uneven and dips under its own lava in places
         * (Jonah, 2026-09-09: a guaranteed channel through every rim "will make every volcano feel cookie
         * cutter").
         *
         * A slab wandering up and down over the summit takes the rock above wherever it goes, so the ring
         * around a crater comes out notched at whatever bearings the noise chose and at none on some
         * cones. Bounded to a cylinder over the summit: this is an erosion of the *tip*, and applied to
         * the whole mountain it would plane the flanks off at one height.
         *
         * The crater cut runs after it and removes everything inside the caldera anyway, so eating into
         * that has no effect — what this reaches is the ring between the crater and the flank.
         */
        private fun eatenSummit(seed: Long): TerrainField {
            val rim = BASE_Y + summitHeight
            val floor = rim - BITE - erosion.roundToInt()
            val roof = rim + HEADROOM
            return Intersect(
                listOf(
                    Cylinder(
                        axis = Direction.Axis.Y,
                        centerX = ON_AXIS,
                        centerY = (floor + roof) / 2,
                        centerZ = ON_AXIS,
                        radius = plateau + BITTEN_MARGIN,
                        halfLength = (roof - floor) / 2.0,
                    ),
                    Undulated(
                        base = Slab(rim - BITE, roof),
                        seed = seed + salt + BITE_SALT,
                        firstOctave = BITE_OCTAVE,
                        amplitudes = ROUGHNESS,
                        scaleX = BITE_SCALE,
                        scaleZ = BITE_SCALE,
                        amount = erosion,
                    ),
                ),
            )
        }

        /**
         * The lava standing in that crater — everything inside the same cut, from the floor to [freeboard]
         * under the rim, and under the same warp so it meets the rock it was cut from exactly.
         *
         * **The summit ring is left to the noise, and that is what makes a crater spill** (Jonah,
         * 2026-09-09). The crater's inner wall is clean geometry, so a lake bounded by it can never reach
         * anywhere lower than itself — measured, and it is why nothing spilled at all for a while. What
         * *can* dip under the lava is the ring outside the cut, which is rock the roll moves like any
         * other: wherever it pulls a column past the freeboard, that column comes out under the surface
         * and lava runs over the lip and down the flank. In as many places as the noise chooses, at
         * whatever bearings, and on some cones not at all.
         *
         * **Nothing has to hold it back.** A channel was cut through every rim for a while and read as
         * cookie-cutter (Jonah), and the floor put under the ring before that was guarding against a lake
         * draining through a notch — which cannot happen, because generation lays *sources* and a source
         * does not empty. What a notch buys is a permanent lavafall off a full crater. The chunk fill
         * already marks any carried fluid with an open neighbour for post-processing, which is the tick
         * that sets it running.
         */
        fun lake(seed: Long): TerrainField {
            val rim = BASE_Y + summitHeight
            return warped(Intersect(listOf(craterCut(), Slab(rim - calderaDepth, rim - freeboard))), seed)
        }

        /** Whole flanks moving, then surface texture over them. */
        private fun roughened(base: TerrainField, seed: Long): TerrainField = Undulated(
            base = Undulated(
                base = base,
                seed = seed + salt + ROLL_SALT,
                firstOctave = ROLL_OCTAVE,
                amplitudes = ROUGHNESS,
                scaleX = ROLL_SCALE,
                scaleZ = ROLL_SCALE,
                amount = roll,
            ),
            seed = seed + salt + RIPPLE_SALT,
            firstOctave = RIPPLE_OCTAVE,
            amplitudes = ROUGHNESS,
            scaleX = RIPPLE_SCALE,
            scaleZ = RIPPLE_SCALE,
            amount = RIPPLE_AMOUNT,
        )

        /** The outline wandering, which is the half undulation cannot reach — and the lake wears it too. */
        private fun warped(base: TerrainField, seed: Long): TerrainField = Warped(
            base = base,
            seed = seed + salt,
            firstOctave = WARP_OCTAVE,
            amplitudes = WARP_ROUGHNESS,
            scale = WARP_SCALE,
            amount = warp,
        )

        /** Where the plateau sits, measured from the cones' shared foot. */
        private val summitHeight: Int get() = GROUND_RISE + rise

        /** The steep peak, truncated flat, standing on its own shallow skirt. */
        private fun mountain(): TerrainField = Union(listOfNotNull(peak(), skirt()))

        /**
         * The peak, solved from [rise], [foot] and [plateau].
         *
         * A cone through both of those points has radius `(foot − k·plateau) / (1 − k)` for the buried
         * fraction `k = GROUND_RISE / summitHeight`, and the tip follows from its slope. It is then clipped
         * at the summit, which is what leaves a plateau instead of a point.
         */
        private fun peak(): TerrainField {
            val buried = GROUND_RISE.toDouble() / summitHeight
            val radius = (foot - buried * plateau) / (ONE_WHOLE - buried)
            val point = summitHeight * radius / (radius - plateau)
            return Intersect(
                listOf(
                    Cone(
                        baseX = ON_AXIS,
                        baseZ = ON_AXIS,
                        baseRadius = radius,
                        baseY = BASE_Y,
                        tipY = BASE_Y + point.roundToInt(),
                    ),
                    // Its low end is well under the foot, so it clips nothing but the summit.
                    Slab(lowY = BASE_Y, highY = BASE_Y + summitHeight),
                ),
            )
        }

        /**
         * The gentle apron the peak stands on, or null where a shape is gentle enough not to want one.
         *
         * **A skirt is expensive in radius and there is no way around it.** It reaches `skirtFoot` while
         * standing only `skirtRise` above the land, and a cone based [GROUND_RISE] below that land needs a
         * base of `skirtFoot × (GROUND_RISE + skirtRise) / skirtRise` to manage it — so halving a volcano
         * *widens* the ratio, and the flatter the skirt the more it costs. That radius is what the
         * instancing cell scan is priced in, which is why the shield below has none: it is already gentle
         * enough that a second cone would only pay for what its own flank already does.
         */
        private fun skirt(): TerrainField? {
            if (skirtRise <= NO_SKIRT) return null
            val top = GROUND_RISE + skirtRise
            return Cone(
                baseX = ON_AXIS,
                baseZ = ON_AXIS,
                baseRadius = skirtFoot * top / skirtRise,
                baseY = BASE_Y,
                tipY = BASE_Y + top,
            )
        }

        /**
         * The crater: an inverted cone with its own point clipped off, so it has a floor to stand on.
         *
         * The walls are aimed to reach full depth exactly where the floor begins, so the three dials agree
         * whatever any of them is set to and there is never a ledge ringing the floor.
         *
         * **The shaft over the rim is what stops the crater being roofed.** Cutting after the roughening
         * means a column the roll lifted stands higher than the rim the cut reaches, and the rock left
         * over it is a lid across the caldera. Widening the cone upward instead would eat the whole
         * summit ring — it is already at the plateau's radius nine blocks up — so the cut goes straight
         * up at the rim's own width.
         */
        private fun craterCut(): TerrainField {
            val rim = BASE_Y + summitHeight
            val wallRun = calderaDepth / (ONE_WHOLE - calderaFloorRadius / calderaRadius)
            val bowl = Intersect(
                listOf(
                    Cone(
                        baseX = ON_AXIS,
                        baseZ = ON_AXIS,
                        baseRadius = calderaRadius,
                        baseY = rim,
                        tipY = rim - wallRun.roundToInt(),
                    ),
                    Slab(lowY = rim - calderaDepth, highY = rim),
                ),
            )
            val shaft = Cylinder(
                axis = Direction.Axis.Y,
                centerX = ON_AXIS,
                centerY = rim + HEADROOM / 2,
                centerZ = ON_AXIS,
                radius = calderaRadius,
                halfLength = HEADROOM / 2.0,
            )
            return Union(listOf(bowl, shaft))
        }
    }

    /**
     * A broad cone, a steep one, a shield and a wide-crater one — at **half the scale the first two walks
     * saw**, which is what lets a bomb thrown off the crater floor clear its own rim (Jonah: they were "so
     * massive they don't pose any credible threat to anything").
     */
    private val SHAPES = listOf(
        Mountain(
            rise = 33,
            foot = 84.0,
            plateau = 40.0,
            calderaRadius = 30.0,
            calderaFloorRadius = 12.0,
            calderaDepth = 18,
            skirtRise = 20,
            skirtFoot = 106.0,
            freeboard = MOUNTAIN_FREEBOARD,
            roll = ROLL_AMOUNT,
            warp = WARP_AMOUNT,
            erosion = MOUNTAIN_EROSION,
            salt = 0x11L,
        ),
        Mountain(
            rise = 44,
            foot = 72.0,
            plateau = 26.0,
            calderaRadius = 17.0,
            calderaFloorRadius = 6.0,
            calderaDepth = 17,
            skirtRise = 24,
            skirtFoot = 96.0,
            freeboard = MOUNTAIN_FREEBOARD,
            roll = ROLL_AMOUNT,
            warp = WARP_AMOUNT,
            erosion = MOUNTAIN_EROSION,
            salt = 0x2222L,
        ),
        // The shield: gentle enough over its own flank that a skirt would buy nothing but reach.
        Mountain(
            rise = 22,
            foot = 98.0,
            plateau = 46.0,
            calderaRadius = 34.0,
            calderaFloorRadius = 15.0,
            calderaDepth = 17,
            skirtRise = NO_SKIRT,
            skirtFoot = 0.0,
            freeboard = MOUNTAIN_FREEBOARD,
            roll = ROLL_AMOUNT,
            warp = WARP_AMOUNT,
            erosion = MOUNTAIN_EROSION,
            salt = 0x333333L,
        ),
        Mountain(
            rise = 37,
            foot = 80.0,
            plateau = 36.0,
            calderaRadius = 26.0,
            calderaFloorRadius = 10.0,
            calderaDepth = 19,
            skirtRise = 21,
            skirtFoot = 102.0,
            freeboard = MOUNTAIN_FREEBOARD,
            roll = ROLL_AMOUNT,
            warp = WARP_AMOUNT,
            erosion = MOUNTAIN_EROSION,
            salt = 0x44444444L,
        ),
    )

    /**
     * The chambers an Age holds, spread down the rock rather than sitting on one level.
     *
     * They differ in kind rather than by a few blocks, like the cones do: a wide shallow sump, a tall
     * narrow shaft, a great flooded hall. How much of each is lava is drawn with them, so some are a pool
     * with a cavern over it and others are nearly full to the roof.
     *
     * **The deepest of them is authored to clear bedrock at its largest pose, and that is a real
     * constraint rather than a taste** (measured 2026-09-11). [Variation] scales about y=0 and has one
     * pivot for all six, so growing a chamber also *drags it down* by `(scale - 1) × at` — the deepest was
     * `at = -50`, which at 1.3 lands its middle at −65 and its floor at −77, under an Age's bedrock at −64.
     * The hollow then came out as its own top half sitting on the world floor, and because [Chamber.pool]
     * fills from the *bottom*, the part that was lost was all of the lava. Two chambers in fourteen on seed
     * 4242 were dry rooms. `MagmaChamberCheck` holds the line; if a deeper one is ever wanted, the pose
     * range has to shrink with it.
     */
    private val CHAMBERS = listOf(
        Chamber(at = -8, across = 15.0, tall = 7.0, flooded = 0.45),
        Chamber(at = -34, across = 11.0, tall = 11.0, flooded = 0.6),
        Chamber(at = 14, across = 19.0, tall = 6.0, flooded = 0.35),
        Chamber(at = -38, across = 22.0, tall = 9.0, flooded = 0.5),
        Chamber(at = -22, across = 9.0, tall = 5.0, flooded = 0.7),
        Chamber(at = 2, across = 13.0, tall = 8.0, flooded = 0.4),
    )

    /**
     * Where a cone's foot sits.
     *
     * Well below any plausible ground, because a cone is unioned *over* the Age's own terrain: starting it
     * underground is what lets it meet a hillside at whatever height that hillside happens to be, instead
     * of hanging off a cliff or floating over a valley.
     */
    private const val BASE_Y = -32

    /**
     * How far ordinary land stands above [BASE_Y] — the plane a [Mountain]'s visible numbers are measured
     * against.
     *
     * **A fiction, and a useful one.** Real ground is wherever the Age's own landform put it, so no cone
     * clears it by exactly [Mountain.rise]; what this buys is that the four templates are proportionate to
     * each other and can be rescaled together by changing the numbers anybody can see.
     */
    private const val GROUND_RISE = 102

    private const val NO_SKIRT = 0

    /** Above the rim, past anything the roll can lift over it — see [Mountain.craterCut]. */
    private const val HEADROOM = 12

    /**
     * How far the outline wanders, and over what wavelength.
     *
     * The big lever on whether a volcano reads as a landform, because it is the only one that reaches the
     * silhouette — see [Warped].
     *
     * **A wavelength here is `scale × 2^-firstOctave`, not `scale`**, and getting that backwards is the
     * quiet way to write a noise that does nothing: a scale of 170 at octave −6 is a swing 10,000 blocks
     * across, which over one mountain is a constant, and a constant displacement only *moves* a cone. Two
     * octaves rather than four, and weighted to the long one, because a domain warp folds over itself once
     * its gradient reaches one — and a folded lookup reads the template twice, which puts a second rim
     * inside the crater.
     */
    private const val WARP_OCTAVE = -7
    private val WARP_ROUGHNESS = listOf(1.0, 0.45)
    private const val WARP_SCALE = 1.05
    private const val WARP_AMOUNT = 14.0

    /** Six blocks of lip over the lava, which is what a walk asked for. */
    private const val MOUNTAIN_FREEBOARD = 6

    /** Far enough past the freeboard that the noise reaches under the lava sometimes and not often. */
    private const val MOUNTAIN_EROSION = 7.0

    /**
     * How the summit is eaten into: how far the erosion starts below the rim, how far it wanders, over
     * what wavelength, and how far past the plateau it reaches.
     *
     * The wavelength is short on purpose — a notch in a rim wants to be a gap you can see the far side
     * of rather than half the crater standing lower than the other half.
     */
    private const val BITE = 2
    private const val BITE_OCTAVE = -5
    private const val BITE_SCALE = 0.8
    private const val BITTEN_MARGIN = 6.0
    private const val BITE_SALT = 0x42_4954_45L

    /**
     * How far apart the chambers sit, and how likely a cell is to hold one.
     *
     * Rarer than the ponds by a good margin: one of these is a find rather than scenery, and a tunnel that
     * met one every hundred blocks would be a tunnel nobody digs. About one per two hundred and sixty
     * square, which over a kilometre of country is a dozen or so.
     */
    private const val CHAMBER_CELL = 260.0
    private const val CHAMBERS_LIKELY = 0.45

    private const val CHAMBER_SALT = 0x43_4841_4D42L

    /** Whole flanks moving, then surface texture on top of them — about 60 blocks, then about 20. */
    private const val ROLL_OCTAVE = -7
    private const val RIPPLE_OCTAVE = -5
    private val ROUGHNESS = listOf(1.0, 0.9, 0.6, 0.35)
    private const val ROLL_SCALE = 0.48
    private const val ROLL_AMOUNT = 7.0
    private const val RIPPLE_SCALE = 0.65
    private const val RIPPLE_AMOUNT = 1.5

    private const val ROLL_SALT = 0x5230_4C4CL
    private const val RIPPLE_SALT = 0x5249_5050L

    /**
     * Far enough apart to be separate mountains, close enough to meet more than one on a walk.
     *
     * It is also what the per-column cost is paid in: [Instanced] scans every cell within a template's
     * reach, and the skirts reach a long way, so cells much smaller than this would have an ordinary
     * column asking after a dozen volcanoes that are not there.
     */
    private const val CELL = 580.0
    private const val REGION = 1800.0

    private const val NONE_AT_ALL = 0
    private const val ONE = 1

    /** Together these run from about one cell in five in the quiet country to every cell in a field. */
    private const val SPARSE = 0.6
    private const val CLUSTERED = 0.4

    /**
     * Never smaller than its template, because a cone scaled down never clears the ground it is laid over
     * — which is not a small volcano but an invisible one with a vent wired into a hillside. Size variety
     * comes from the four shapes instead, which is where it can be made proportionate.
     */
    private const val SMALLEST = 1.0
    private const val LARGEST = 1.3
    private const val SIZES = 4

    private const val TURNS = 8

    /** Enough to turn two overlapping skirts into one massif rather than a crease. */
    private const val SHOULDERS = 8.0

    private const val ON_AXIS = 0
    private const val ONE_WHOLE = 1.0
}
