package co.voik.agesandtheart.worldgen.fissure

import co.voik.agesandtheart.generation.Ages
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3

/**
 * Where something fallen out of an Age comes down in the overworld: a little over the world's spawn, already
 * falling, so it reads as having come *out* of somewhere rather than as a teleport.
 *
 * **Unless the spawn is buried, for a player.** A collapsing Age lets its rubble down around the spawn
 * ([RubbleArrivals]), and a heap taller than the drop put whoever fell next inside it. Where the column a player
 * would fall through is not clear, a pocket is carved at the spawn as linking carves one, and they arrive
 * standing. Anything else comes down as it always has: an item thrown in carves nothing.
 */
object HomeLanding {

    /** Puts [entity] down over [home]'s spawn, falling where there is room to and standing where there is not. */
    fun putDown(entity: Entity, home: ServerLevel) {
        val spawn = home.levelData.respawnData.pos()
        val isAPlayer = entity is Player
        val hasRoomToFall = !isAPlayer || isClear(home, spawn)
        if (!hasRoomToFall) Ages.openUpArrival(home, Ages.Arrival(spawn))
        val above = if (hasRoomToFall) FALL_OUT_ABOVE else ON_THE_GROUND
        entity.teleportTo(
            home,
            spawn.x + HALF_A_BLOCK, spawn.y + above, spawn.z + HALF_A_BLOCK,
            emptySet(), entity.yRot, entity.xRot, isAPlayer,
        )
        entity.deltaMovement = if (hasRoomToFall) Vec3(0.0, -GENTLY_DOWN, 0.0) else Vec3.ZERO
    }

    /** Whether the three-wide column from [spawn] up past where the drop starts holds nothing but air. */
    private fun isClear(home: ServerLevel, spawn: BlockPos): Boolean {
        val cursor = BlockPos.MutableBlockPos()
        val top = spawn.y + FALL_OUT_ABOVE.toInt() + HEADROOM
        return (spawn.y..top).all { y ->
            (-REACH..REACH).all { dx ->
                (-REACH..REACH).all { dz -> home.getBlockState(cursor.set(spawn.x + dx, y, spawn.z + dz)).isAir }
            }
        }
    }

    private const val FALL_OUT_ABOVE = 8.0
    private const val ON_THE_GROUND = 0.0
    private const val GENTLY_DOWN = 0.2
    private const val HALF_A_BLOCK = 0.5

    /** The column a falling player takes up, as a pocket carved for one is wide. */
    private const val REACH = 1
    private const val HEADROOM = 2
}
