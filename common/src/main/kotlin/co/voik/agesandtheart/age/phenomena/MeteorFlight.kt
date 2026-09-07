package co.voik.agesandtheart.age.phenomena

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything about one body of a storm, worked out from the storm and the body's number and nothing else.
 *
 * **This is what makes a light in the sky and a rock in the air the same object** (Jonah, 2026-09-06). The
 * telegraph used to be a decorative count — a few lights that grew and then stopped, while the storm went
 * on dropping bodies that had never been announced. Every light now *is* a body: the client draws the ones
 * that have not fallen yet and the server drops them at the instant their light goes out.
 *
 * **A pure function of the storm and an index**, so neither side is told anything. The server needs where
 * and when; the client needs where in the sky and how soon; both compute the same answer from a storm's
 * own identifier, which vanilla already sends with the entity.
 */
data class MeteorFlight(
    /** Which tick of the storm's life this one falls on. */
    val fallsAt: Int,
    /** The bearing it arrives *from*, in radians — so the sky can hang its light where it is coming from. */
    val comingFrom: Double,
    /**
     * How far above the horizontal it comes in, in radians.
     *
     * **Ten to forty-five degrees** (Jonah), never straight down: a body dropped from overhead is on screen
     * for a moment and reads as a falling block, where one entering obliquely crosses the sky and reads as
     * something arriving from somewhere else.
     */
    val entryAngle: Double,
    /** Where it is aimed, as an offset from the storm's own middle. */
    val landsAwayX: Double,
    val landsAwayZ: Double,
) {

    companion object {

        /**
         * The [number]th body of a storm identified by [storm].
         *
         * [spread] is how many ticks the whole fall runs over and [count] how many bodies it drops, which
         * together space them evenly; the jitter on top of that is what stops the shower being a metronome.
         */
        fun of(storm: Long, number: Int, count: Int, spread: Int, startingAt: Int): MeteorFlight {
            val evenly = if (count <= ONE) NONE else number.toDouble() / (count - ONE)
            val jitter = mixed(storm, number, WHEN_SALT)
            val fallsAt = startingAt + (evenly * spread + (jitter - HALF) * WANDER).toInt()
            // Aimed at a disc rather than a square, and evenly over it — the square's corners lay outside
            // the ring the storm lights, so a body could land where nothing had warned it would.
            val away = sqrt(mixed(storm, number, ACROSS_SALT)) * MeteorStorm.REACH
            val round = mixed(storm, number, ALONG_SALT) * FULL_TURN
            return MeteorFlight(
                fallsAt = fallsAt.coerceAtLeast(startingAt),
                comingFrom = bearingOf(storm) + (mixed(storm, number, BEARING_SALT) - HALF) * FANS_OUT,
                entryAngle = angleOf(storm) + (mixed(storm, number, ANGLE_SALT) - HALF) * FANS_OUT,
                landsAwayX = cos(round) * away,
                landsAwayZ = sin(round) * away,
            )
        }

        /**
         * The bearing the **whole storm** comes in on, which every one of its bodies varies only slightly
         * off (Jonah, 2026-09-06).
         *
         * **This is what sells the first half of the arrival.** Bodies each drawing their own bearing came
         * in from all over the sky, so the approach read as unrelated lights rather than as one thing
         * breaking up — and "it splits into multiple" only means anything if they were together first.
         * What separates them by the end is the disc they are aimed at closing; [FANS_OUT] is the wobble.
         */
        fun bearingOf(storm: Long): Double = mixed(storm, WHOLE_STORM, STORM_BEARING_SALT) * FULL_TURN

        /** And the angle it comes in at, ten to forty-five degrees off the horizontal. */
        fun angleOf(storm: Long): Double =
            SHALLOWEST + mixed(storm, WHOLE_STORM, STORM_ANGLE_SALT) * (STEEPEST - SHALLOWEST)

        /**
         * A number in nought to one for this storm, body and purpose.
         *
         * A plain integer mix rather than a random source: it must give the same answer on a client and a
         * server that never spoke about it, which nothing carrying its own state can promise.
         */
        private fun mixed(storm: Long, number: Int, salt: Long): Double {
            var value = storm * PRIME_ONE + number * PRIME_TWO + salt
            value = value xor (value ushr 33)
            value *= PRIME_THREE
            value = value xor (value ushr 29)
            value *= PRIME_FOUR
            value = value xor (value ushr 32)
            return (value ushr SPARE_BITS).toDouble() / KEPT
        }

        /** How far a body's moment may wander from its even share, in ticks. */
        private const val WANDER = 14.0

        /**
         * How far a body strays from its storm's own line, in radians — **rather under a degree**.
         *
         * Nearly nothing, because the separation a watcher sees is not this: the bodies are aimed across a
         * disc [MeteorStorm.REACH] wide and hang far enough out that the disc is under two degrees, so
         * they come apart on their own as they close — from about one and a half degrees at first sighting
         * to five and thirty at the arrival. This is only the wobble on top of that, and it is *angular*
         * rather than a displacement, so unlike the disc it does not shrink with how far out they hang.
         * A walk read fifteen times as much as lights arriving from all over rather than one thing
         * splitting.
         */
        private const val FANS_OUT = 0.02

        /** The index the storm's own answers are mixed at, which no body can take. */
        private const val WHOLE_STORM = -1

        private const val STORM_BEARING_SALT = 0x4D_45_54_36L
        private const val STORM_ANGLE_SALT = 0x4D_45_54_37L

        private val SHALLOWEST = Math.toRadians(10.0)
        private val STEEPEST = Math.toRadians(45.0)

        private const val WHEN_SALT = 0x4D_45_54_31L
        private const val BEARING_SALT = 0x4D_45_54_32L
        private const val ANGLE_SALT = 0x4D_45_54_33L
        private const val ACROSS_SALT = 0x4D_45_54_34L
        private const val ALONG_SALT = 0x4D_45_54_35L

        private val PRIME_ONE = 0x9E3779B97F4A7C15uL.toLong()
        private val PRIME_TWO = 0xBF58476D1CE4E5B9uL.toLong()
        private val PRIME_THREE = 0xFF51AFD7ED558CCDuL.toLong()
        private val PRIME_FOUR = 0xC4CEB9FE1A85EC53uL.toLong()

        private const val SPARE_BITS = 11
        private const val KEPT = (1L shl 53).toDouble()

        private const val ONE = 1
        private const val NONE = 0.0
        private const val HALF = 0.5
        private const val FULL_TURN = 2.0 * PI
    }

    /** Where this one enters, as an offset from the point it is aimed at. */
    fun entryOffset(slantRange: Double): Triple<Double, Double, Double> {
        val across = cos(entryAngle) * slantRange
        return Triple(cos(comingFrom) * across, sin(entryAngle) * slantRange, sin(comingFrom) * across)
    }
}
