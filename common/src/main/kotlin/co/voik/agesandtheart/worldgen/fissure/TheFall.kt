package co.voik.agesandtheart.worldgen.fissure

import co.voik.agesandtheart.advancement.AgeTriggers
import co.voik.agesandtheart.platform.Services
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer

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

    /** Everyone the field has finished with, put out where the Age's fissures lead. */
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

    /** Out where the Age's fissures lead — see [FissureExits] and [HomeLanding]. */
    private fun putBackInTheOverworld(player: ServerPlayer, server: MinecraftServer) {
        player.noPhysics = false
        player.resetFallDistance()
        val exit = (player.level() as? ServerLevel)?.let(FissureExits::of) ?: FissureExits.home(server)
        HomeLanding.putDown(player, exit)
        AgeTriggers.FELL_HOME.trigger(player)
    }
}
