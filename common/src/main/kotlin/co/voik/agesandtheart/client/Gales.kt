package co.voik.agesandtheart.client

import co.voik.agesandtheart.AgeConfig
import co.voik.agesandtheart.age.phenomena.Blizzard
import co.voik.agesandtheart.age.phenomena.ConeExposure
import co.voik.agesandtheart.age.phenomena.Gale
import co.voik.agesandtheart.age.phenomena.GalePayload
import co.voik.agesandtheart.age.phenomena.Sampling
import co.voik.agesandtheart.content.AgeContent
import co.voik.ephemeris.sky.LevelWind
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.vehicle.boat.AbstractBoat

/**
 * What the client has been told about the gale over the Age it stands in ([GalePayload]), and what it does with it:
 * the gale blown as the level's wind, the local player and the boat they steer leaned on, and the wind heard.
 */
object Gales {

    private var told: GalePayload? = null

    /** The gale over [level] now, or null — including where the last word was about another Age. */
    fun blowingIn(level: ClientLevel): Gale.Blowing? {
        val heard = told ?: return null
        if (heard.age != level.dimension().identifier()) return null
        return heard.blowing
    }

    fun remember(payload: GalePayload) {
        told = payload
        val level = Minecraft.getInstance().level ?: return
        if (payload.age != level.dimension().identifier()) return
        LevelWind.blowInstead(level, payload.blowing?.let(Gale::windOf))
    }

    /** For a client leaving a server, whose Age ids mean nothing on the next. */
    fun forget() {
        told = null
        inTheWind = ConeExposure.Remembered()
    }

    private var inTheWind = ConeExposure.Remembered()

    /**
     * The gale leaning on the local player, or on the boat they steer, as exposed as they stand: here rather than on
     * the server because a client moves its own player and its own boat.
     */
    fun push(client: Minecraft) {
        val level = client.level ?: return
        val player = client.player ?: return
        val blowing = blowingIn(level) ?: return
        if (client.isPaused) return
        val strength = Gale.pushStrength(AgeConfig.galeWind.get(), blowing)
        val exposed = Blizzard.exposureAt(
            level,
            player.blockPosition(),
            inTheWind.read(level, player.eyePosition, ConeExposure.upwindAt(blowing.bearingDegrees)),
        )
        val boat = player.vehicle as? AbstractBoat
        val isSteeringABoat = boat != null && boat.controllingPassenger === player
        if (boat != null && isSteeringABoat) {
            val push = Blizzard.windPush(strength, exposed, Blizzard.ORDINARY_FRICTION) * SAILING
            boat.deltaMovement = boat.deltaMovement.add(blowing.towardsX * push, 0.0, blowing.towardsZ * push)
            return
        }
        val isOutOfTheWind = player.isSpectator || player.abilities.flying || player.isPassenger || player.isInWater
        if (isOutOfTheWind) return
        val friction = if (player.onGround()) {
            level.getBlockState(player.blockPosBelowThatAffectsMyMovement).block.friction
        } else {
            Blizzard.ORDINARY_FRICTION
        }
        val push = Blizzard.windPush(strength, exposed, friction)
        player.deltaMovement = player.deltaMovement.add(blowing.towardsX * push, 0.0, blowing.towardsZ * push)
    }

    /** Keep the two winds playing while a gale blows, the open one louder the more sky is over the player. */
    fun heard(client: Minecraft) {
        val level = client.level ?: return stop(client)
        if (client.player == null || !isHeardIn(level)) return stop(client)
        if (playing.isNotEmpty() && playing.none(Wind::isStopped)) return
        stop(client)
        playing = listOf(Wind(AgeContent.BLIZZARD_EXPOSED, whenOpen = true), Wind(AgeContent.BLIZZARD_SHELTERED, whenOpen = false))
        playing.forEach(client.soundManager::play)
    }

    /** Whether a gale is to be heard in [level]: one blows, and no blizzard is already howling with its own wind. */
    private fun isHeardIn(level: ClientLevel): Boolean = blowingIn(level) != null && Storms.blowingIn(level) == null

    private fun stop(client: Minecraft) {
        playing.forEach(client.soundManager::stop)
        playing = emptyList()
    }

    private var playing: List<Wind> = emptyList()

    /** How loud a gale this hard is, before shelter takes its share. */
    private fun loudnessOf(blowing: Gale.Blowing): Float =
        (blowing.lying * (QUIETEST + (LOUDEST - QUIETEST) * blowing.fury)).toFloat()

    /** One of the two winds, looping while the gale holds and crossfaded by how open the sky is overhead. */
    private class Wind(soundEvent: SoundEvent, private val whenOpen: Boolean) :
        AbstractTickableSoundInstance(soundEvent, SoundSource.WEATHER, RandomSource.create()) {

        init {
            looping = true
            delay = 0
            relative = true
            volume = SILENT
        }

        /** Started silent, as the blizzard's are: the wrong one for where you stand fades up from nothing. */
        override fun canStartSilent(): Boolean = true

        override fun tick() {
            val client = Minecraft.getInstance()
            val level = client.level
            val player = client.player
            if (level == null || player == null || !isHeardIn(level)) return stop()
            val blowing = blowingIn(level) ?: return stop()
            val open = Sampling.exposureAt(level, player.blockPosition())
            volume = loudnessOf(blowing) * (if (whenOpen) open else ALL_OF_IT - open)
        }
    }

    /** How much more a boat takes of the gale than a walker, its side being a sail of sorts. */
    private const val SAILING = 0.5

    private const val QUIETEST = 0.4
    private const val LOUDEST = 0.9
    private const val SILENT = 0.0f
    private const val ALL_OF_IT = 1.0f
}
