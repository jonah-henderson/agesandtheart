package co.voik.agesandtheart.age.phenomena

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.InsideBlockEffectApplier
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.BonemealSource
import net.minecraft.world.level.block.BonemealableBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape
import kotlin.math.abs

/**
 * One segment of a thorn vine: a chain, never a branch, that hurts whatever moves in it (design §5.2.2).
 *
 * A segment knows the neighbour it hangs from ([ANCHOR]) and the one it grows toward ([HEADING]), so it is
 * straight when the two are opposite and a turn when they are at a right angle. It stands only while the
 * chain behind it leads back to an overgrowth, and falls away a step at a time when the chain is cut.
 */
class ThornVineBlock(properties: BlockBehaviour.Properties) : Block(properties), BonemealableBlock {

    init {
        registerDefaultState(
            stateDefinition.any().setValue(ANCHOR, Direction.DOWN).setValue(HEADING, Direction.UP).setValue(REACH, 0),
        )
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(ANCHOR, HEADING, REACH)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(ANCHOR, context.clickedFace.opposite).setValue(HEADING, context.clickedFace)

    override fun getEntityInsideCollisionShape(
        state: BlockState,
        level: BlockGetter,
        pos: BlockPos,
        entity: Entity,
    ): VoxelShape = Shapes.block()

    override fun entityInside(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        entity: Entity,
        effects: InsideBlockEffectApplier,
        isPrecise: Boolean,
    ) {
        if (entity !is LivingEntity) return
        entity.makeStuckInBlock(state, SLOWED_TO)
        if (level !is ServerLevel) return
        val movement = if (entity.isClientAuthoritative) entity.knownMovement else entity.position().subtract(entity.oldPosition())
        val isMoving = abs(movement.x) >= MOVEMENT_THAT_COUNTS || abs(movement.y) >= MOVEMENT_THAT_COUNTS ||
            abs(movement.z) >= MOVEMENT_THAT_COUNTS
        if (isMoving) entity.hurtServer(level, level.damageSources().sweetBerryBush(), DAMAGE)
    }

    override fun attack(state: BlockState, level: Level, pos: BlockPos, player: Player) {
        if (level !is ServerLevel) return
        scratch(level, player)
        level.scheduleTick(pos, this, SCRATCH_INTERVAL)
    }

    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        if (level is ServerLevel) scratch(level, player)
        return super.playerWillDestroy(level, pos, state, player)
    }

    /** Hurts [player] if nothing is in their hand to cut with. */
    private fun scratch(level: ServerLevel, player: Player) {
        if (player.mainHandItem.isEmpty) player.hurtServer(level, level.damageSources().sweetBerryBush(), DAMAGE)
    }

