package co.voik.agesandtheart.optics

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.DirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * An astrite mirror set against a block: a pane a pixel thick, facing out from the face it was placed on, which the
 * client draws as a true reflection.
 */
class AstriteMirrorBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState = defaultBlockState().setValue(FACING, context.clickedFace)

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPES.getValue(state.getValue(FACING))

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = AstriteMirrorBlockEntity(pos, state)

    companion object {
        /** The way the glass faces. */
        val FACING: EnumProperty<Direction> = DirectionalBlock.FACING

        /** How thick the pane is, in blocks. */
        const val THICKNESS = 1.0 / 16.0

        private const val PIXELS = 16.0
        private const val PANE = PIXELS * THICKNESS

        private val SHAPES: Map<Direction, VoxelShape> = mapOf(
            Direction.NORTH to box(0.0, 0.0, PIXELS - PANE, PIXELS, PIXELS, PIXELS),
            Direction.SOUTH to box(0.0, 0.0, 0.0, PIXELS, PIXELS, PANE),
            Direction.WEST to box(PIXELS - PANE, 0.0, 0.0, PIXELS, PIXELS, PIXELS),
            Direction.EAST to box(0.0, 0.0, 0.0, PANE, PIXELS, PIXELS),
            Direction.DOWN to box(0.0, PIXELS - PANE, 0.0, PIXELS, PIXELS, PIXELS),
            Direction.UP to box(0.0, 0.0, 0.0, PIXELS, PANE, PIXELS),
        )

        /** A point on the glass of a mirror at [pos] facing [facing]: the middle of its front face. */
        fun glassMiddle(pos: BlockPos, facing: Direction): Vec3 =
            Vec3.atCenterOf(pos).relative(facing, -(HALF - THICKNESS))

        private const val HALF = 0.5
    }
}

/** Only so the client can draw a mirror's reflection; it keeps nothing. */
class AstriteMirrorBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(Optics.MIRROR_ENTITY, pos, state)
