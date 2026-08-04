package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.age.aspect.Span
import net.minecraft.core.Holder
import net.minecraft.world.level.levelgen.VerticalAnchor
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration
import net.minecraft.world.level.levelgen.feature.configurations.VegetationPatchConfiguration
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import net.minecraft.world.level.levelgen.placement.PlacementModifier
import kotlin.math.roundToInt

/**
 * What an Age's features are *like*, as opposed to how many of them there are ([FeatureDensity]).
 *
 * Three knobs, chosen from a survey of every placement modifier and configuration 26.1.2 ships (design
 * §7.2): how big one ore vein is, how thickly a vegetation patch fills, and how deep in the column a thing
 * sits.
 * Each is a single number in vanilla's own data, and each is reached by **rebuilding the record that holds
 * it** — a public constructor and no codec surgery, which is why these three and not the other thirty.
 *
 * **They apply to everything the Age places**, named or not: a dial is about how this world makes things,
 * where a rung is about one of them. A feature the knob does not fit — a lake, for a vein size — comes
 * back untouched rather than approximated.
 */
object FeatureShape {

    /** Whether any of these were turned at all, so an Age nobody steered rebuilds nothing. */
    fun asksForNothing(size: Double?, thickness: Double?, height: Double?): Boolean =
        size == null && thickness == null && height == null

    fun reshaped(
        feature: Holder<PlacedFeature>,
        size: Double?,
        thickness: Double?,
        height: Double?,
    ): Holder<PlacedFeature> {
        val placed = feature.value()
        val configured = withConfiguration(placed.feature(), size, thickness)
        val placement = withHeight(placed.placement(), height)
        if (configured === placed.feature() && placement === placed.placement()) return feature
        return Holder.direct(PlacedFeature(configured, placement))
    }

    /**
     * The configured feature with its own shape rebuilt — an ore's vein size, a patch's fill.
     *
     * Both are one number in a record with a public constructor, so this needs neither a codec round trip
     * nor a widener. Anything else is returned as it stands: thirty-odd configuration types exist and
     * guessing at one we did not survey would be worse than declining.
     */
    private fun withConfiguration(
        configured: Holder<ConfiguredFeature<*, *>>,
        size: Double?,
        thickness: Double?,
    ): Holder<ConfiguredFeature<*, *>> {
        val feature = configured.value()
        val rebuilt = when (val configuration = feature.config()) {
            is OreConfiguration -> size?.let {
                OreConfiguration(
                    configuration.targetStates,
                    scaled(configuration.size, it, MOST_OF_A_VEIN),
                    configuration.discardChanceOnAirExposure,
                )
            }

            is VegetationPatchConfiguration -> thickness?.let {
                VegetationPatchConfiguration(
                    configuration.replaceable,
                    configuration.groundState,
                    configuration.vegetationFeature,
                    configuration.surface,
                    configuration.depth,
                    configuration.extraBottomBlockChance,
                    configuration.verticalRange,
                    scaledChance(configuration.vegetationChance, it),
                    configuration.xzRadius,
                    configuration.extraEdgeColumnChance,
                )
            }

            else -> null
        } ?: return configured
        // The generics are the record's own: a `ConfiguredFeature<FC, F>` pairs a configuration with the
        // feature that reads it, and rebuilding one loses the pairing the compiler was tracking. The
        // configuration came out of this very feature, so the pair is sound.
        @Suppress("UNCHECKED_CAST")
        val paired = ConfiguredFeature(feature.feature() as Feature<FeatureConfiguration>, rebuilt)
        return Holder.direct(paired)
    }

    /**
     * The placement with its height band moved — **replacing an existing one, never adding a new one**.
     *
     * A modifier list is a stream transform, so a height range appended to a feature that has none simply
     * overrides whatever put it there: a tree that follows the heightmap would be torn off the ground and
     * buried. So a feature with no band of its own is one this knob has nothing to say about.
     *
     * The replacement is a uniform band, which loses the triangular distribution vanilla gives its ores.
     * That is the trade: a writer can say *where* a thing sits, and cannot say what shape the seam has.
     */
    private fun withHeight(placement: List<PlacementModifier>, height: Double?): List<PlacementModifier> {
        if (height == null || placement.none { it is HeightRangePlacement }) return placement
        val middle = DEEPEST + Span.NATURAL.fractionOf(height) * (HIGHEST - DEEPEST)
        val band = HeightRangePlacement.of(
            UniformHeight.of(
                VerticalAnchor.absolute((middle - HALF_A_BAND).roundToInt()),
                VerticalAnchor.absolute((middle + HALF_A_BAND).roundToInt()),
            ),
        )
        return placement.map { if (it is HeightRangePlacement) band else it }
    }

    /** [ordinary] moved by [dial], read across the axis every span shares, and kept somewhere sane. */
    private fun scaled(ordinary: Int, dial: Double, most: Int): Int {
        val factor = FAINTEST + Span.NATURAL.fractionOf(dial) * (RICHEST - FAINTEST)
        return (ordinary * factor).roundToInt().coerceIn(1, most)
    }

    /** A knob at the bottom of its axis leaves a quarter of what there was; at the top, four times. */
    private const val FAINTEST = 0.25
    private const val RICHEST = 4.0

    /** Vanilla's largest vein is 20-odd blocks, so this is generous rather than a real bound. */
    private const val MOST_OF_A_VEIN = 64

    /** A chance stays a chance: a knob may fill a patch or thin it, never take it past certain. */
    private fun scaledChance(ordinary: Float, dial: Double): Float {
        val factor = FAINTEST + Span.NATURAL.fractionOf(dial) * (RICHEST - FAINTEST)
        return (ordinary * factor).toFloat().coerceIn(0.05f, 1.0f)
    }

    /** The band a steered feature sits in — the world's floor to well above the surface. */
    private const val DEEPEST = -56.0
    private const val HIGHEST = 200.0
    private const val HALF_A_BAND = 32.0
}
