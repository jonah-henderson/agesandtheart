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
import net.minecraft.world.level.levelgen.GeodeCrackSettings
import net.minecraft.world.level.levelgen.GeodeLayerSettings
import net.minecraft.world.level.levelgen.feature.BlockColumnFeature
import net.minecraft.world.level.levelgen.feature.CoralTreeFeature
import net.minecraft.world.level.levelgen.feature.DeltaFeature
import net.minecraft.world.level.levelgen.feature.DiskFeature
import net.minecraft.world.level.levelgen.feature.FallenTreeFeature
import net.minecraft.world.level.levelgen.feature.GeodeFeature
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
import kotlin.math.sqrt

/**
 * Features resized through their own records: a tree's trunk and crown (and its girth, through [ThickTrunk]), a
 * column's height — sugar cane, cactus — a huge mushroom's cap, a geode's shells, the reach of a disk, a delta or a
 * cluster of columns, a dripstone's girth. A chooser among features resizes each feature it chooses between.
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
        is TreeFeature -> thickened(tree(feature, factor, registries), factor)
        // A layer of at most one block is a cap — a cactus's flower — and a bigger column still wears one.
        is BlockColumnFeature -> BlockColumnFeature(
            feature.layers().map { layer ->
                if (layer.height().maxInclusive() <= 1) layer
                else BlockColumnFeature.Layer(scaled(layer.height(), factor, MOST_HEIGHT), layer.state())
            },
            feature.direction(), feature.allowedPlacement(), feature.prioritizeTip(),
        )
        is HugeRedMushroomFeature -> HugeRedMushroomFeature(
            feature.capProvider(), feature.stemProvider(), scaled(feature.foliageRadius(), factor, MOST_REACH), feature.canPlaceOn(),
        )
        is HugeBrownMushroomFeature -> HugeBrownMushroomFeature(
            feature.capProvider(), feature.stemProvider(), scaled(feature.foliageRadius(), factor, MOST_REACH), feature.canPlaceOn(),
        )
        // By the root and no deeper, since a disk replaces the ground it covers: at the whole factor a colossal one
        // paved the land smooth a chunk across and seventeen blocks down.
        is DiskFeature -> DiskFeature(
            feature.stateProvider(), feature.target(), scaled(feature.radius(), sqrt(factor), MOST_REACH), feature.halfHeight(),
        )
        is GeodeFeature -> geode(feature, factor)
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
     * A tree with its trunk and crown scaled — **up rather than out**, as a jungle tree is big: the trunk and the
     * crown's height by the whole factor, the crown's reach by its root and never past [MOST_CROWN_REACH], so a
     * colossal tree stays inside a chunk. Each of vanilla's dozen trunk and foliage placers keeps its heights and
     * radii under its own names, so they are rewritten through the placer's codec by name, and a placer that does
     * not read back is kept as it was.
     */
    private fun tree(tree: TreeFeature, factor: Double, registries: HolderLookup.Provider?): TreeFeature {
        val trunk = rescaled(TrunkPlacer.CODEC, tree.trunkPlacer(), factor, TRUNK_MEASURES, registries) ?: tree.trunkPlacer()
        val crown = rescaled(FoliagePlacer.CODEC, tree.foliagePlacer(), factor, CROWN_MEASURES, registries) ?: tree.foliagePlacer()
        return TreeFeature(
            tree.trunkProvider(), trunk, tree.foliageProvider(), crown, tree.rootPlacer(), tree.minimumSize(),
            tree.decorators(), tree.ignoreVines(), tree.belowTrunkProvider(),
        )
    }

    /**
     * A geode at [factor] times its size, every shell in proportion, and never more than [MOST_GEODE_GROWTH]: vanilla
     * builds one only inside a box sixteen blocks each way from its origin.
     *
     * Its shells are thresholds on a sum of inverse distances to a few points, so scaling every distance by `g` is
     * the same test with the points' offsets and the shells' sizes (both squared distances) times `g²` and the noise
     * over `g`. The points sit a few blocks off the origin in every axis, which a grown geode cannot afford in its
     * box, so a growing one keeps them close and spreads them only as far apart as they grew.
     */
    private fun geode(geode: GeodeFeature, factor: Double): GeodeFeature {
        val growth = factor.coerceAtMost(MOST_GEODE_GROWTH)
        val squared = growth * growth
        val layers = geode.layerSettings()
        val crack = geode.crackSettings()
        val wall = geode.outerWallDistance()
        val pointsSpread = wall.maxInclusive() - wall.minInclusive()
        val nearerWall = if (growth > 1.0) {
            UniformInt.of(NEAREST_GEODE_POINT, NEAREST_GEODE_POINT + (pointsSpread * growth).roundToInt())
        } else {
            scaled(wall, growth, MOST_REACH)
        }
        return GeodeFeature(
            geode.blockSettings(),
            GeodeLayerSettings(
                layers.filling * squared, layers.innerLayer * squared, layers.middleLayer * squared, layers.outerLayer * squared,
            ),
            GeodeCrackSettings(
                crack.generateCrackChance, crack.baseCrackSize * squared, (crack.crackPointOffset * squared).roundToInt(),
            ),
            geode.usePotentialPlacementsChance(),
            geode.useAlternateLayer0Chance(),
            geode.placementsRequireLayer0Alternate(),
            nearerWall,
            geode.distributionPoints(),
            scaled(geode.pointOffset(), squared, MOST_REACH * MOST_REACH),
            geode.minGenOffset(),
            geode.maxGenOffset(),
            geode.noiseMultiplier() / growth,
            geode.invalidBlocksThreshold(),
        )
    }

    /** [tree] with a trunk as many logs across as its size warrants, where that is more than one. */
    private fun thickened(tree: TreeFeature, factor: Double): Feature {
        val girth = (factor * GIRTH_PER_TIMES_AS_BIG).roundToInt()
        return if (girth > 1) ThickTrunk(Holder.direct(tree), girth) else tree
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
            written.add(name, scaledJson(held, measure.growth.of(factor), measure.most))
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

    /** How a placer's measure grows with the tree: by the whole factor, or by its root, as a crown's reach does. */
    private enum class Growth {
        WHOLE, ROOT;

        fun of(factor: Double): Double = when (this) {
            WHOLE -> factor
            ROOT -> sqrt(factor)
        }
    }

    private data class Measure(val most: Int, val growth: Growth = Growth.WHOLE)

    /** Vanilla's own ceilings on a trunk's height, which its codec refuses to read past. */
    private val TRUNK_MEASURES = mapOf(
        "base_height" to Measure(most = 32),
        "height_rand_a" to Measure(most = 24),
        "height_rand_b" to Measure(most = 24),
    )

    /**
     * And a crown's: its reach by the root, and whichever height a placer of its kind keeps by the whole factor.
     * Its offset is left alone, so the crown stays on its trunk; the count of leaves a scattering placer tries goes
     * with the area its reach covers, which is the whole factor again.
     */
    private val CROWN_MEASURES = mapOf(
        "radius" to Measure(most = MOST_CROWN_REACH, growth = Growth.ROOT),
        "height" to Measure(most = 16),
        "trunk_height" to Measure(most = 24),
        "crown_height" to Measure(most = 24),
        "foliage_height" to Measure(most = 512),
        "leaf_placement_attempts" to Measure(most = 256),
    )

    /** As far as a crown reaches from its trunk: seven each side of a trunk three across is one chunk. */
    private const val MOST_CROWN_REACH = 7

    /** The fields an int provider is written with. */
    private val PROVIDER_NUMBERS = setOf("value", "min_inclusive", "max_inclusive", "min", "max")

    /** How many logs across a trunk is for each time as big as ordinary its tree is: two for large, three for colossal. */
    private const val GIRTH_PER_TIMES_AS_BIG = 0.75

    /**
     * As far from its middle as a feature may reach and still touch only the chunks next door: a chunk's width, less
     * the two blocks past what it writes that a feature reads (a dripstone cluster resized to sixteen read a chunk
     * two away).
     */
    private const val MOST_REACH = 14

    /** As big as a geode grows and still fits the box vanilla builds it in, with its points kept near the origin. */
    private const val MOST_GEODE_GROWTH = 1.8

    /** How near the origin a grown geode's points sit, in every axis; vanilla's are four or five off. */
    private const val NEAREST_GEODE_POINT = 1

    /** As tall as a resized column or cluster is let grow. */
    private const val MOST_HEIGHT = 48
}
