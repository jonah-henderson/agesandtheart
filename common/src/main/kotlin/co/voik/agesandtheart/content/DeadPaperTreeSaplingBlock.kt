package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.VegetationBlock
import net.minecraft.world.level.block.sounds.AmbientDesertBlockSoundsPlayer
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A yema sapling that died, and **vanilla's dead bush in everything but its look**: replaceable, sticks
 * unless sheared, burning as a dead bush burns, rattling in a desert. Every planting that fails ends here,
 * and the D'ni greenhouse is full of them.
 *
 * **How it died shows** (design §7.1.2), in the leaves' own colours: brown for a sapling that dried out,
 * yellow going black for one that drowned, so a row of failures says which way each went wrong. Shears keep
 * which it was.
 *
 * It stands on a dead bush's ground or on any a yema sapling takes, so dying never leaves it on soil it
 * cannot hold.
 */
class DeadPaperTreeSaplingBlock(properties: Properties) : VegetationBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(DROWNED, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(DROWNED)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPE

    override fun mayPlaceOn(state: BlockState, level: BlockGetter, pos: BlockPos): Boolean =
        state.`is`(BlockTags.SUPPORTS_DRY_VEGETATION) || PaperTreeGrowth.isSoftGround(state)

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        AmbientDesertBlockSoundsPlayer.playAmbientDeadBushSounds(level, pos, random)
    }

    companion object {
        /** Whether it drowned; otherwise it dried out. */
        val DROWNED: BooleanProperty = BooleanProperty.create("drowned")

        /** A dead bush's. */
        private val SHAPE: VoxelShape = Block.column(12.0, 0.0, 13.0)
    }
}
