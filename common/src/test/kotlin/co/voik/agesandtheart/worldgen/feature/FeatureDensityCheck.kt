package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.aspect.Rung
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.util.valueproviders.ConstantInt
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import kotlin.math.abs

/**
 * How often an Age lays a thing, which has to be a **scale**.
 *
 * The amount arriving here is a sum of tag weights rounded to two decimals, so it is very rarely whole:
 * `1.2`, `0.9`, `2.4`. What it used to become was `CountPlacement.of(2)` for everything from just above
 * ordinary to 2.49, and `RarityFilter.onAverageOnceEvery(2)` for everything from 0.4 to 1.0 — a two-rung
 * ladder at the last step of a pipeline built to be continuous. These are the checks that would notice it
 * happening again.
 *
 * `WeightedList` reaches for a registry as it is built, so this needs the bootstrap even though every
 * question it asks is arithmetic.
 */
@Tags(NEEDS_REGISTRIES)
class FeatureDensityCheck : FunSpec({

    /** Enough that the mean is settled to three decimals, and seeded, so it is the same run to run. */
    val draws = 200_000

    fun meanOf(density: Double): Double {
        val random = XoroshiroRandomSource(density.toRawBits())
        val provider = FeatureDensity.timesOver(density)
        var total = 0L
        repeat(draws) { total += provider.sample(random) }
        return total.toDouble() / draws
    }

    test("what is laid averages out to what was asked for") {
        val asked = listOf(0.1, 0.25, 0.5, 0.75, 0.9, 0.99, 1.2, 1.5, 2.4, 3.75, 7.99)
        for (density in asked) {
            val got = meanOf(density)
            check(abs(got - density) < TOLERANCE) {
                "$density was laid $got times over on average, which is not what it asked for"
            }
        }
    }

    /**
     * The regression this exists for. Nine tenths is not a half, and it used to be: a rarity filter is an
     * integer divisor, so `1 / 0.9` rounded to the nearest one it could say, and every faint thinning in
     * the language became a halving.
     */
    test("a faint thinning stays faint") {
        val faint = meanOf(0.9)
        check(faint > 0.8) { "0.9 thinned to $faint — the old rarity filter would have said 0.5" }
    }

    /** And its mirror: a faint lift is not a doubling. */
    test("a faint lift stays faint") {
        val faint = meanOf(1.2)
        check(faint < 1.5) { "1.2 lifted to $faint — the old integer count would have said 2.0" }
    }

    test("a whole number is that number every time") {
        for (whole in listOf(2.0, 3.0, 8.0)) {
            val provider = FeatureDensity.timesOver(whole)
            check(provider is ConstantInt && provider.value == whole.toInt()) {
                "$whole came back as $provider rather than a constant"
            }
            check(provider.minInclusive() == provider.maxInclusive()) { "$whole varies and should not" }
        }
    }

    /**
     * Nothing is a real answer here — `Features.PLACES` may be emptied — and a count of zero is how it is
     * said, `RepeatingPlacement` being an `IntStream.range` that is content to be empty.
     */
    test("nothing asked for is nothing laid") {
        val provider = FeatureDensity.timesOver(0.0)
        check(provider.maxInclusive() == 0) { "an amount of nothing still lays up to ${provider.maxInclusive()}" }
    }

    test("the ordinary amount rebuilds nothing") {
        check(Rung.isOrdinary(Rung.ORDINARY)) { "ordinary is not ordinary" }
        val provider = FeatureDensity.timesOver(Rung.ORDINARY)
        check(provider is ConstantInt && provider.value == 1) { "ordinary came back as $provider" }
    }
}) {
    private companion object {
        /** Three decimals of a mean over 200,000 draws is comfortably inside this. */
        const val TOLERANCE = 0.02
    }
}
