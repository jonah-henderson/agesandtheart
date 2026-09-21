package co.voik.agesandtheart.portal

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.InsideBlockEffectApplier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Portal
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.portal.TeleportTransition
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * The opening of a lit linking portal: a nether portal's sheet, leading where the book on its frame leads.
 *
 * **Vanilla's [Portal] does the crossing**, so anything that can walk through a nether portal can walk
 * through this one — mobs, items, minecarts and whatever they carry — with vanilla's delay, cooldown and
 * swirl. What is ours is only where it goes, which is [LinkingPortals.destination].
 */
class LinkingPortalBlock(properties: Properties) : Block(properties), Portal {

    init {
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X))
    }


    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPES.getValue(state.getValue(AXIS))

    /** Goes out the moment its frame stops being one, by vanilla's own test. */
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
        val neighbourAxis = directionToNeighbour.axis
        val isAcrossThePlane = neighbourAxis != state.getValue(AXIS) && neighbourAxis.isHorizontal
        val mayHaveBrokenTheFrame = !isAcrossThePlane && !neighbourState.`is`(this)
        if (mayHaveBrokenTheFrame && LinkingPortals.portalAt(level, pos, state.getValue(AXIS)) == null) {
            return Blocks.AIR.defaultBlockState()
        }
        return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random)
    }

    override fun entityInside(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        entity: Entity,
        effectApplier: InsideBlockEffectApplier,
        isPrecise: Boolean,
    ) {
        if (entity.canUsePortal(false)) entity.setAsInsidePortal(this, pos)
    }

    /** A nether portal's wait, from the same two game rules, so a player may still step back out. */
    override fun getPortalTransitionTime(level: ServerLevel, entity: Entity): Int {
        if (entity !is Player) return 0
        val rule = if (entity.abilities.invulnerable) {
            GameRules.PLAYERS_NETHER_PORTAL_CREATIVE_DELAY
        } else {
            GameRules.PLAYERS_NETHER_PORTAL_DEFAULT_DELAY
        }
        return maxOf(0, level.gameRules.get(rule))
    }

    override fun getPortalDestination(level: ServerLevel, entity: Entity, portalEntryPos: BlockPos): TeleportTransition? {
        val axis = level.getBlockState(portalEntryPos).getOptionalValue(AXIS).orElse(Direction.Axis.X)
        return LinkingPortals.destination(level, entity, portalEntryPos, axis)
    }

    override fun getLocalTransition(): Portal.Transition = Portal.Transition.CONFUSION

    /** A nether portal's hum and drift, until the asset pass gives the portal a look of its own. */
    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        if (random.nextInt(HUM_CHANCE) == 0) {
            level.playLocalSound(
                pos.x + MIDDLE,
                pos.y + MIDDLE,
                pos.z + MIDDLE,
                SoundEvents.PORTAL_AMBIENT,
                SoundSource.BLOCKS,
                HUM_VOLUME,
                random.nextFloat() * HUM_PITCH_SPREAD + HUM_LOWEST_PITCH,
                false,
            )
        }
        val facesAlongX = state.getValue(AXIS) == Direction.Axis.X
        repeat(PARTICLES_A_TICK) {
            val side = random.nextInt(2) * 2 - 1
            val drift = random.nextFloat() * 2.0 * side
            val besideTheSheetInX = pos.x + MIDDLE + QUARTER * side
            val besideTheSheetInZ = pos.z + MIDDLE + QUARTER * side
            level.addParticle(
                ParticleTypes.PORTAL,
                if (facesAlongX) pos.x + random.nextDouble() else besideTheSheetInX,
                pos.y + random.nextDouble(),
                if (facesAlongX) besideTheSheetInZ else pos.z + random.nextDouble(),
                if (facesAlongX) wander(random) else drift,
                wander(random),
                if (facesAlongX) drift else wander(random),
            )
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getCloneItemStack(level: LevelReader, pos: BlockPos, state: BlockState, includeData: Boolean): ItemStack =
        ItemStack.EMPTY

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = when (rotation) {
        Rotation.CLOCKWISE_90, Rotation.COUNTERCLOCKWISE_90 -> state.setValue(AXIS, turned(state.getValue(AXIS)))
        Rotation.NONE, Rotation.CLOCKWISE_180 -> state
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(AXIS)
    }

    companion object {

        val AXIS: EnumProperty<Direction.Axis> = BlockStateProperties.HORIZONTAL_AXIS

        /** A nether portal's: a sheet four pixels thick, standing in the middle of its block. */
        private val SHAPES: Map<Direction.Axis, VoxelShape> =
            Shapes.rotateHorizontalAxis(column(4.0, 16.0, 0.0, 16.0))

        private const val MIDDLE = 0.5
        private const val QUARTER = 0.25
        private const val HUM_CHANCE = 100
        private const val HUM_VOLUME = 0.5f
        private const val HUM_PITCH_SPREAD = 0.4f
        private const val HUM_LOWEST_PITCH = 0.8f
        private const val PARTICLES_A_TICK = 4

        private fun wander(random: RandomSource): Double = (random.nextFloat() - MIDDLE) * MIDDLE

        private fun turned(axis: Direction.Axis): Direction.Axis =
            if (axis == Direction.Axis.X) Direction.Axis.Z else Direction.Axis.X
    }
}
