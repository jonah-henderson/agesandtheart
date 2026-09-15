package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * What a canyon's cross-section looks like, from its axis out to its rim.
 *
 * **Proportions, never distances.** Every number here is a share of the canyon's own width or depth, which
 * is what makes [Canyon.resized] able to leave the whole of it alone: a canyon a third the size should
 * still be a canyon, not a shallower one. It is also why these travel together — they only mean anything
 * against each other.
 */
data class CanyonProfile(
    /** How many benches the wall climbs in, between the inner gorge and the rim. */
    val benches: Int = DEFAULT_BENCHES,
    /** What share of a bench is riser rather than tread. Small keeps the cliffs steep and the treads wide. */
    val riserShare: Double = DEFAULT_RISER_SHARE,
    /**
     * What share of the half-width lies flat at the floor — the valley bed the river runs down. Without it
     * the gorge closes to a point and the river is a crack rather than something with banks.
     */
    val floorShare: Double = DEFAULT_FLOOR_SHARE,
    /** What share of the half-width the inner gorge takes, the flat bed included. */
    val gorgeShare: Double = DEFAULT_GORGE_SHARE,
    /** What share of the depth the inner gorge climbs through — against [gorgeShare], its steepness. */
    val gorgeRise: Double = DEFAULT_GORGE_RISE,
) {
    /**
     * How far up from the floor the ground has climbed, [across] the way out to the rim: the flat bed,
     * then the gorge steeply, then the terraced wall. Zero at the axis and one at the rim, so a caller
     * scales it by whatever depth its own landform has.
     */
    fun climbAt(across: Double): Double {
        if (across <= floorShare) return 0.0
        if (across <= gorgeShare) {
            val upTheGorge = (across - floorShare) / (gorgeShare - floorShare).coerceAtLeast(SMALLEST_SHARE)
            return gorgeRise * upTheGorge
        }
        val upTheWall = (across - gorgeShare) / (1.0 - gorgeShare).coerceAtLeast(SMALLEST_SHARE)
        return gorgeRise + (1.0 - gorgeRise) * terracedAt(upTheWall)
    }

    /**
     * [upTheWall] quantised into benches: a riser climbing over [riserShare] of each bench, then a flat
     * tread across the rest. Eased rather than vertical, so a riser is a steep slope of a few blocks and
     * not a wall one column wide — which any fraying would turn into a comb.
     */
    private fun terracedAt(upTheWall: Double): Double {
        val steps = benches.coerceAtLeast(1)
        val reached = upTheWall * steps
        val bench = floor(reached)
        val climbing = ((reached - bench) / riserShare.coerceAtLeast(SMALLEST_SHARE)).coerceIn(0.0, 1.0)
        val eased = climbing * climbing * (3.0 - 2.0 * climbing)
        return ((bench + eased) / steps).coerceIn(0.0, 1.0)
    }

    companion object {
        /** Never below this, since a share divides and zero would send a floor to infinity. */
        internal const val SMALLEST_SHARE = 1.0e-6

        /** Enough to read as strata; more than about six and each tread is too narrow to stand on. */
        const val DEFAULT_BENCHES = 5

        /** The riser's share of a bench. Small, or the wall becomes a ramp with ripples in it. */
        const val DEFAULT_RISER_SHARE = 0.3

        /** The river bed's share of the width — a valley floor about a tenth of the way out. */
        const val DEFAULT_FLOOR_SHARE = 0.09

        /** The gorge's share of the width, and of the depth. Steep against the benched wall above it. */
        const val DEFAULT_GORGE_SHARE = 0.3
        const val DEFAULT_GORGE_RISE = 0.25

        val DEFAULT = CanyonProfile()

        /**
         * Inlined into [Canyon]'s own record rather than nested under a key, so a canyon still reads as one
         * flat object in a recipe. What this buys is a codec slot: sixteen is the most a record takes.
         */
        val MAP_CODEC: MapCodec<CanyonProfile> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.INT.optionalFieldOf("benches", DEFAULT_BENCHES).forGetter(CanyonProfile::benches),
                Codec.DOUBLE.optionalFieldOf("riser_share", DEFAULT_RISER_SHARE)
                    .forGetter(CanyonProfile::riserShare),
                Codec.DOUBLE.optionalFieldOf("floor_share", DEFAULT_FLOOR_SHARE)
                    .forGetter(CanyonProfile::floorShare),
                Codec.DOUBLE.optionalFieldOf("gorge_share", DEFAULT_GORGE_SHARE)
                    .forGetter(CanyonProfile::gorgeShare),
                Codec.DOUBLE.optionalFieldOf("gorge_rise", DEFAULT_GORGE_RISE)
                    .forGetter(CanyonProfile::gorgeRise),
            ).apply(instance, ::CanyonProfile)
        }
    }
}

