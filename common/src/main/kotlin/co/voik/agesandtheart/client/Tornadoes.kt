package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.Sampling
import co.voik.agesandtheart.age.phenomena.Tornado
import co.voik.agesandtheart.age.phenomena.TornadoWinds
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.Entity

/**
 * The tornadoes' winds on the local player, here rather than on the server because a client moves its own player
 * ([TornadoWinds]), the signs of them in the air ([TornadoSigns]), and their roar.
 */
object Tornadoes {

    /** How each tornado has the player taken up, by its id. */
    private val lifts = HashMap<Int, TornadoWinds.Lift>()

    fun blow(client: Minecraft) {
        val level = client.level ?: return forget()
        val player = client.player ?: return forget()
        if (client.isPaused) return
        val near = near(level, player)
        nearNow = near
        TornadoSigns.show(level, player, near)
        val isOutOfTheWind = player.isSpectator || player.abilities.flying || player.isPassenger
        if (isOutOfTheWind) return lifts.clear()
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
    fun forget() {
        lifts.clear()
        nearNow = emptyList()
    }

    /** The tornadoes near the player as of the last tick, for what is read many times a frame ([TornadoLight]). */
    fun nearNow(): List<Tornado> = nearNow

    private var nearNow: List<Tornado> = emptyList()

    /**
     * Keep the roar playing while a tornado is in earshot: one sound for however many, as loud as the nearest is,
     * heard from all around rather than from a point, since it is.
     */
    fun heard(client: Minecraft) {
        val level = client.level
        val player = client.player
        val isInEarshot = level != null && player != null && loudnessNow(level, player) > SILENT
        if (!isInEarshot) {
            roaring?.let(client.soundManager::stop)
            roaring = null
            return
        }
        if (roaring?.isStopped == false) return
        roaring = Roar().also(client.soundManager::play)
    }

    private var roaring: Roar? = null

    /**
     * How loud the nearest tornado is where [player] stands: all of it inside its orbit, falling away to nothing at
     * [HEARD_FROM] times its outer winds' reach, and none under water. It rises from the moment the funnel starts down,
     * loud by the time it is a third of the way, and dies as it ropes out.
     */
    private fun loudnessNow(level: ClientLevel, player: Entity): Float {
        if (player.isUnderWater) return SILENT
        fun loudnessOf(tornado: Tornado): Float {
            val forming = Mth.smoothstep((tornado.descended / LOUD_BY).coerceIn(0.0f, 1.0f)) * (1.0f - tornado.roped)
            if (forming <= SILENT) return SILENT
            val vortex = TornadoWinds.shapeOf(tornado, forming.toDouble())
            val out = vortex.bearingsOf(player.x, player.y, player.z).out
            val inner = vortex.orbitReachAt(player.y)
            val outer = vortex.outerReach * HEARD_FROM
            val fading = ((out - inner) / (outer - inner)).coerceIn(0.0, 1.0)
            return ((1.0 - fading) * (1.0 - fading) * vortex.might).toFloat()
        }
        return near(level, player).maxOfOrNull(::loudnessOf) ?: SILENT
    }

    private class Roar : AbstractTickableSoundInstance(AgeContent.TORNADO_ROAR, SoundSource.WEATHER, RandomSource.create()) {
        init {
            looping = true
            delay = 0
            relative = true
            volume = SILENT
        }

        override fun canStartSilent(): Boolean = true

        override fun tick() {
            val client = Minecraft.getInstance()
            val level = client.level ?: return stop()
            val player = client.player ?: return stop()
            volume = loudnessNow(level, player)
        }
    }

    /** The tornadoes near enough to [player] to be felt or seen whirling. */
    private fun near(level: ClientLevel, player: Entity): List<Tornado> =
        level.getEntitiesOfClass(Tornado::class.java, player.boundingBox.inflate(SEEN_ACROSS, SEEN_UP, SEEN_ACROSS))

    /** Heard out to twice as far as its winds reach, and at full loudness once the funnel is this far down. */
    private const val LOUD_BY = 0.3f
    private const val HEARD_FROM = 2.0
    private const val SILENT = 0.0f

    /** Past where any tornado's winds reach, far enough to see its dust go round, to hear it and to stand under its overcast. */
    private const val SEEN_ACROSS = 256.0
    private const val SEEN_UP = 256.0
}
