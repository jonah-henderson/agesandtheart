package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.InsideBlockEffectApplier
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.pathfinder.PathComputationType
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * The plasma sea: drawn as a solid block, but nothing stands on it and a click passes through it, as through
 * water. Whatever falls in is annihilated, and a block taken from it is filled again from beside.
 */
class PlasmaBlock(properties: Properties) : Block(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(BURIED, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(BURIED)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        Shapes.empty()

    override fun getCollisionShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        Shapes.empty()

    /** What a containment unit aims at, through `ClipContext.Block.VISUAL`. */
    override fun getVisualShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        Shapes.block()

    /** Full, though the shape is empty, so the sea's inside faces are culled and it blocks light. */
    override fun getOcclusionShape(state: BlockState): VoxelShape = Shapes.block()

    /** Opaque to the sky too: an empty shape would otherwise let skylight down through the whole sea. */
    override fun propagatesSkylightDown(state: BlockState): Boolean = false

    override fun isPathfindable(state: BlockState, type: PathComputationType): Boolean = false

    override fun entityInside(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        entity: Entity,
        effectApplier: InsideBlockEffectApplier,
        isPrecise: Boolean,
    ) {
        if (level is ServerLevel) Plasma.annihilate(entity, level)
    }

    override fun updateShape(
        state: BlockState,
        level: LevelReader,
        ticks: ScheduledTickAccess,
        pos: BlockPos,
        directionToNeighbour: Direction,
        neighbourPos: BlockPos,
        neighbourState: BlockState,
        random: RandomSource,
    ): BlockState {
        if (!neighbourState.`is`(this)) ticks.scheduleTick(pos, this, REFILLS_IN)
        return state.setValue(BURIED, isBuried(level, pos))
    }

    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        PlasmaSea.refillAround(level, pos)
    }

    private fun isBuried(level: LevelReader, pos: BlockPos): Boolean =
        Direction.entries.all { direction -> level.getBlockState(pos.relative(direction)).canOcclude() }

    companion object {
        /** Closed in on every side, so it casts no light: only the sea's skin is a light source. */
        val BURIED: BooleanProperty = BooleanProperty.create("buried")

        private const val REFILLS_IN = 10
    }
}
