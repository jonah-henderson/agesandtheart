package co.voik.agesandtheart.worldgen.fissure

import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import java.util.Optional
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rubble a star fissure sends out: kept as it falls in, and let down over the fissure's exit — the world's
 * spawn unless a book led it elsewhere ([FissureExits]) — a little at a time, faster the more is waiting.
 *
 * **Kept rather than moved.** Rubble arriving the moment it fell in came down all at once when a tear
 * widened; each block now joins a backlog of at most [KEPT_AT_MOST] — see [RubbleBacklog] for which go
 * first. The backlog is saved with the level it is owed to, so what an Age lost is still owed after a restart.
 *
 * **Let down only while the exit is ticking.** It is not kept loaded, and rubble dropped into a chunk that
 * is not running would hang in the air until somebody came back.
 *
 * **A fluid's source is placed rather than dropped**, in the first open space over the ground where it is drawn
 * to come down: a falling block of fluid has nothing to land as.
 *
 * **Each block falls onto open ground of its own.** Rubble dropped at one point lands in the heap it has
 * already built, cannot be placed there, and breaks into an item — a collapsing Age once left the spawn
 * littered with thousands of them. So each is scattered across a small disc and let go above the ground
 * *there*, with a little extra height drawn so two sent to one column do not land in the same tick.
 */
object RubbleArrivals {

    /**
     * Keeps [rubble]'s block for [exit], and takes the entity out of the Age. A block nobody could break,
     * such as bedrock, is never sent: a heap of it at the exit could never be cleared.
     */
    fun deliver(exit: FissureExits.Exit, rubble: FallingBlockEntity) {
        keep(exit, rubble.blockState)
        rubble.discard()
    }

    /** Keeps [state] for [exit], for a block that fell in without ever being an entity. */
    fun keep(exit: FissureExits.Exit, state: BlockState) {
        if (!isUnbreakable(exit.level, state)) RubbleBacklog.of(exit.level).keep(state, exit.at)
    }

    /** Whether [state] is a fluid's own source block, rather than running fluid or a block holding some. */
    fun isAFluidSource(state: BlockState): Boolean {
        val fluid = state.fluidState
        return fluid.isSource && fluid.createLegacyBlock() == state
    }

    private fun isUnbreakable(level: ServerLevel, state: BlockState): Boolean =
        state.getDestroySpeed(level, BlockPos.ZERO) < 0.0f

    /** One tick's worth of every level's backlog, dropped over the columns it is owed to. */
    fun letDown(server: MinecraftServer) {
        for (level in server.allLevels) letDown(level)
    }

    /**
     * One tick's worth of [level]'s backlog. Stops at the first block whose column is not ticking, so
     * rubble owed to two places in one level waits on the newer while it is away.
     */
    private fun letDown(level: ServerLevel) {
        val backlog = RubbleBacklog.of(level)
        if (backlog.isEmpty) return
        repeat(dropsThisTick(backlog.size, level.random.nextDouble())) {
            val (state, owedTo) = backlog.newest() ?: return
            val over = owedTo ?: level.levelData.respawnData.pos()
            if (!level.isPositionEntityTicking(over)) return
            backlog.letGoOfNewest()
            // A backlog saved before unbreakable blocks were refused may still hold some.
            if (!isUnbreakable(level, state)) drop(level, over, state)
        }
    }

    /**
     * How many to drop this tick: [dropsPerSecond] spread over twenty ticks, with the fraction left over
     * drawn against [chance] so the average comes out right.
     */
    fun dropsThisTick(waiting: Int, chance: Double): Int {
        val perTick = dropsPerSecond(waiting) / TICKS_PER_SECOND
        val whole = floor(perTick)
        val oneMore = if (chance < perTick - whole) 1 else 0
        return (whole.toInt() + oneMore).coerceAtMost(waiting)
    }

    /** A block every couple of seconds when little is waiting, rising to about sixteen a second when full. */
    fun dropsPerSecond(waiting: Int): Double = SLOWEST_PER_SECOND + waiting.toDouble() / WAITING_PER_EXTRA_BLOCK

