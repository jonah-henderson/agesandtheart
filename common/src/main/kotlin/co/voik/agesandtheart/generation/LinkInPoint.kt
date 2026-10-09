package co.voik.agesandtheart.generation

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.AgeTemplate
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelData
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * **What a spawn compass points at in an Age: where a link lands.**
 *
 * A runtime level is given `DerivedLevelData`, which reports the overworld's spawn, so vanilla's compass sees
 * a spawn in another dimension and spins. The client level takes its spawn from the packet below, which
 * vanilla sends on joining, on a dimension change and on respawn — so telling it the arrival, with the Age's
 * own dimension, is the whole of making a vanilla compass point home, and lodestones keep working as they
 * always have.
 *
 * An Age written over the nether or the End is left as vanilla leaves those, with a compass that spins.
 */
object LinkInPoint {

    /**
     * The level each player was last settled in. Weak on both sides, so neither a player nor an Age that has
     * been deleted is kept alive by it, and keyed by the player *instance*: a respawn makes a new one, which
     * is what makes a respawn into the same Age tell again.
     */
    private val settledIn = WeakHashMap<ServerPlayer, WeakReference<ServerLevel>>()

    /**
     * Tells every player who is somewhere new, **once a tick and after everything that moved them**.
     *
     * Reconciling against the level they are in, rather than reacting to joining, teleporting and respawning,
     * is what keeps this independent of when each loader fires its events relative to vanilla's own spawn
     * packet: Fabric's change-level event fires before it on a teleport and would be overwritten.
     */
    fun tick(server: MinecraftServer) {
        for (player in server.playerList.players) {
            val level = player.level()
            if (settledIn[player]?.get() === level) continue
            settledIn[player] = WeakReference(level)
            send(player, level)
        }
    }

    private fun send(player: ServerPlayer, level: ServerLevel) {
        val recipe = Ages.recipeOf(level) ?: return
        if (recipe.template != AgeTemplate.ORDINARY) return

        val home = Ages.arrivalIn(level)
        Constants.LOG.info("Pointing {}'s spawn compass at {} in {}", player.plainTextName, home, level.dimension().identifier())
        val respawn = LevelData.RespawnData.of(level.dimension(), home, NO_YAW, NO_PITCH)
        player.connection.send(ClientboundSetDefaultSpawnPositionPacket(respawn))
    }

    private const val NO_YAW = 0.0f
    private const val NO_PITCH = 0.0f
}
