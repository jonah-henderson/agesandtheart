package co.voik.agesandtheart.content

import co.voik.agesandtheart.platform.Services
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LevelEvent
import net.minecraft.world.level.block.state.BlockState

/**
 * Water and ink boil away over a plasma sea (design §7.1.2): within [PlasmaField.SCORCHING_REACH] of its surface, where
 * the heat haze begins, a block of either is gone in a puff of steam as it is placed or as anything beside it changes,
 * source or flow, so a bucket poured toward plasma never reaches its face (Jonah). Lava is left to meet the plasma and be
 * annihilated there.
 */
object PlasmaBoil {

    /** Boils the water or ink at [at] away where it stands in a plasma sea's reach, answering whether it did. */
    fun boilsAway(level: Level, at: BlockPos, state: BlockState): Boolean {
        if (level !is ServerLevel) return false
        val fluid = state.fluidState
        val boils = fluid.`is`(FluidTags.WATER) || Services.INK_FLUIDS.tierOf(fluid.type) != null
        if (!boils) return false
        val generator = level.chunkSource.generator
        if (!PlasmaSea.isAnywhereIn(generator)) return false
        val surface = PlasmaSea.surfaceAt(generator, at.x, at.z) ?: return false
        if (at.y - surface !in 0..PlasmaField.SCORCHING_REACH) return false
        level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)
        level.levelEvent(LevelEvent.LAVA_FIZZ, at, 0)
        return true
    }
}
