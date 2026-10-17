package co.voik.agesandtheart.worldgen.feature

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.mojang.serialization.Codec
import com.mojang.serialization.JsonOps
import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.HolderSet
import net.minecraft.util.valueproviders.ConstantFloat
import net.minecraft.util.valueproviders.ConstantInt
import net.minecraft.util.valueproviders.FloatProvider
import net.minecraft.util.valueproviders.IntProvider
import net.minecraft.util.valueproviders.UniformFloat
import net.minecraft.util.valueproviders.UniformInt
import net.minecraft.world.level.levelgen.feature.CoralTreeFeature
import net.minecraft.world.level.levelgen.feature.DeltaFeature
import net.minecraft.world.level.levelgen.feature.DiskFeature
import net.minecraft.world.level.levelgen.feature.FallenTreeFeature
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.HugeBrownMushroomFeature
import net.minecraft.world.level.levelgen.feature.HugeRedMushroomFeature
import net.minecraft.world.level.levelgen.feature.LargeDripstoneFeature
import net.minecraft.world.level.levelgen.feature.RandomBooleanSelectorFeature
import net.minecraft.world.level.levelgen.feature.RandomSelectorFeature
import net.minecraft.world.level.levelgen.feature.RootSystemFeature
import net.minecraft.world.level.levelgen.feature.SequenceFeature
import net.minecraft.world.level.levelgen.feature.SimpleRandomSelectorFeature
import net.minecraft.world.level.levelgen.feature.SpeleothemClusterFeature
import net.minecraft.world.level.levelgen.feature.SteppedColumnClusterFeature
import net.minecraft.world.level.levelgen.feature.TreeFeature
import net.minecraft.world.level.levelgen.feature.WeightedPlacedFeature
import net.minecraft.world.level.levelgen.feature.WeightedRandomSelectorFeature
import net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacer
import net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import kotlin.math.roundToInt

/**
 * Features resized through their own records: a tree's trunk and crown, a huge mushroom's cap, the reach of a
 * disk, a delta or a cluster of columns, a dripstone's girth. A chooser among features resizes each feature it
 * chooses between. What is not here is resized whole by [ScaledFeature].
 */
object ResizedFeatures {

    /**
     * [feature] at [factor] times its size, or null where its record says nothing about how big it is.
     * [resized] resizes a feature it chooses between, and [registries] lets a tree's placers be rewritten
     * through their codecs, which name block tags.
     */
    fun of(
        feature: Feature,
        factor: Double,
        resized: (Holder<PlacedFeature>) -> Holder<PlacedFeature>,
        registries: HolderLookup.Provider?,
    ): Feature? = when (feature) {
        is TreeFeature -> tree(feature, factor, registries)
        is HugeRedMushroomFeature -> HugeRedMushroomFeature(
            feature.capProvider(), feature.stemProvider(), scaled(feature.foliageRadius(), factor, MOST_REACH), feature.canPlaceOn(),
        )
        is HugeBrownMushroomFeature -> HugeBrownMushroomFeature(
            feature.capProvider(), feature.stemProvider(), scaled(feature.foliageRadius(), factor, MOST_REACH), feature.canPlaceOn(),
        )
        is DiskFeature -> DiskFeature(
            feature.stateProvider(), feature.target(), scaled(feature.radius(), factor, MOST_REACH),
            scaled(feature.halfHeight(), factor, MOST_REACH),
        )
        is DeltaFeature -> DeltaFeature(
            feature.contents(), feature.rim(), scaled(feature.size(), factor, MOST_REACH), scaled(feature.rimSize(), factor, MOST_REACH),
        )
        is SteppedColumnClusterFeature -> SteppedColumnClusterFeature(
            feature.block(), feature.continueThrough(), feature.canReplace(), feature.cannotPlaceOn(),
            scaled(feature.clusterReach(), factor, MOST_REACH), feature.columnCount(),
            scaled(feature.columnReach(), factor, MOST_REACH), scaled(feature.height(), factor, MOST_HEIGHT),
        )
        is SpeleothemClusterFeature -> SpeleothemClusterFeature(
            feature.baseBlock(), feature.pointedBlock(), feature.replaceableBlocks(), feature.floorToCeilingSearchRange(),
            scaled(feature.height(), factor, MOST_HEIGHT), scaled(feature.radius(), factor, MOST_REACH),
            feature.maxStalagmiteStalactiteHeightDiff(), feature.heightDeviation(), feature.speleothemBlockLayerThickness(),
            feature.density(), feature.wetness(), feature.chanceOfSpeleothemAtMaxDistanceFromCenter(),
            feature.maxDistanceFromEdgeAffectingChanceOfSpeleothem(), feature.maxDistanceFromCenterAffectingHeightBias(),
        )
        is LargeDripstoneFeature -> LargeDripstoneFeature(
            feature.replaceableBlocks(), feature.floorToCeilingSearchRange(), scaled(feature.columnRadius(), factor, MOST_REACH),
            scaled(feature.heightScale(), factor), feature.maxColumnRadiusToCaveHeightRatio(), feature.stalactiteBluntness(),
            feature.stalagmiteBluntness(), feature.windSpeed(), feature.minRadiusForWind(), feature.minBluntnessForWind(),
        )
        is FallenTreeFeature -> FallenTreeFeature(
            feature.trunkProvider(), scaled(feature.logLength(), factor, MOST_REACH), feature.stumpDecorators(), feature.logDecorators(),
        )
        is RootSystemFeature -> RootSystemFeature(
            resized(feature.treeFeature()), feature.requiredVerticalSpaceForTree(), feature.levelTestDistance(),
            feature.maxLevelDeviation(), feature.rootRadius(), feature.rootReplaceable(), feature.rootStateProvider(),
            feature.rootPlacementAttempts(), feature.rootColumnMaxHeight(), feature.hangingRootRadius(),
            feature.hangingRootsVerticalSpan(), feature.hangingRootStateProvider(), feature.hangingRootPlacementAttempts(),
            feature.allowedVerticalWaterForTree(), feature.allowedTreePosition(),
        )
        is RandomSelectorFeature -> RandomSelectorFeature(
            feature.features().map { WeightedPlacedFeature(resized(it.feature()), it.chance()) }, resized(feature.defaultFeature()),
        )
        is RandomBooleanSelectorFeature -> RandomBooleanSelectorFeature(resized(feature.featureTrue()), resized(feature.featureFalse()))
        is SimpleRandomSelectorFeature -> SimpleRandomSelectorFeature(HolderSet.direct(feature.features().map(resized)))
        is WeightedRandomSelectorFeature -> WeightedRandomSelectorFeature(feature.features().map(resized))
        is SequenceFeature -> SequenceFeature(HolderSet.direct(feature.features().map(resized)))
        is CoralTreeFeature -> CoralTreeFeature(resized(feature.feature()))
        else -> null
    }

