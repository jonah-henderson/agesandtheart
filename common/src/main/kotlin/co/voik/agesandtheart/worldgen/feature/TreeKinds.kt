package co.voik.agesandtheart.worldgen.feature

import net.minecraft.core.Holder
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.levelgen.feature.TreeFeature
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider
import net.minecraft.world.level.levelgen.feature.stateproviders.SimpleStateProvider
import net.minecraft.world.level.levelgen.placement.PlacedFeature

/**
 * A kind of tree, told by its log: what a book says of one kind (`fallen oak`, `bare birch`) reaches every tree of that
 * log wherever it stands, since a biome grows its trees inside a forest's mixed choice rather than as the lone feature
 * the kind's word names.
 */
object TreeKinds {

    /** The log [tree] grows its trunk of, or null where its trunk is drawn from several. */
    fun logOf(tree: TreeFeature): Block? = blockOf(tree.trunkProvider())

    /** The logs of the trees [feature] grows, wherever they sit among what it chooses between. */
    fun logsOf(feature: Holder<PlacedFeature>): Set<Block> = feature.value().getFeatures()
        .map { it.value() }
        .toList()
        .filterIsInstance<TreeFeature>()
        .mapNotNull(::logOf)
        .toSet()

    fun blockOf(provider: Holder<BlockStateProvider>): Block? = (provider.value() as? SimpleStateProvider)?.state()?.block
}
