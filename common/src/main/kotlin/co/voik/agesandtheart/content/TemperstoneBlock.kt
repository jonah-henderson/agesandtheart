package co.voik.agesandtheart.content

import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState

/**
 * Temperstone, which binds to itself under a piston.
 *
 * A run of it moves as one the way slime does, but it sticks only to its own kind — the same unusually
 * strong bond that lets a climber's boots hold on to it.
 *
 * **[isStickyBlock] and [canStickTo] are overrides on NeoForge and unused methods on Fabric**, which is
 * what the suppression is for. NeoForge patches `Block` to implement `IBlockExtension`, whose two methods
 * carry these names and vanilla-only signatures; common is compiled once against each loader's jar, so
 * Kotlin sees a supertype member on one pass and none on the other and can only be right about one of
 * them. The emitted descriptors are what bind at runtime, and they are identical either way.
 *
 * Fabric's half of the same feature is `PistonStructureResolverMixin`, vanilla having no hook at all.
 */
@Suppress("VIRTUAL_MEMBER_HIDDEN")
class TemperstoneBlock(properties: BlockBehaviour.Properties) : Block(properties) {

    fun isStickyBlock(state: BlockState): Boolean = bindsToItsOwnKind(state)

    /**
     * Temperstone binds to its own kind and to nothing else.
     *
     * Not vanilla's `isSticky(a) || isSticky(b)`, which would drag whatever the run happened to touch —
     * that is a slime block, and this is not one.
     */
    fun canStickTo(state: BlockState, other: BlockState): Boolean =
        bindsToItsOwnKind(state) && bindsToItsOwnKind(other)

    companion object {
        /**
         * Whether this is the bonded stuff.
         *
         * The raw and scorched forms are excluded: one has not been tempered and the other was tempered
         * past use, and neither should carry the property tempering is for.
         */
        @JvmStatic
        fun bindsToItsOwnKind(state: BlockState): Boolean = state.`is`(AgeContent.TEMPERSTONE_BLOCK)
    }
}
