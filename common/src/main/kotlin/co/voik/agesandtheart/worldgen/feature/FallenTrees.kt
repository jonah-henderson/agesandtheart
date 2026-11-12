package co.voik.agesandtheart.worldgen.feature

import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.HolderSet
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.util.valueproviders.UniformInt
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.levelgen.feature.FallenTreeFeature
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.RandomBooleanSelectorFeature
import net.minecraft.world.level.levelgen.feature.RandomSelectorFeature
import net.minecraft.world.level.levelgen.feature.SequenceFeature
import net.minecraft.world.level.levelgen.feature.SimpleRandomSelectorFeature
import net.minecraft.world.level.levelgen.feature.TreeFeature
import net.minecraft.world.level.levelgen.feature.WeightedPlacedFeature
import net.minecraft.world.level.levelgen.feature.WeightedRandomSelectorFeature
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider
import net.minecraft.world.level.levelgen.feature.stateproviders.SimpleStateProvider
import net.minecraft.world.level.levelgen.placement.PlacedFeature

/**
 * Trees lying where they fell — `fallen trees`, `fallen oak`: each tree a feature grows chosen between standing and
 * lying fallen, as vanilla's own forests choose a fallen tree one time in eighty. A tree falls as vanilla's fallen tree
 * of the same log where there is one, mushrooms and all, and otherwise as its fallen oak laid of the tree's own trunk.
 *
 * **A kind falls by its log**, wherever it stands: `fallen oak` names vanilla's lone oak, while the oaks a biome grows
 * stand among birches in a forest's own choice, so the oaks there fall and the birches beside them do not.
 */
object FallenTrees {

    /** The share of the trees that lie fallen. */
    const val SHARE_FALLEN = 0.5f

    /**
     * [feature] with every tree it grows of one of [logs] fallen [SHARE_FALLEN] of the time — every tree, where [logs]
     * is null — or itself where it grows none.
     */
    fun felled(feature: Holder<PlacedFeature>, registries: HolderLookup.Provider, logs: Set<Block>? = null): Holder<PlacedFeature> {
        if (!FeatureShape.growsTrees(feature)) return feature
        val fallenTrees = registries.lookupOrThrow(Registries.FEATURE).listElements()
            .filter { it.value() is FallenTreeFeature }
            .toList()
            .sortedBy { it.key().identifier().toString() }
        return Felling(fallenTrees, logs).placed(feature)
    }

    /** The logs of the trees [feature] grows, wherever they sit among what it chooses between. */
    fun logsOf(feature: Holder<PlacedFeature>): Set<Block> = feature.value().getFeatures()
        .map { it.value() }
        .toList()
        .filterIsInstance<TreeFeature>()
        .mapNotNull { blockOf(it.trunkProvider()) }
        .toSet()

    private fun blockOf(provider: Holder<BlockStateProvider>): Block? = (provider.value() as? SimpleStateProvider)?.state()?.block

    private class Felling(private val fallenTrees: List<Holder.Reference<Feature>>, private val logs: Set<Block>?) {

        fun placed(feature: Holder<PlacedFeature>): Holder<PlacedFeature> {
            val placed = feature.value()
            val held = placed.feature().value()
            val rebuilt = when (held) {
                is TreeFeature -> if (falls(held)) standingOrFallen(placed.feature(), held) else return feature
                else -> chooserOf(held) ?: return feature
            }
            return Holder.direct(PlacedFeature(Holder.direct(rebuilt), placed.placement()))
        }

        private fun falls(tree: TreeFeature): Boolean = logs == null || blockOf(tree.trunkProvider()) in logs

        private fun standingOrFallen(tree: Holder<Feature>, held: TreeFeature): Feature {
            val standing = Holder.direct(PlacedFeature(tree, emptyList()))
            val fallen = Holder.direct(PlacedFeature(Holder.direct(fallenOf(held)), emptyList()))
            return RandomSelectorFeature(listOf(WeightedPlacedFeature(fallen, SHARE_FALLEN)), standing)
        }

        /** [feature] with each tree it chooses between felled, or null where it chooses nothing. */
        // RandomSelectorFeature is deprecated and vanilla's own features are still built of it.
        @Suppress("DEPRECATION")
        private fun chooserOf(feature: Feature): Feature? = when (feature) {
            is RandomSelectorFeature -> RandomSelectorFeature(
                feature.features().map { WeightedPlacedFeature(placed(it.feature()), it.chance()) }, placed(feature.defaultFeature()),
            )
            is RandomBooleanSelectorFeature -> RandomBooleanSelectorFeature(placed(feature.featureTrue()), placed(feature.featureFalse()))
            is SimpleRandomSelectorFeature -> SimpleRandomSelectorFeature(HolderSet.direct(feature.features().map(::placed)))
            is WeightedRandomSelectorFeature -> WeightedRandomSelectorFeature(feature.features().map(::placed))
            is SequenceFeature -> SequenceFeature(HolderSet.direct(feature.features().map(::placed)))
            else -> null
        }

        /** Vanilla's fallen tree of [tree]'s log, or its fallen oak laid of [tree]'s trunk. */
        private fun fallenOf(tree: TreeFeature): Feature {
            val log = blockOf(tree.trunkProvider())
            val ofTheSameLog = fallenTrees.firstOrNull { log != null && blockOf((it.value() as FallenTreeFeature).trunkProvider()) == log }
            if (ofTheSameLog != null) return ofTheSameLog.value()
            val oak = fallenTrees.firstOrNull { it.key() == FALLEN_OAK }?.value() as? FallenTreeFeature
                ?: return FallenTreeFeature(tree.trunkProvider(), LOG_LENGTH, emptyList(), emptyList())
            return FallenTreeFeature(tree.trunkProvider(), oak.logLength(), oak.stumpDecorators(), oak.logDecorators())
        }

    }

    private val FALLEN_OAK: ResourceKey<Feature> = ResourceKey.create(Registries.FEATURE, Identifier.withDefaultNamespace("fallen_oak_tree"))

    /** Vanilla's fallen oak's length, where a pack has taken the fallen oak away. */
    private val LOG_LENGTH = UniformInt.of(SHORTEST_LOG, LONGEST_LOG)
    private const val SHORTEST_LOG = 4
    private const val LONGEST_LOG = 7
}
