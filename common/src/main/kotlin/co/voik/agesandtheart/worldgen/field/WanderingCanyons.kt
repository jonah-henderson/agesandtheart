package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One set of canyons in [WanderingCanyons]: those running along the contours of a noise [stretch] blocks to a unit,
 * so they lie roughly that far apart.
 */
data class CanyonCourse(
    val stretch: Double,
    val halfWidth: Double,
    /** What share of the full depth these canyons cut, so a shallower course hangs over the deeper ones it meets. */
    val depthShare: Double = 1.0,
    /** What share of the land these canyons cross; under one, they run out where a second noise says they are absent. */
    val presence: Double = 1.0,
) {
    fun resized(factor: Double) = copy(stretch = stretch * factor, halfWidth = halfWidth * factor)

    companion object {
        val CODEC: Codec<CanyonCourse> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.DOUBLE.fieldOf("stretch").forGetter(CanyonCourse::stretch),
                Codec.DOUBLE.fieldOf("half_width").forGetter(CanyonCourse::halfWidth),
                Codec.DOUBLE.optionalFieldOf("depth_share", 1.0).forGetter(CanyonCourse::depthShare),
                Codec.DOUBLE.optionalFieldOf("presence", 1.0).forGetter(CanyonCourse::presence),
            ).apply(instance, ::CanyonCourse)
        }
    }
}

/**
 * The ground canyons took, where each canyon runs along a **contour of a slow noise**: a line that wanders on every
 * heading, runs on for kilometres, and closes on itself around a butte now and then. Meant to be [Subtract]ed, like
 * [Canyon].
 *
 * Contours lie unevenly, so the table between them comes in every size, and each of the [courses] adds a coarser or
 * finer set. A column's distance from a contour is its noise over the noise's gradient, and the canyon's
 * [profile] is laid out by that distance, with the flanks standing in and out on their own and the wall frayed.
 */
