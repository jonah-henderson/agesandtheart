package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.Sampling
import co.voik.agesandtheart.age.phenomena.Tornado
import co.voik.agesandtheart.age.phenomena.TornadoWinds
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.Entity

/**
 * The tornadoes' winds on the local player, here rather than on the server because a client moves its own player
 * ([TornadoWinds]), and the signs of them in the air ([TornadoSigns]).
 */
object Tornadoes {

    /** How each tornado has the player taken up, by its id. */
    private val lifts = HashMap<Int, TornadoWinds.Lift>()

    fun blow(client: Minecraft) {
        val level = client.level ?: return forget()
        val player = client.player ?: return forget()
        if (client.isPaused) return
        val near = near(level, player)
        TornadoSigns.show(level, player, near)
        val isOutOfTheWind = player.isSpectator || player.abilities.flying || player.isPassenger
        if (isOutOfTheWind) return forget()
        val now = level.gameTime
        val exposed = Sampling.exposureAt(level, player.blockPosition())
        val stillHeld = HashSet<Int>()
        for (tornado in near) {
            val vortex = TornadoWinds.of(tornado) ?: continue
            val lift = TornadoWinds.carry(player, vortex, lifts[tornado.id], now, level.random, exposed) ?: continue
            lifts[tornado.id] = lift
            stillHeld += tornado.id
        }
        // Flung clear of where any tornado is near, and still flying.
        for ((thrownBy, lift) in lifts.entries.toList()) {
            if (thrownBy in stillHeld) continue
            TornadoWinds.fly(player, lift, now)?.let { stillHeld += thrownBy }
        }
        lifts.keys.retainAll(stillHeld)
    }

    /** For a client leaving a level or a server. */
    fun forget() = lifts.clear()

    /** The tornadoes near enough to [player] to be felt or seen whirling. */
    private fun near(level: ClientLevel, player: Entity): List<Tornado> =
        level.getEntitiesOfClass(Tornado::class.java, player.boundingBox.inflate(SEEN_ACROSS, SEEN_UP, SEEN_ACROSS))

    /** Past where any tornado's winds reach, and far enough to see its dust go round. */
    private const val SEEN_ACROSS = 160.0
    private const val SEEN_UP = 256.0
}