    /**
     * A tree with its trunk and crown scaled. Each of vanilla's dozen trunk and foliage placers keeps its
     * heights and radii under its own names, so they are rewritten through the placer's codec by name, and a
     * placer that does not read back is kept as it was.
     */
    private fun tree(tree: TreeFeature, factor: Double, registries: HolderLookup.Provider?): TreeFeature {
        val trunk = rescaled(TrunkPlacer.CODEC, tree.trunkPlacer(), factor, TRUNK_MEASURES, registries) ?: tree.trunkPlacer()
        val crown = rescaled(FoliagePlacer.CODEC, tree.foliagePlacer(), factor, CROWN_MEASURES, registries) ?: tree.foliagePlacer()
        return TreeFeature(
            tree.trunkProvider(), trunk, tree.foliageProvider(), crown, tree.rootPlacer(), tree.minimumSize(),
            tree.decorators(), tree.ignoreVines(), tree.belowTrunkProvider(),
        )
    }

    private fun <T : Any> rescaled(
        codec: Codec<T>,
        value: T,
        factor: Double,
        measures: Map<String, Measure>,
        registries: HolderLookup.Provider?,
    ): T? {
        val ops = registries?.createSerializationContext(JsonOps.INSTANCE) ?: JsonOps.INSTANCE
        val written = codec.encodeStart(ops, value).result().orElse(null) as? JsonObject ?: return null
        for ((name, measure) in measures) {
            val held = written.get(name) ?: continue
            written.add(name, scaledJson(held, measure.scaledBy(factor), measure.most))
        }
        return codec.parse(ops, written).result().orElse(null)
    }

    /** A number, or every number an int provider is written with, scaled and kept within [most]. */
    private fun scaledJson(element: JsonElement, factor: Double, most: Int): JsonElement = when {
        element is JsonPrimitive && element.isNumber -> JsonPrimitive(scaled(element.asInt, factor, most))
        element is JsonObject -> JsonObject().also { scaledObject ->
            for ((key, inner) in element.entrySet()) {
                scaledObject.add(key, if (key in PROVIDER_NUMBERS || inner is JsonObject) scaledJson(inner, factor, most) else inner)
            }
        }
        else -> element
    }

    private fun scaled(ordinary: Int, factor: Double, most: Int): Int {
        val least = minOf(ordinary, 1)
        return (ordinary * factor).roundToInt().coerceIn(least, maxOf(most, ordinary))
    }

    /** A provider's range scaled; one of an unusual shape becomes the uniform range over its bounds. */
    private fun scaled(provider: IntProvider, factor: Double, most: Int): IntProvider {
        val least = scaled(provider.minInclusive(), factor, most)
        val greatest = scaled(provider.maxInclusive(), factor, most)
        return if (least == greatest) ConstantInt.of(least) else UniformInt.of(least, greatest)
    }

    private fun scaled(provider: FloatProvider, factor: Double): FloatProvider {
        val least = (provider.min() * factor).toFloat()
        val greatest = (provider.max() * factor).toFloat()
        return if (least == greatest) ConstantFloat.of(least) else UniformFloat.of(least, greatest)
    }

    /** How a placer's measure scales: along with the feature, or with its area, as a count of leaves does. */
    private data class Measure(val most: Int, val byArea: Boolean = false) {
        fun scaledBy(factor: Double): Double = if (byArea) factor * factor else factor
    }

    /** Vanilla's own ceilings on a trunk's height, which its codec refuses to read past. */
    private val TRUNK_MEASURES = mapOf(
        "base_height" to Measure(most = 32),
        "height_rand_a" to Measure(most = 24),
        "height_rand_b" to Measure(most = 24),
    )

    /** And on a crown's: its radius and offset, and whichever height a placer of its kind keeps. */
    private val CROWN_MEASURES = mapOf(
        "radius" to Measure(most = 16),
        "offset" to Measure(most = 16),
        "height" to Measure(most = 16),
        "trunk_height" to Measure(most = 24),
        "crown_height" to Measure(most = 24),
        "foliage_height" to Measure(most = 512),
        "leaf_placement_attempts" to Measure(most = 256, byArea = true),
    )

    /** The fields an int provider is written with. */
    private val PROVIDER_NUMBERS = setOf("value", "min_inclusive", "max_inclusive", "min", "max")

    /** As far from its middle as a feature may reach and still write only in the chunks next door. */
    private const val MOST_REACH = 16

    /** As tall as a resized column or cluster is let grow. */
    private const val MOST_HEIGHT = 48
}
