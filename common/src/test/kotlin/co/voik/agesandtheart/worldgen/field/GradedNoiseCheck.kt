package co.voik.agesandtheart.worldgen.field

import io.kotest.core.spec.style.FunSpec

/**
 * What a graded [Noise3D] buys over a level one, stated as the difference between them: **a level
 * threshold has no up.**
 *
 * The failure this guards against is silent and looks fine in a screenshot — a world that is equally
 * likely to be rock at the bedrock and at the cloud line still renders as *something*, and from inside a
 * pocket of it there is no way to tell foam from ground. It is only visible in the profile of how much of
 * each height is solid, which is what these read.
 */
class GradedNoiseCheck : FunSpec({

    val lowY = 0
    val highY = 200

    fun sample(field: Noise3D, y: Int): Double {
        val columns = (-120..120 step 5).flatMap { x -> (-120..120 step 5).map { z -> x to z } }
        return columns.count { (x, z) -> field.columnSpans(x, z).contains(y) }.toDouble() / columns.size
    }

    fun noise(thresholdAtTop: Double?) = Noise3D(
        seed = 0x62_ADEDL,
        firstOctave = -7,
        amplitudes = listOf(1.0, 0.5),
        scaleX = 2.0,
        scaleY = 1.0,
        scaleZ = 2.0,
        character = NoiseCharacter.PLAIN,
        threshold = -0.95,
        lowY = lowY,
        highY = highY,
        thresholdAtTop = thresholdAtTop,
    )

    test("a level threshold fills the top of its band as readily as the bottom") {
        val level = noise(thresholdAtTop = null)
        val low = sample(level, lowY + 10)
        val high = sample(level, highY - 10)
        check(low - high < TELLS_ONE_END_FROM_THE_OTHER) {
            "A level field was $low solid low and $high high, which is a landscape and should not be"
        }
    }

    test("a graded threshold is nearly solid at its floor and nearly empty at its ceiling") {
        val graded = noise(thresholdAtTop = 1.05)
        val low = sample(graded, lowY + 10)
        val high = sample(graded, highY - 10)
        check(low > MOSTLY_SOLID) { "The graded field was only $low solid at its floor, so it has no ground" }
        check(high < MOSTLY_EMPTY) { "The graded field was still $high solid at its ceiling, so it has no sky" }
    }

    test("the solid share falls all the way up, so there is one surface rather than several") {
        val graded = noise(thresholdAtTop = 1.05)
        val profile = (lowY..highY step 20).map { y -> y to sample(graded, y) }
        for ((below, above) in profile.zipWithNext()) {
            check(above.second <= below.second + RISE_THAT_IS_ONLY_NOISE) {
                "Solidity rose from ${below.second} at y=${below.first} to ${above.second} at y=${above.first}"
            }
        }
    }

    test("grading leaves the band's ends where they were, so nothing spills past them") {
        val graded = noise(thresholdAtTop = 1.05)
        val outside = listOf(lowY - 1, highY + 1)
        for ((x, z) in listOf(0 to 0, 37 to -61, -104 to 88)) {
            val spans = graded.columnSpans(x, z)
            for (y in outside) {
                check(!spans.contains(y)) { "($x, $z) was solid at y=$y, outside the band $lowY..$highY" }
            }
        }
    }
}) {
    private companion object {
        /** How far apart the two ends have to be before the field can be said to know which way is up. */
        const val TELLS_ONE_END_FROM_THE_OTHER = 0.25
        const val MOSTLY_SOLID = 0.9
        const val MOSTLY_EMPTY = 0.1

        // A surface is not a plane, so a step may rise a little where the terrain happens to lean up.
        const val RISE_THAT_IS_ONLY_NOISE = 0.06
    }
}
