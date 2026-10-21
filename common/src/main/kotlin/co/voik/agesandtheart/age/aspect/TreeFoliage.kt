package co.voik.agesandtheart.age.aspect

import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.placement.PlacedFeature as VanillaPlacedFeature

/** The block a book grew this Age's trees with in place of their leaves, for every tree or by placed feature. */
data class TreeFoliage(val everyTree: BlockState?, val byFeature: Map<String, BlockState>) {

    val isNothing: Boolean get() = everyTree == null && byFeature.isEmpty()

    /** What the tree placed by [feature] grows in place of its leaves, or null where it grows leaves. */
    fun of(feature: String?): BlockState? = feature?.let(byFeature::get) ?: everyTree

    /**
     * What [tree] grows in place of its leaves where a sapling grows it, or null where it grows leaves: the block of
     * the first placed feature written of that can place it, so a sapling grows as the trees of its kind were made.
     */
    fun ofSapling(tree: Feature, registries: HolderLookup.Provider): BlockState? {
        if (byFeature.isEmpty()) return everyTree
        fun places(placed: Holder.Reference<VanillaPlacedFeature>) = placed.value().getFeatures().anyMatch { it.value() === tree }
        val written = registries.lookupOrThrow(Registries.PLACED_FEATURE).listElements()
            .filter { it.key().identifier().toString() in byFeature }
            .filter(::places)
            .findFirst().orElse(null)
        return written?.let { byFeature[it.key().identifier().toString()] } ?: everyTree
    }

    companion object {
        val NONE = TreeFoliage(null, emptyMap())

        fun of(features: Options): TreeFoliage {
            val holders = features.holdersOfAny(Features.FOLIAGE)
            val everyTree = if (holders.everyone) blockNamed(features.of(Features.FOLIAGE)) else null
            val byFeature = holders.only.mapNotNull { member ->
                blockNamed(features.of(Features.FOLIAGE, member = member))?.let { member to it }
            }.toMap()
            return TreeFoliage(everyTree, byFeature)
        }

        private fun blockNamed(spelled: String): BlockState? {
            if (spelled == Parameter.UNCHANGED) return null
            val id = Identifier.tryParse(spelled)?.let(Materials::laidAs) ?: return null
            return BuiltInRegistries.BLOCK.getOptional(id).orElse(null)?.defaultBlockState()
        }
    }
}
