package co.voik.agesandtheart.age.consequence

import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import java.util.Collections
import java.util.WeakHashMap

/**
 * The rubble a tear or a cave-in throws down as the ground goes, and where the ground's going is shown at all.
 *
 * **Shown only where somebody is near** ([isSeen]): there the ground cracks and rubble falls into the hole; anywhere
 * else it simply goes, since what nobody can see costs every client a rebuilt section and a relit column all the
 * same. Rubble **never becomes an item** where it cannot land, **none past [MOST_IN_FLIGHT] is falling near a place**,
 * and **none falls longer than [FALLS_FOR]**, by when it is far down the hole: every piece is an entity ticked and
 * collided on the server and on every client watching.
 */
object FallingRubble {

    /** Whether anybody in [level] is near enough to [at], across the ground, to see the ground there go. */
    fun isSeen(level: ServerLevel, at: BlockPos): Boolean = level.players().any { player ->
        val acrossX = player.x - (at.x + HALF)
        val acrossZ = player.z - (at.z + HALF)
        acrossX * acrossX + acrossZ * acrossZ <= SEEN_WITHIN * SEEN_WITHIN
    }

    /** Lets [state] fall from [at], which the caller has already cleared, where it is seen and not enough is falling. */
    fun throwDown(level: ServerLevel, at: BlockPos, state: BlockState) {
        if (!isSeen(level, at)) return
        val inFlight = level.getEntities(EntityTypes.FALLING_BLOCK, AABB(at).inflate(NEAR)) { true }.size
        if (inFlight >= MOST_IN_FLIGHT) return
        val rubble = FallingBlockEntity.fall(level, at, state)
        rubble.disableDrop()
        thrown[rubble] = Unit
    }

    /** Every tick: rubble that has fallen long enough is gone. */
    fun tick(server: MinecraftServer) {
        if (thrown.isEmpty()) return
        synchronized(thrown) {
            val iterator = thrown.keys.iterator()
            while (iterator.hasNext()) {
                val rubble = iterator.next()
                val hasFallenLongEnough = rubble.time > FALLS_FOR
                if (hasFallenLongEnough) rubble.discard()
                if (rubble.isRemoved) iterator.remove()
            }
        }
    }

    private val thrown: MutableMap<FallingBlockEntity, Unit> = Collections.synchronizedMap(WeakHashMap())

    /** Four chunks: as far as the cracks and the rubble are worth anybody's frames. */
    private const val SEEN_WITHIN = 64.0
    private const val MOST_IN_FLIGHT = 24
    private const val NEAR = 32.0

    /** Three seconds of falling. */
    private const val FALLS_FOR = 60
    private const val HALF = 0.5
}
