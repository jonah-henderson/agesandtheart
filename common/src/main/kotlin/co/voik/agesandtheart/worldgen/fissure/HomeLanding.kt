package co.voik.agesandtheart.worldgen.fissure

import co.voik.agesandtheart.generation.Ages
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3

/**
 * Where something fallen out of an Age comes down in the overworld: over the world's spawn, already falling, so
 * it reads as having come *out* of somewhere rather than as a teleport.
 *
 * **Over whatever stands there**, not over the spawn's own height: a collapsing Age lets its rubble down around
 * the spawn ([RubbleArrivals]), and a heap taller than the drop put whoever fell next inside it.
 */
object HomeLanding {

    /** Puts [entity] down falling, a little over the highest thing standing at [home]'s spawn. */
    fun putDown(entity: Entity, home: ServerLevel) {
        val spawn = home.levelData.respawnData.pos()
        val standing = home.getHeight(Heightmap.Types.MOTION_BLOCKING, spawn.x, spawn.z)
        val from = (standing + FALL_OUT_ABOVE).coerceAtMost(home.maxY - HEADROOM.toDouble())
        entity.teleportTo(
            home,
            spawn.x + HALF_A_BLOCK, from, spawn.z + HALF_A_BLOCK,
            emptySet(), entity.yRot, entity.xRot, entity is Player,
        )
        entity.deltaMovement = Vec3(0.0, -GENTLY_DOWN, 0.0)
    }

    /**
     * A player here for the first time who would start inside the rubble heaped on the spawn: carved a pocket,
     * as linking carves one. Vanilla puts a new player at the spawn without asking what has fallen on it since.
     */
    fun joined(player: ServerPlayer) {
        val isTheirFirstTime = player.stats.getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) == NEVER_PLAYED
        if (!isTheirFirstTime || !player.isInWall) return
        Ages.openUpArrival(player.level(), Ages.Arrival(player.blockPosition()))
    }

    private const val FALL_OUT_ABOVE = 8.0
    private const val GENTLY_DOWN = 0.2
    private const val HALF_A_BLOCK = 0.5
    private const val HEADROOM = 2
    private const val NEVER_PLAYED = 0
}