/**
 * The ground a river took: a wedge either side of a straight axis, solid upwards from a floor that **rises
 * in terraces with distance from it**. Meant to be [Subtract]ed from a shape, like [Rift].
 *
 * Where a [Rift] runs along a territory seam and climbs to its rim in a straight V, this runs along an axis
 * of its own and climbs in **benches** — a narrow inner gorge at the bottom, then flat treads separated by
 * steep risers out to the rim. That profile is the whole difference, and it is what makes a canyon read as
 * strata worn back at different rates rather than as a trench someone dug.
 *
 * **One canyon, not a system of them.** Several are [Union]ed together before being subtracted, which is
 * what [cut] does — so a world crossed by many is the same node at a smaller [halfWidth] with different
 * bearings and offsets, and [resized] scales one whole.
 *
 * Three noises keep it from being a ruled line: the axis itself **meanders** along its length, the wall
 * frays block to block, and — because the profile is a function of distance alone — those two are the only
 * things making one cross-section differ from the next.
 */
data class Canyon(
    /** Which way the canyon runs, in radians, zero running it along +Z and a quarter turn along +X. */
    val bearing: Double,
    /** How far the axis sits from the origin, measured across the canyon. Zero runs it through the origin. */
    val offset: Double,
    /** How far either side of the axis the rim stands, so the canyon is twice this across. */
    val halfWidth: Double,
    /** The **mean** level of the floor at the axis — the river bed, about which [bedRelief] varies. */
    val floorY: Int,
    /**
     * Where the canyon meets the surrounding ground, and so where it stops cutting. **One past the top of
     * the ground it cuts**, or the rim is shaved by a block all the way along.
     */
    val rimY: Int,
    val seed: Long,
    /** What the cross-section looks like, in proportions — see [CanyonProfile]. */
    val profile: CanyonProfile = CanyonProfile.DEFAULT,
    /** How far the axis meanders either way, in blocks. Zero rules the canyon straight. */
    val meanderReach: Double = DEFAULT_MEANDER_REACH,
    /** Blocks *along* the canyon per unit of meander noise — how long one bend runs. */
    val meanderStretch: Double = DEFAULT_MEANDER_STRETCH,
    /** How far the wall frays, in blocks. What stops every terrace edge being a drawn contour. */
    val roughness: Double = DEFAULT_ROUGHNESS,
    /**
     * What share of [halfWidth] the two flanks stand in or out by, each on its own.
     *
     * Without it the profile is a function of distance alone and comes out **mirror-symmetric**: both
     * walls step back at exactly the same places, which reads as machined. This is what makes one side
     * broad and benched where the other is a sheer wall, and what varies that along the canyon's length.
     */
    val flankVariation: Double = DEFAULT_FLANK_VARIATION,
    /**
     * How far the valley floor stands above and below [floorY], in blocks.
     *
     * The profile is a function of distance out from the axis, so the bed it describes is a poured slab —
     * every column across the valley floor at exactly one height. This is what makes it a river bed: pools
     * where it dips under the waterline and bars where it stands proud of it. Drawn out **along** the
     * canyon rather than isotropically, so a run of shallows is a reach of the river and not a patch.
     */
    val bedRelief: Double = DEFAULT_BED_RELIEF,
    /**
     * How far apart the canyons of a **family** run, in blocks — one node describing a whole set of
     * parallel canyons rather than a single one. Zero, the default, is the single canyon.
     *
     * Unioning a list of canyons cannot do this: they would be a finite set of lines through one part of
     * the world, and the land between them grows without bound as you walk away from it. A family
     * repeats, so a network of them covers the whole plane evenly — which is what a land criss-crossed by
     * canyons has to be.
     *
     * Keep [meanderReach] plus [halfWidth] well inside half of this, or a canyon wanders into its own
     * neighbour's half and is clipped at the join.
     */
    val spacing: Double = NO_FAMILY,
) : TerrainField {
    override val kind = FieldKind.CANYON

    // A canyon runs right across a world, so there is no bounded neighbourhood to scan — as with [Rift].
    override val horizontalReach = Double.POSITIVE_INFINITY

    override val samplesPerColumn = 4

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val alongAxis = alongBearing(bearing, worldX, worldZ)
        val fromLine = acrossBearing(bearing, worldX, worldZ) - offset
        // Which canyon of the family this column belongs to, and how far it lies from *that* one's axis.
        // No spacing is the single canyon, which is repeat zero over the whole plane.
        val repeat = if (spacing <= 0.0) 0.0 else floor(fromLine / spacing + HALF)
        // Every noise below is read on a plane of this canyon's own, or a family would meander, widen and
        // pool in lockstep, and the land between its members would come out corrugated.
        val ownPlane = repeat * REPEAT_SEPARATION
        // The axis wanders along its own length, so the canyon snakes as a whole. Measured *before* the
        // distance is taken, which is what moves the centreline rather than merely blurring the rim.
        val wandered = meanderNoise.getValue(alongAxis / meanderStretch, ownPlane, 0.0) * meanderReach
        val fromCentre = fromLine - repeat * spacing - wandered
        val frayed = wallNoise.getValue(worldX / WALL_STRETCH, 0.0, worldZ / WALL_STRETCH) * roughness
        val fromAxis = (abs(fromCentre) + frayed).coerceAtLeast(0.0)
        val widthHere = widthOf(fromCentre, alongAxis, ownPlane)
        if (fromAxis >= widthHere) return Spans.EMPTY
        val climb = profile.climbAt(fromAxis / widthHere)
        val floorHere = floorY + ((rimY - floorY) * climb).roundToInt() +
            bedAt(alongAxis, fromCentre, climb, ownPlane)
        return Spans.of(floorHere, Spans.HIGHEST_Y)
    }

    /**
     * How far the bed stands over its mean here, in blocks — and nothing once the ground has climbed out
     * of the gorge, where the wall's own fray is already doing this job sideways.
     *
     * The noise is **coerced** rather than taken as it comes, so the bed keeps to `floorY ± bedRelief`:
     * this is the one place the shape reaches below its own floor, and what is under it is the only rock
     * the canyon deliberately leaves whole.
     */
    private fun bedAt(alongAxis: Double, fromCentre: Double, climb: Double, ownPlane: Double): Int {
        if (bedRelief <= 0.0) return 0
        val onTheBed = 1.0 - (climb / profile.gorgeRise.coerceAtLeast(CanyonProfile.SMALLEST_SHARE)).coerceIn(0.0, 1.0)
        if (onTheBed <= 0.0) return 0
        val downstream = alongAxis / bedStretch
        val across = fromCentre / (bedStretch * BED_NARROWING)
        val standing = bedNoise.getValue(downstream, ownPlane, across).coerceIn(-1.0, 1.0)
        return (standing * bedRelief * onTheBed).roundToInt()
    }

    /**
     * How far the rim stands from the axis on this side, here. The **same noise read on a different
     * plane** for each flank, which costs one sample and is what keeps the two from agreeing — a second
     * noise object would buy nothing a separated plane does not.
     */
    private fun widthOf(fromCentre: Double, alongAxis: Double, ownPlane: Double): Double {
        val plane = if (fromCentre < 0.0) -FLANK_SEPARATION else FLANK_SEPARATION
        val stands = meanderNoise.getValue(alongAxis / flankStretch, plane + ownPlane, 0.0) * flankVariation
        return halfWidth * (1.0 + stands).coerceAtLeast(CanyonProfile.SMALLEST_SHARE)
    }

    private val meanderNoise = fieldNoise(seed, MEANDER_OCTAVE, MEANDER_AMPLITUDES)
    private val wallNoise = fieldNoise(seed xor WALL_SALT, WALL_OCTAVE, WALL_AMPLITUDES)
    private val bedNoise = fieldNoise(seed xor BED_SALT, BED_OCTAVE, BED_AMPLITUDES)

    /** Shorter than a bend, so a wall steps in and out several times within one meander. */
    private val flankStretch = meanderStretch * FLANK_SHARE_OF_A_BEND

    /** Pool-to-pool rather than bend-to-bend: several reaches of river within one meander. */
    private val bedStretch = meanderStretch * BED_SHARE_OF_A_BEND

    /**
     * Everything that is a distance scales; the bearing and the whole [profile] do not. An angle is not a
     * length, and a profile is proportions throughout — see [CanyonProfile].
     */
    override fun resized(factor: Double, pivotY: Int) = copy(
        offset = offset * factor,
        halfWidth = halfWidth * factor,
        floorY = scaledAbout(floorY, factor, pivotY),
        rimY = scaledAbout(rimY, factor, pivotY),
        meanderReach = meanderReach * factor,
        meanderStretch = meanderStretch * factor,
        roughness = roughness * factor,
        bedRelief = bedRelief * factor,
        spacing = spacing * factor,
    )

    companion object {
        /**
         * How far the bed rises and falls. Read against the river's own depth: past it and the bed breaks
         * the surface as a bar, well under it and the water runs deep.
         */
        const val DEFAULT_BED_RELIEF = 8.0

        /** How long a reach of river runs, against the length of a bend. */
        private const val BED_SHARE_OF_A_BEND = 0.3

        /** How much shorter a bed feature is across the river than along it. */
        private const val BED_NARROWING = 0.4

        private const val BED_OCTAVE = -4
        private val BED_AMPLITUDES = listOf(1.0, 0.5)
        private const val BED_SALT = 0x8ED_B0DL

        /** How far the axis wanders, and how long a bend runs. Both scale with a resized canyon. */
        const val DEFAULT_MEANDER_REACH = 60.0
        const val DEFAULT_MEANDER_STRETCH = 40.0

        /** How far the wall frays. A few blocks: enough to spoil the contour, not to erase the bench. */
        const val DEFAULT_ROUGHNESS = 7.0

        /** How far a flank stands in or out. A quarter of the width is plainly visible without lopsidedness. */
        const val DEFAULT_FLANK_VARIATION = 0.25

        /** How far apart the two flanks read the noise. Far enough that neither knows about the other. */
        private const val FLANK_SEPARATION = 512.0

        private const val FLANK_SHARE_OF_A_BEND = 0.35

        /** One canyon rather than a family of them — see [spacing]. */
        const val NO_FAMILY = 0.0

        private const val HALF = 0.5

        /** How far apart in the noise two neighbouring canyons of a family read. Far enough to disagree. */
        private const val REPEAT_SEPARATION = 512.0

        // Long bends: at this octave a unit of noise is sixteen of input, so a bend runs some hundreds of
        // blocks at the default stretch.
        private const val MEANDER_OCTAVE = -4
        private val MEANDER_AMPLITUDES = listOf(1.0, 0.5)

        // And a finer pattern for the wall, down to a few blocks, so terrace edges come out ragged.
        private const val WALL_OCTAVE = -4
        private val WALL_AMPLITUDES = listOf(1.0, 0.5, 0.25)
        private const val WALL_STRETCH = 1.0
        private const val WALL_SALT = 0x3A9_C0DEL

        /**
         * [base] with these canyons cut through it, or [base] itself where there are none. Several are
         * unioned before the subtraction so that where two cross, the deeper cut wins — which is what a
         * confluence is.
         */
        fun cut(base: TerrainField, canyons: List<Canyon>): TerrainField {
            val cutting = canyons.filter { it.halfWidth > 0.0 && it.rimY > it.floorY }
            if (cutting.isEmpty()) return base
            return Subtract(base, cutting.singleOrNull() ?: Union(cutting))
        }

        /** No children, so no need for the recursive field codec — the same shape [Rift] has. */
        val CODEC: MapCodec<Canyon> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Codec.DOUBLE.fieldOf("bearing").forGetter(Canyon::bearing),
                Codec.DOUBLE.fieldOf("offset").forGetter(Canyon::offset),
                Codec.DOUBLE.fieldOf("half_width").forGetter(Canyon::halfWidth),
                Codec.INT.fieldOf("floor_y").forGetter(Canyon::floorY),
                Codec.INT.fieldOf("rim_y").forGetter(Canyon::rimY),
                Codec.LONG.fieldOf("seed").forGetter(Canyon::seed),
                CanyonProfile.MAP_CODEC.forGetter(Canyon::profile),
                Codec.DOUBLE.optionalFieldOf("meander_reach", DEFAULT_MEANDER_REACH)
                    .forGetter(Canyon::meanderReach),
                Codec.DOUBLE.optionalFieldOf("meander_stretch", DEFAULT_MEANDER_STRETCH)
                    .forGetter(Canyon::meanderStretch),
                Codec.DOUBLE.optionalFieldOf("roughness", DEFAULT_ROUGHNESS).forGetter(Canyon::roughness),
                Codec.DOUBLE.optionalFieldOf("flank_variation", DEFAULT_FLANK_VARIATION)
                    .forGetter(Canyon::flankVariation),
                Codec.DOUBLE.optionalFieldOf("bed_relief", DEFAULT_BED_RELIEF).forGetter(Canyon::bedRelief),
                Codec.DOUBLE.optionalFieldOf("spacing", NO_FAMILY).forGetter(Canyon::spacing),
            ).apply(instance, ::Canyon)
        }
    }
}
