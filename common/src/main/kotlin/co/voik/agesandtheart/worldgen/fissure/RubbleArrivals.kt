package co.voik.agesandtheart.worldgen.fissure

import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.level.saveddata.SavedDataType
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rubble a star fissure sends home: kept as it falls in, and let down over the world's spawn a little at a
 * time, faster the more is waiting (Jonah's walk of G12).
 *
 * **Kept rather than moved.** Rubble arriving the moment it fell in came down all at once when a tear
 * widened; each block now joins a backlog of at most [KEPT_AT_MOST], and what arrives past that falls into
 * the stars. The backlog is saved with the overworld, so what an Age lost is still owed after a restart.
 *
 * **Let down only while the spawn is ticking.** It is not kept loaded, and rubble dropped into a chunk that
 * is not running would hang in the air until somebody came back.
 *
 * **Each block falls onto open ground of its own.** Rubble dropped at one point lands in the heap it has
 * already built, cannot be placed there, and breaks into an item — a collapsing Age once left the spawn
 * littered with thousands of them. So each is scattered across a small disc and let go above the ground
 * *there*, with a little extra height drawn so two sent to one column do not land in the same tick.
 */
object RubbleArrivals {

    /** Keeps [rubble]'s block for the spawn, and takes the entity out of the Age. */
    fun deliver(home: ServerLevel, rubble: FallingBlockEntity) {
        RubbleBacklog.of(home).keep(rubble.blockState)
        rubble.discard()
    }

    /** One tick's worth of the backlog, dropped over the spawn. */
    fun letDown(server: MinecraftServer) {
        val home = server.overworld()
        val backlog = RubbleBacklog.of(home)
        if (backlog.isEmpty) return
        val spawn = home.levelData.respawnData.pos()
        if (!home.isPositionEntityTicking(spawn)) return
        repeat(dropsThisTick(backlog.size, home.random.nextDouble())) {
            val state = backlog.next() ?: return
            drop(home, spawn, state)
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

    private fun drop(home: ServerLevel, spawn: BlockPos, state: BlockState) {
        val random = home.random
        val angle = random.nextDouble() * FULL_TURN
        // The square root spreads them evenly over the disc rather than bunching them at its middle.
        val distance = sqrt(random.nextDouble()) * SCATTER_RADIUS
        val x = spawn.x + cos(angle) * distance
        val z = spawn.z + sin(angle) * distance
        val ground = home.getHeight(Heightmap.Types.MOTION_BLOCKING, x.toInt(), z.toInt())
        val at = BlockPos.containing(x, (ground + DROPPED_FROM + random.nextInt(EXTRA_HEIGHT_DRAWN)).toDouble(), z)
        // `fall` clears the block it starts from, so it must start in the air.
        if (!home.getBlockState(at).isAir) return
        FallingBlockEntity.fall(home, at, state)
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
}

/** The rubble still owed to the overworld's spawn, oldest first. */
class RubbleBacklog private constructor(private val waiting: ArrayDeque<BlockState>) : SavedData() {

    constructor() : this(ArrayDeque())

    val size: Int get() = waiting.size

    val isEmpty: Boolean get() = waiting.isEmpty()

    /** Keeps [state] if there is room, and lets it go if there is not. */
    fun keep(state: BlockState) {
        if (waiting.size >= RubbleArrivals.KEPT_AT_MOST) return
        waiting.addLast(state)
        setDirty()
    }

    fun next(): BlockState? = waiting.removeFirstOrNull()?.also { setDirty() }

    companion object {
        private const val NAME = "agesandtheart_rubble"

        private val CODEC: Codec<RubbleBacklog> = BlockState.CODEC.listOf()
            .xmap({ RubbleBacklog(ArrayDeque(it)) }, { it.waiting.toList() })

        /** [DataFixTypes.LEVEL] for the same reason `AgeSavedData` names it: the record demands one. */
        private val TYPE: SavedDataType<RubbleBacklog> =
            SavedDataType(Identifier.withDefaultNamespace(NAME), ::RubbleBacklog, CODEC, DataFixTypes.LEVEL)

        fun of(home: ServerLevel): RubbleBacklog = home.dataStorage.computeIfAbsent(TYPE)
    }
}