    private fun drop(level: ServerLevel, over: BlockPos, state: BlockState) {
        val random = level.random
        val angle = random.nextDouble() * FULL_TURN
        // The square root spreads them evenly over the disc rather than bunching them at its middle.
        val distance = sqrt(random.nextDouble()) * SCATTER_RADIUS
        val x = over.x + cos(angle) * distance
        val z = over.z + sin(angle) * distance
        val ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x.toInt(), z.toInt())
        if (isAFluidSource(state)) return pour(level, BlockPos.containing(x, ground.toDouble(), z), state)
        val at = BlockPos.containing(x, (ground + DROPPED_FROM + random.nextInt(EXTRA_HEIGHT_DRAWN)).toDouble(), z)
        // `fall` clears the block it starts from, so it must start in the air.
        if (!level.getBlockState(at).isAir) return
        FallingBlockEntity.fall(level, at, state).addTag(RUBBLE_TAG)
    }

    /**
     * Where rubble let down here has landed, before vanilla decides whether it can be placed. A plant there is
     * crushed under it, and a block it cannot replace whose collision covers the whole square — a slab, stairs,
     * a carpet — is stood on, as a block placed by hand would be. Anything narrower, a torch or an end rod,
     * still breaks it into an item.
     */
    @JvmStatic
    fun settle(rubble: FallingBlockEntity) {
        val level = rubble.level() as? ServerLevel ?: return
        val isOurs = RUBBLE_TAG in rubble.entityTags()
        if (!isOurs || !rubble.onGround()) return
        val at = rubble.blockPosition()
        val landedIn = level.getBlockState(at)
        when {
            isAPlant(landedIn) -> level.setBlock(at, AIR, Block.UPDATE_ALL)
            coversTheWholeSquare(level, at, landedIn) -> rubble.setPos(rubble.x, at.y + 1.0, rubble.z)
        }
    }

    /** Grass or a flower, with no fluid in it. */
    private fun isAPlant(state: BlockState): Boolean {
        val growsThere = state.canBeReplaced() || state.`is`(BlockTags.FLOWERS)
        return growsThere && state.fluidState.isEmpty && !state.isAir
    }

    private fun coversTheWholeSquare(level: ServerLevel, at: BlockPos, state: BlockState): Boolean {
        if (state.canBeReplaced()) return false
        val shape = state.getCollisionShape(level, at)
        if (shape.isEmpty) return false
        val spansX = shape.min(Direction.Axis.X) <= 0.0 && shape.max(Direction.Axis.X) >= 1.0
        val spansZ = shape.min(Direction.Axis.Z) <= 0.0 && shape.max(Direction.Axis.Z) >= 1.0
        return spansX && spansZ
    }

    /** A source poured over a plant crushes it, as rubble landing does. */
    private fun pour(level: ServerLevel, at: BlockPos, source: BlockState) {
        val there = level.getBlockState(at)
        if (there.canBeReplaced() || isAPlant(there)) level.setBlock(at, source, Block.UPDATE_ALL)
    }

    /** How many blocks can be waiting at once. */
    const val KEPT_AT_MOST = 1024

    private const val SLOWEST_PER_SECOND = 0.5
    private const val WAITING_PER_EXTRA_BLOCK = 64.0
    private const val TICKS_PER_SECOND = 20.0

    private const val SCATTER_RADIUS = 6.0
    private const val DROPPED_FROM = 8
    private const val EXTRA_HEIGHT_DRAWN = 8
    private const val FULL_TURN = 2 * Math.PI

    private val AIR = Blocks.AIR.defaultBlockState()

    /** Marks the rubble let down here, so [settle] leaves every other falling block to vanilla. */
    private const val RUBBLE_TAG = "agesandtheart.rubble"
}

/**
 * The rubble still owed to one level — **newest first**, so after visiting several collapsing Ages it is the
 * latest one's rock that comes down, and when full it is the oldest that is let go.
 */
class RubbleBacklog private constructor(private val waiting: ArrayDeque<Owed>) : SavedData() {

    constructor() : this(ArrayDeque())

    /** A block, and the column it comes down over — the level's spawn where that is null. */
    data class Owed(val state: BlockState, val over: BlockPos?)

    val size: Int get() = waiting.size

    val isEmpty: Boolean get() = waiting.isEmpty()

    /** Keeps [state] for the column [over], letting the oldest go if there is no room. */
    fun keep(state: BlockState, over: BlockPos) {
        if (waiting.size >= RubbleArrivals.KEPT_AT_MOST) waiting.removeFirst()
        waiting.addLast(Owed(state, over))
        setDirty()
    }

    fun newest(): Owed? = waiting.lastOrNull()

    fun letGoOfNewest() {
        waiting.removeLastOrNull()?.also { setDirty() }
    }

    companion object {
        private const val NAME = "agesandtheart_rubble"

        private val OWED_CODEC: Codec<Owed> = RecordCodecBuilder.create { instance ->
            instance.group(
                BlockState.CODEC.fieldOf("state").forGetter(Owed::state),
                BlockPos.CODEC.optionalFieldOf("over").forGetter { Optional.ofNullable(it.over) },
            ).apply(instance) { state, over -> Owed(state, over.orElse(null)) }
        }

        /** A bare block state is what the backlog held when every fissure led to the spawn. */
        private val OWED_OR_BARE_STATE: Codec<Owed> = Codec.either(OWED_CODEC, BlockState.CODEC).xmap(
            { either -> either.map({ it }, { Owed(it, null) }) },
            { Either.left(it) },
        )

        private val CODEC: Codec<RubbleBacklog> = OWED_OR_BARE_STATE.listOf()
            .xmap({ RubbleBacklog(ArrayDeque(it)) }, { it.waiting.toList() })

        /** [DataFixTypes.LEVEL] for the same reason `AgeSavedData` names it: the record demands one. */
        private val TYPE: SavedDataType<RubbleBacklog> =
            SavedDataType(Identifier.withDefaultNamespace(NAME), ::RubbleBacklog, CODEC, DataFixTypes.LEVEL)

        fun of(level: ServerLevel): RubbleBacklog = level.dataStorage.computeIfAbsent(TYPE)
    }
}
