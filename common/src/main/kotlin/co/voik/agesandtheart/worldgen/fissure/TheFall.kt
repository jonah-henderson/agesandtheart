package co.voik.agesandtheart.worldgen.fissure

import co.voik.agesandtheart.advancement.AgeTriggers
import co.voik.agesandtheart.platform.Services
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3

/**
 * Letting go of whoever has fallen through a tear (design §7.8).
 *
 * **The hold keeps no clock.** A player drops until they are nearly into the void under the Age
 * ([StarFissureFall.letsGoAt]), and that depth is what says the beat is up. So there is no timer per player and no map
 * to clear when a server stops — §5.4's rule that the world is the state, which the fissures have kept
 * from the start.
 *
 * **Nothing can be seen to measure the drop against**, which is what lets one block read as a fall of any
 * length: the field is the same in every direction and does not move with the eyes inside it.
 *
 * @see StarFissureFall
 */
object TheFall {

    /** Everyone the field has finished with, put back in the overworld. */
    fun letGo(server: MinecraftServer) {
        val home = server.overworld()
        for (level in server.allLevels) {
            if (level === home) continue
            for (player in level.players().toList()) {
                if (!StarFissureFall.hasFallenFarEnough(player)) continue
                putBackInTheOverworld(player, server)
            }
        }
    }

    /**
     * A fall that a disconnection interrupted, taken up again or ended — called as a player joins.
     *
     * The fall itself needs nothing done to it: [StarFissureFall.SAVE_KEY] restores the one flag vanilla
     * does not save, and where they are and how fast they were going come back with them, so the drop
     * simply carries on. What this catches is the case where it *cannot* — a tear that is no longer there,
     * because the Age went on coming apart while they were away, or the block was taken. Rather than leave
     * somebody noclipping through a world with nothing overhead to let go of, the Age lets go now.
     */
    fun resumed(player: ServerPlayer) {
        if (!StarFissureFall.isFalling(player)) return
        if (StarFissureFall.tearOfTheFall(player) == null) {
            putBackInTheOverworld(player, player.level().server)
            return
        }
        // And the client told, which is the one part of a fall it cannot read off the blocks for itself.
        Services.NETWORK.sendToPlayer(player, FallResumedPayload)
    }

    /**
     * A little over the world's spawn, already falling.
     *
     * Arriving at rest reads as a teleport; arriving still moving reads as having come *out* of somewhere,
     * which is the thing the fall started with.
     */
    private fun putBackInTheOverworld(player: ServerPlayer, server: MinecraftServer) {
        val home = server.overworld()
        // The world spawn moved behind `LevelData.RespawnData`, which carries a `GlobalPos`.
        val spawn = home.levelData.respawnData.pos()
        player.noPhysics = false
        player.resetFallDistance()
        player.teleportTo(
            home,
            spawn.x + HALF_A_BLOCK, spawn.y + FALL_OUT_ABOVE, spawn.z + HALF_A_BLOCK,
            emptySet(), player.yRot, player.xRot, true,
        )
        player.deltaMovement = Vec3(0.0, -GENTLY_DOWN, 0.0)
        AgeTriggers.FELL_HOME.trigger(player)
    }

    /** A little over the spawn, so the last thing the fall does is the thing it started with. */
    private const val FALL_OUT_ABOVE = 8.0
    private const val GENTLY_DOWN = 0.2
    private const val HALF_A_BLOCK = 0.5
}
