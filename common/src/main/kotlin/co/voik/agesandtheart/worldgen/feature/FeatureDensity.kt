package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.age.aspect.Rung
import net.minecraft.core.Holder
import net.minecraft.util.random.Weighted
import net.minecraft.util.random.WeightedList
import net.minecraft.util.valueproviders.ConstantInt
import net.minecraft.util.valueproviders.IntProvider
import net.minecraft.util.valueproviders.WeightedListInt
import net.minecraft.world.level.levelgen.placement.CountPlacement
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * How often an Age grows a thing — a rung applied to a feature's **placement**.
 *
 * **Placement, not configuration.** A `ConfiguredFeature` says what to build and knows nothing about how
 * many; a `PlacedFeature` is that plus a list of `PlacementModifier`s, and the list is a *stream
 * transform*: it starts with one position, the chunk origin, and each modifier maps it to however many
 * positions it wants. `CountPlacement(7)` is what makes seven diamond veins out of one chunk.
 *
 * So both more of something and less of it are one modifier at the **front** of that list: a count whose
 * mean is the amount asked for, which lays the feature twice as often at two and four times in five at
 * 0.8. Prepending rather than editing is what keeps this out of every feature's private configuration:
 * nothing here has to know what a modifier list already holds, and a feature counted by noise or by layer
 * scales like any other.
 *
 * **A count rather than vanilla's rarity filter for the thin end**, though vanilla writes its own rare
 * ores with one. A filter is an integer *divisor*, so the only amounts it can say are a half, a third, a
 * quarter — and the amounts a sentence actually produces are things like 0.9, which rounded to the
 * nearest expressible filter is a half. Nine tenths became a half, and the scale the tag weights were
 * building collapsed to a two-rung ladder at the last step.
 *
 * **Features do not compete.** Twice the trees is twice the trees, and it takes nothing from the ores —
 * unlike a biome, where every column has one and more of something is necessarily less of another. That is
 * why this is a [Rung] and not a share.
 */
object FeatureDensity {
    /**
     * [feature] rebuilt to occur as often as [density] asks, or [feature] itself at [Rung.ORDINARY].
     *
     * **A rebuilt feature is a *direct* holder**, no registry having heard of it — which is fine here for
     * the same reason it is fine for a rescaled structure set: nothing asks a placed feature its name
     * except a crash report, which falls back to `toString`.
     */
    fun applied(feature: Holder<PlacedFeature>, density: Double): Holder<PlacedFeature> {
        if (Rung.isOrdinary(density)) return feature
        val placed = feature.value()
        // A vein is read off noise over the whole world, so laying it twice lays the same blocks twice.
        // More of it is more of the ground it runs through.
        val vein = placed.feature().value() as? OreVein
        if (vein != null) {
            val richer = vein.copy(abundance = vein.abundance * density)
            return Holder.direct(PlacedFeature(Holder.direct(richer), placed.placement()))
        }
        val asOftenAsAsked = CountPlacement.of(timesOver(density))
        return Holder.direct(PlacedFeature(placed.feature(), listOf(asOftenAsAsked) + placed.placement()))
    }

    /**
     * How many times over to lay the feature — a provider whose **mean is exactly [density]**, which is
     * what makes the amount a scale rather than a ladder.
     *
     * A whole number is that number every time. Anything between is the two whole numbers either side of
     * it, weighted so they average out: 1.2 is four ones to one two, and 0.9 is nine ones to one nothing.
     * Both directions are the same mechanism, which is why there is no rarity filter here any more — a
     * count of zero lays nothing, and `RepeatingPlacement` is an `IntStream.range` that is happy to be
     * empty.
     *
     * **[SHARES] is a hundred because [Rung.legible] rounds an amount to two decimals**, so every amount a
     * claim can hold is expressible here exactly and nothing is lost twice.
     *
     * Public so the mean can be checked without a world under it, as [SpilledSpring.spill] is.
     */
    fun timesOver(density: Double): IntProvider {
        val wanted = density.coerceAtLeast(NONE)
        val fewest = floor(wanted).toInt()
        val overshoot = ((wanted - fewest) * SHARES).roundToInt()
        if (overshoot == 0) return ConstantInt.of(fewest)
        if (overshoot >= SHARES) return ConstantInt.of(fewest + 1)
        return WeightedListInt(
            WeightedList.of(
                Weighted(ConstantInt.of(fewest), SHARES - overshoot),
                Weighted(ConstantInt.of(fewest + 1), overshoot),
            ),
        )
    }

    /** How finely a fraction may be split, matching what [Rung.legible] keeps. */
    private const val SHARES = 100

    private const val NONE = 0.0
}