data class WanderingCanyons(
    val courses: List<CanyonCourse>,
    /** The mean river bed of a canyon cutting the full depth. */
    val floorY: Int,
    /** One past the top of the ground being cut. */
    val rimY: Int,
    val seed: Long,
    val profile: CanyonProfile = CanyonProfile.DEFAULT,
    val roughness: Double = Canyon.DEFAULT_ROUGHNESS,
    val flankVariation: Double = Canyon.DEFAULT_FLANK_VARIATION,
    val bedRelief: Double = Canyon.DEFAULT_BED_RELIEF,
) : TerrainField {
    override val kind = FieldKind.WANDERING_CANYONS

    override val horizontalReach = Double.POSITIVE_INFINITY

    override val samplesPerColumn = courses.size * SAMPLES_PER_COURSE + 1

    private val contourNoises = courses.indices.map { index -> fieldNoise(seed + index, CONTOUR_OCTAVE, CONTOUR_AMPLITUDES) }
    private val presenceNoises = courses.indices.map { index -> fieldNoise((seed xor PRESENCE_SALT) + index, PRESENCE_OCTAVE, PRESENCE_AMPLITUDES) }
    private val flankNoise = fieldNoise(seed xor FLANK_SALT, FLANK_OCTAVE, FLANK_AMPLITUDES)
    private val wallNoise = fieldNoise(seed xor WALL_SALT, WALL_OCTAVE, WALL_AMPLITUDES)
    private val bedNoise = fieldNoise(seed xor BED_SALT, BED_OCTAVE, BED_AMPLITUDES)

    override fun columnSpans(worldX: Int, worldZ: Int): Spans {
        val frayed = wallNoise.get(worldX.toDouble(), 0.0, worldZ.toDouble()).toDouble() * roughness
        val lowestFloor = courses.indices.mapNotNull { index -> floorOf(index, worldX, worldZ, frayed) }.minOrNull()
            ?: return Spans.EMPTY
        return Spans.of(lowestFloor, Spans.HIGHEST_Y)
    }

    /** Where this course's canyon floors the column, or null where the column stands outside it. */
    private fun floorOf(index: Int, worldX: Int, worldZ: Int, frayed: Double): Int? {
        val course = courses[index]
        val stretch = course.stretch.coerceAtLeast(SMALLEST_STRETCH)
        val noise = contourNoises[index]
        fun sample(x: Double, z: Double) = noise.get(x / stretch, 0.0, z / stretch).toDouble()
        val here = sample(worldX.toDouble(), worldZ.toDouble())
        val widest = course.halfWidth * (1.0 + flankVariation) + roughness
        // A noise this far from zero is too far from its contour for any canyon of this course, however steep it is.
        if (abs(here) * stretch > widest * STEEPEST_GRADIENT) return null
        val eastward = (sample(worldX + GRADIENT_STEP, worldZ.toDouble()) - here) / GRADIENT_STEP
        val southward = (sample(worldX.toDouble(), worldZ + GRADIENT_STEP) - here) / GRADIENT_STEP
        val gradient = sqrt(eastward * eastward + southward * southward).coerceAtLeast(FLATTEST_GRADIENT / stretch)
        val fromContour = abs(here) / gradient
        val widthHere = course.halfWidth * presenceAt(index, worldX, worldZ) * flankAt(index, here, worldX, worldZ)
        val fromAxis = (fromContour + frayed).coerceAtLeast(0.0)
        if (widthHere <= 0.0 || fromAxis >= widthHere) return null
        val climb = profile.climbAt(fromAxis / widthHere)
        val depth = (rimY - floorY) * course.depthShare.coerceIn(0.0, 1.0)
        val bed = rimY - depth
        return (bed + depth * climb).roundToInt() + bedAt(worldX, worldZ, climb)
    }

    /**
     * One where the course is present, narrowing over a band where it runs out and ending at [NARROWEST_HEAD] of
     * its width, so a canyon stops at a box head rather than thinning to a scratch.
     */
    private fun presenceAt(index: Int, worldX: Int, worldZ: Int): Double {
        val presence = courses[index].presence
        if (presence >= 1.0) return 1.0
        val threshold = 1.0 - 2.0 * presence.coerceAtLeast(0.0)
        val reading = presenceNoises[index].get(worldX / PRESENCE_STRETCH, 0.0, worldZ / PRESENCE_STRETCH).toDouble()
        val share = smoothstep((reading - threshold) / PRESENCE_FADE + HALF)
        return if (share < NARROWEST_HEAD) 0.0 else share
    }

    /** How far this flank stands out, read on a plane of its own for each side of the contour. */
    private fun flankAt(index: Int, contour: Double, worldX: Int, worldZ: Int): Double {
        val side = if (contour < 0.0) -FLANK_SEPARATION else FLANK_SEPARATION
        val plane = side + index * COURSE_SEPARATION
        val stands = flankNoise.get(worldX / FLANK_STRETCH, plane, worldZ / FLANK_STRETCH).toDouble() * flankVariation
        return (1.0 + stands).coerceAtLeast(0.0)
    }

    /** How far the bed stands over its mean, on the bed only. */
    private fun bedAt(worldX: Int, worldZ: Int, climb: Double): Int {
        if (bedRelief <= 0.0) return 0
        val onTheBed = 1.0 - (climb / profile.gorgeRise.coerceAtLeast(CanyonProfile.SMALLEST_SHARE)).coerceIn(0.0, 1.0)
        if (onTheBed <= 0.0) return 0
        val standing = bedNoise.get(worldX / BED_STRETCH, 0.0, worldZ / BED_STRETCH).toDouble().coerceIn(-1.0, 1.0)
        return (standing * bedRelief * onTheBed).roundToInt()
    }

    override fun resized(factor: Double, pivotY: Int) = copy(
        courses = courses.map { it.resized(factor) },
        floorY = scaledAbout(floorY, factor, pivotY),
        rimY = scaledAbout(rimY, factor, pivotY),
        roughness = roughness * factor,
        bedRelief = bedRelief * factor,
    )

    private fun smoothstep(share: Double): Double {
        val clamped = share.coerceIn(0.0, 1.0)
        return clamped * clamped * (3.0 - 2.0 * clamped)
    }

    companion object {
        private const val SAMPLES_PER_COURSE = 4
        private const val HALF = 0.5

        // Smooth enough that a contour wanders rather than wriggles.
        private const val CONTOUR_OCTAVE = 0
        private val CONTOUR_AMPLITUDES = listOf(1.0, 0.3, 0.08)

        /** Blocks between the samples a gradient is read from; small against any course's stretch. */
        private const val GRADIENT_STEP = 4.0

        /**
         * Bounds on the contour noise's gradient per unit of noise. Past the steepest, no column is skipped that
         * should have been cut; under the flattest, near a saddle or a summit at the contour's level, the
         * distance is held rather than blown up into a basin.
         */
        private const val STEEPEST_GRADIENT = 6.0
        private const val FLATTEST_GRADIENT = 0.6

        private const val PRESENCE_OCTAVE = 0
        private val PRESENCE_AMPLITUDES = listOf(1.0, 0.5)
        private const val PRESENCE_STRETCH = 1400.0
        private const val PRESENCE_FADE = 0.3
        private const val NARROWEST_HEAD = 0.45
        private const val PRESENCE_SALT = 0x9E5_E7CEL

        private const val FLANK_OCTAVE = 0
        private val FLANK_AMPLITUDES = listOf(1.0, 0.5)
        private const val FLANK_STRETCH = 160.0
        private const val FLANK_SEPARATION = 512.0
        private const val COURSE_SEPARATION = 2048.0
        private const val FLANK_SALT = 0xF1A_4C5L

        private const val WALL_OCTAVE = -4
        private val WALL_AMPLITUDES = listOf(1.0, 0.5, 0.25)
        private const val WALL_SALT = 0x3A9_C0DEL

        private const val BED_OCTAVE = 0
        private val BED_AMPLITUDES = listOf(1.0, 0.5)
        private const val BED_STRETCH = 40.0
        private const val BED_SALT = 0x8ED_B0DL

        val CODEC: MapCodec<WanderingCanyons> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                CanyonCourse.CODEC.listOf().fieldOf("courses").forGetter(WanderingCanyons::courses),
                Codec.INT.fieldOf("floor_y").forGetter(WanderingCanyons::floorY),
                Codec.INT.fieldOf("rim_y").forGetter(WanderingCanyons::rimY),
                Codec.LONG.fieldOf("seed").forGetter(WanderingCanyons::seed),
                CanyonProfile.MAP_CODEC.forGetter(WanderingCanyons::profile),
                Codec.DOUBLE.optionalFieldOf("roughness", Canyon.DEFAULT_ROUGHNESS).forGetter(WanderingCanyons::roughness),
                Codec.DOUBLE.optionalFieldOf("flank_variation", Canyon.DEFAULT_FLANK_VARIATION)
                    .forGetter(WanderingCanyons::flankVariation),
                Codec.DOUBLE.optionalFieldOf("bed_relief", Canyon.DEFAULT_BED_RELIEF).forGetter(WanderingCanyons::bedRelief),
            ).apply(instance, ::WanderingCanyons)
        }
    }
}
