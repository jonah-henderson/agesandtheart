package co.voik.agesandtheart.age.phenomena

import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.PI
import kotlin.math.hypot

/**
 * **That a body comes in at the angle its own light was drawn at.**
 *
 * The sky announces a meteor by drawing a light down its entry line, and the storm then throws a rock
 * along that same line — so the angle a player watches coming in has to be the angle the rock actually
 * flies. It has drifted apart twice now, both times because the two ends of the line were worked out in
 * two places from two different origins, and both times it read as the sky lying rather than as arithmetic
 * being wrong.
 *
 * The origin is structural and is fixed by both sides asking `MeteorStorm.landingOf`. What is checkable
 * without a world is the contract underneath that, which everything else assumes: an entry offset is a
 * point at exactly [MeteorFlight.entryAngle] above its target, at whatever range it was asked for.
 */
class MeteorFlightCheck : FunSpec({

    test("an entry offset stands at exactly the angle it was drawn at, at every range") {
        for (storm in STORMS) {
            for (number in 0..<BODIES_TRIED) {
                val flight = aBody(storm, number)
                for (range in RANGES) {
                    val (across, up, along) = flight.entryOffset(range)
                    val stood = inDegrees(atan2(up, hypot(across, along)))
                    val drawn = inDegrees(flight.entryAngle)
                    check(abs(stood - drawn) < A_HAIR) {
                        "body $number of storm $storm stands at $stood degrees at range $range, " +
                            "where its light is drawn coming in at $drawn"
                    }
                }
            }
        }
    }

    /** And it is the same line at every range — a light closing in must not swing as it comes. */
    test("the line does not swing as the light closes in") {
        for (storm in STORMS) {
            val flight = aBody(storm, 0)
            val far = flight.entryOffset(RANGES.last())
            val near = flight.entryOffset(RANGES.first())
            // Same direction: the near offset is the far one scaled down, so their cross product vanishes.
            val scale = RANGES.first() / RANGES.last()
            check(abs(far.first * scale - near.first) < A_HAIR) { "the line swung across as it closed" }
            check(abs(far.second * scale - near.second) < A_HAIR) { "the line swung up as it closed" }
            check(abs(far.third * scale - near.third) < A_HAIR) { "the line swung round as it closed" }
        }
    }
}) {
    private companion object {
        private val STORMS = listOf(1L, 4242L, -77L, 90210L)
        private const val BODIES_TRIED = 12
        private val RANGES = listOf(150.0, 1200.0, 12000.0)

        /** Whatever a storm happens to be; none of these reach the entry geometry. */
        private fun aBody(storm: Long, number: Int) = MeteorFlight.of(
            storm = storm,
            number = number,
            count = BODIES_TRIED,
            spread = A_FALL,
            startingAt = 0,
            steepness = MeteorFlight.angleOf(storm),
            reach = A_DISC,
        )

        private const val A_FALL = 200
        private const val A_DISC = 90.0

        private fun inDegrees(radians: Double) = radians * 180.0 / PI

        private const val A_HAIR = 1.0e-6
    }
}
