package co.voik.agesandtheart.age.aspect

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.state.BlockState

/** The block a book grew this Age's trees with in place of their leaves, for every tree or by placed feature. */
data class TreeFoliage(val everyTree: BlockState?, val byFeature: Map<String, BlockState>) {

    val isNothing: Boolean get() = everyTree == null && byFeature.isEmpty()

    /** What the tree placed by [feature] grows in place of its leaves, or null where it grows leaves. */
    fun of(feature: String?): BlockState? = feature?.let(byFeature::get) ?: everyTree

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
