package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.age.aspect.Rung
import net.minecraft.core.Holder
import net.minecraft.world.level.levelgen.placement.CountPlacement
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import net.minecraft.world.level.levelgen.placement.RarityFilter
import kotlin.math.roundToInt

/**
 * How often an Age grows a thing — a rung applied to a feature's **placement**.
 *
 * **Placement, not configuration.** A `ConfiguredFeature` says what to build and knows nothing about how
 * many; a `PlacedFeature` is that plus a list of `PlacementModifier`s, and the list is a *stream
 * transform*: it starts with one position, the chunk origin, and each modifier maps it to however many
 * positions it wants. `CountPlacement(7)` is what makes seven diamond veins out of one chunk.
 *
 * So more of something is another modifier at the **front** of that list, and less of it is a rarity
 * filter there — which is exactly how vanilla writes its own rare ores (`rareOrePlacement` puts a
 * `RarityFilter` first and a common one puts a `CountPlacement` first). Prepending rather than editing is
 * what keeps this out of every feature's private configuration: nothing here has to know what a modifier
 * list already holds, and a feature counted by noise or by layer scales like any other.
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
        val asOftenAsAsked = if (density > Rung.ORDINARY) {
            CountPlacement.of((density.roundToInt()).coerceAtLeast(TWICE))
        } else {
            RarityFilter.onAverageOnceEvery((ONE / density).roundToInt().coerceAtLeast(TWICE))
        }
        val placed = feature.value()
        return Holder.direct(PlacedFeature(placed.feature(), listOf(asOftenAsAsked) + placed.placement()))
    }

    /**
     * The least a rung can ask for and still mean anything: a count of one is what the feature already
     * had, and a rarity of one is every chunk. Rounding to either would be a parameter that did nothing, so the
     * faintest ask still doubles or halves.
     */
    private const val TWICE = 2

    private const val ONE = 1.0
}
