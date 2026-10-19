package co.voik.agesandtheart.worldgen.fissure

import co.voik.agesandtheart.generation.Ages
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3

/**
 * Where something fallen out of an Age comes down — over its [FissureExits.Exit], already falling, so it reads as
 * having come *out* of somewhere rather than as a teleport.
 *
 * **Over everything standing there**, not over the exit's own height: a collapsing Age lets its rubble down around
 * the same place ([RubbleArrivals]), and a heap taller than the drop put whoever fell next inside it.
 *
 * **Never into fire or lava where there is anywhere else.** The fluids a collapse sends through are poured on the
 * same heap, so the exit's own column is taken only if it is safe, and otherwise the nearest one that is
 * ([ExitGround.safestNear]).
 */
object HomeLanding {

    /** Puts [entity] down falling, a little over everything standing near [exit]. */
    fun putDown(entity: Entity, exit: FissureExits.Exit) {
        val from = fallingFrom(exit)
        entity.teleportTo(exit.level, from.x, from.y, from.z, emptySet(), entity.yRot, entity.xRot, entity is Player)
        entity.deltaMovement = Vec3(0.0, -GENTLY_DOWN, 0.0)
    }

    /** Where something comes out over [exit]: the middle of the safest column near it, a little over everything in it. */
    fun fallingFrom(exit: FissureExits.Exit): Vec3 {
        val (level, at) = exit
        val column = ExitGround.safestNear(level, at.x, at.z) { x, z -> ExitGround.column(level, x, z) }
        val y = (column.clearAbove + FALL_OUT_ABOVE).coerceAtMost(level.maxY - HEADROOM.toDouble())
        return Vec3(column.x + HALF_A_BLOCK, y, column.z + HALF_A_BLOCK)
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