    private fun isDigging(player: Player, pos: BlockPos): Boolean {
        val target = player.pick(player.blockInteractionRange(), 0f, false)
        val isLookingAtIt = target is BlockHitResult && target.blockPos == pos
        return player.isSwinging && isLookingAtIt
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
        val anchor = state.getValue(ANCHOR)
        val lostItsAnchor = directionToNeighbour == anchor && !holds(neighbourState, anchor)
        val touchesHeat = Overgrowth.isHot(neighbourState)
        if (lostItsAnchor || touchesHeat) ticks.scheduleTick(pos, this, FALLS_AWAY_IN)
        return state
    }

    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        val anchor = state.getValue(ANCHOR)
        val isHanging = holds(level.getBlockState(pos.relative(anchor)), anchor)
        val isBurning = Overgrowth.isHotWithin(level, pos, 1)
        if (!isHanging || isBurning) {
            level.destroyBlock(pos, false)
            return
        }
        val diggers = level.players().filter { isDigging(it, pos) }
        diggers.forEach { scratch(level, it) }
        if (diggers.isNotEmpty()) level.scheduleTick(pos, this, SCRATCH_INTERVAL)
        else if (outlookFrom(level, pos, state) != Outlook.SETTLED) beat(state, level, pos, random)
    }

    /** One look at whether the tip grows: it does with some chance, and looks again while it still could. */
    private fun beat(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (Overgrowth.isHotWithin(level, pos, Overgrowth.BURN_RADIUS)) return
        if (random.nextFloat() < GROWTH_PER_BEAT * Overgrowth.growthIn(level)) grow(state, level, pos, random) else level.scheduleTick(pos, this, HEARTBEAT)
    }

    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (Overgrowth.isHotWithin(level, pos, Overgrowth.BURN_RADIUS)) {
            level.destroyBlock(pos, false)
            return
        }
        if (outlookFrom(level, pos, state) != Outlook.SETTLED) level.scheduleTick(pos, this, HEARTBEAT)
    }

    /** What a segment can do about growing: nothing, extend into the open cell ahead, or turn away from what blocks it. */
    private enum class Outlook { SETTLED, EXTEND, TURN }

    private fun outlookFrom(level: LevelReader, pos: BlockPos, state: BlockState): Outlook {
        val heading = state.getValue(HEADING)
        val ahead = pos.relative(heading)
        val aheadState = level.getBlockState(ahead)
        val isContinued = aheadState.`is`(this) && aheadState.getValue(ANCHOR) == heading.opposite
        if (isContinued || !level.hasChunkAt(ahead)) return Outlook.SETTLED
        if (aheadState.isAir) return if (reachAt(level, ahead, state) <= MAX_REACH) Outlook.EXTEND else Outlook.SETTLED
        return if (openWaysFrom(level, pos, state).isEmpty()) Outlook.SETTLED else Outlook.TURN
    }

    /**
     * How many unsupported segments the chain would have run once it reached [cell]: none if a solid block
     * stands beside it, and one more than [from] otherwise. Only the unsupported run is capped, so a chain
     * that keeps to a wall has no end.
     */
    private fun reachAt(level: LevelReader, cell: BlockPos, from: BlockState): Int =
        if (isSupported(level, cell)) 0 else from.getValue(REACH) + 1

    private fun isSupported(level: LevelReader, cell: BlockPos): Boolean = Direction.entries.any { way ->
        val beside = cell.relative(way)
        level.hasChunkAt(beside) && level.getBlockState(beside).isFaceSturdy(level, beside, way.opposite)
    }

    private fun openWaysFrom(level: LevelReader, pos: BlockPos, state: BlockState): List<Direction> {
        val anchor = state.getValue(ANCHOR)
        return Direction.entries.filter { way ->
            way != anchor && level.hasChunkAt(pos.relative(way)) && level.getBlockState(pos.relative(way)).isAir
        }
    }

    /** Extends the chain by one segment if this is its tip, or turns the tip toward open air if it is blocked. */
    private fun grow(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        val heading = state.getValue(HEADING)
        when (outlookFrom(level, pos, state)) {
            Outlook.SETTLED -> Unit
            Outlook.EXTEND -> {
                val ahead = pos.relative(heading)
                val segment = defaultBlockState()
                    .setValue(ANCHOR, heading.opposite)
                    .setValue(HEADING, headingAfter(heading, random))
                    .setValue(REACH, reachAt(level, ahead, state))
                level.setBlock(ahead, segment, UPDATE_ALL)
                level.scheduleTick(ahead, this, HEARTBEAT)
            }
            Outlook.TURN -> {
                val openWays = openWaysFrom(level, pos, state)
                level.setBlock(pos, state.setValue(HEADING, openWays[random.nextInt(openWays.size)]), UPDATE_ALL)
                level.scheduleTick(pos, this, HEARTBEAT)
            }
        }
    }

    override fun isValidBonemealTarget(level: LevelReader, pos: BlockPos, state: BlockState, source: BonemealSource) =
        outlookFrom(level, pos, state) != Outlook.SETTLED

    override fun isBonemealSuccess(level: Level, random: RandomSource, pos: BlockPos, state: BlockState, source: BonemealSource) =
        true

    override fun performBonemeal(level: ServerLevel, random: RandomSource, pos: BlockPos, state: BlockState, source: BonemealSource) =
        grow(state, level, pos, random)

    /** Whether [neighbour], on the [anchor] side, is something this segment can hang from. */
    private fun holds(neighbour: BlockState, anchor: Direction): Boolean {
        val isAnOvergrowth = neighbour.`is`(Overgrowth.BLOCK)
        val isTheSegmentBefore = neighbour.`is`(this) && neighbour.getValue(HEADING) == anchor.opposite
        return isAnOvergrowth || isTheSegmentBefore
    }

    companion object {
        /** The side of the segment that is toward the overgrowth, by way of any segments between. */
        val ANCHOR: EnumProperty<Direction> = EnumProperty.create("anchor", Direction::class.java)

        /** The side the chain grows toward next. */
        val HEADING: EnumProperty<Direction> = EnumProperty.create("heading", Direction::class.java)

        /** How many unsupported segments the chain has run to here, counting this one; none beside a solid block. */
        const val MAX_REACH = 8
        val REACH: IntegerProperty = IntegerProperty.create("reach", 0, MAX_REACH)

        /** How often a chain's tip looks to grow, in ticks; each look grows it with the chance below. */
        private const val HEARTBEAT = 20
        private const val GROWTH_PER_BEAT = 0.2f

        private const val DAMAGE = 2.0f

        private const val SCRATCH_INTERVAL = 10
        private const val MOVEMENT_THAT_COUNTS = 0.003
        private val SLOWED_TO = Vec3(0.7, 0.6, 0.7)

        /** Long enough to see a cut chain go segment by segment. */
        private const val FALLS_AWAY_IN = 3

        /** The chance a chain carries straight on rather than turning at a segment. */
        private const val STRAIGHT_ODDS = 0.6f

        /** The first segment of a chain on the face of an overgrowth, [way] being the face it is on. */
        fun startingFrom(way: Direction, random: RandomSource): BlockState =
            Overgrowth.THORNS.defaultBlockState()
                .setValue(ANCHOR, way.opposite)
                .setValue(HEADING, headingAfter(way, random))

        /** Straight on, or a right angle either way, chosen at random. Never back. */
        fun headingAfter(travelling: Direction, random: RandomSource): Direction {
            if (random.nextFloat() < STRAIGHT_ODDS) return travelling
            val turns = Direction.entries.filter { it.axis != travelling.axis }
            return turns[random.nextInt(turns.size)]
        }
    }
}
