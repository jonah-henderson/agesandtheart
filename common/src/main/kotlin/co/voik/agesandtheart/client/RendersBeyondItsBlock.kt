package co.voik.agesandtheart.client

import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.phys.AABB

/**
 * A block entity renderer that draws well outside its own block, and the box round all it may draw.
 *
 * Vanilla draws such a renderer while its block is out of sight if it says [shouldRenderOffScreen]; NeoForge then
 * culls every block entity by a box its renderer gives, its own block unless told otherwise, through an extension
 * method that `common` cannot override, since the same source compiles against both loaders. So NeoForge's
 * registration wraps a renderer carrying this in one that answers with [renderBounds]; Fabric needs nothing.
 */
interface RendersBeyondItsBlock {
    fun renderBounds(blockEntity: BlockEntity): AABB
}
