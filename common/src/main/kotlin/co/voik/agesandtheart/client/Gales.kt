package co.voik.agesandtheart.client

import co.voik.agesandtheart.AgeConfig
import co.voik.agesandtheart.age.phenomena.Blizzard
import co.voik.agesandtheart.age.phenomena.ConeExposure
import co.voik.agesandtheart.age.phenomena.Gale
import co.voik.agesandtheart.age.phenomena.GalePayload
import co.voik.agesandtheart.age.phenomena.Gust
import co.voik.agesandtheart.age.phenomena.Gusts
import co.voik.agesandtheart.age.phenomena.Sampling
import co.voik.agesandtheart.content.AgeContent
import co.voik.ephemeris.sky.LevelWind
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.vehicle.boat.AbstractBoat
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the client has been told about the gale over the Age it stands in ([GalePayload]), and what it does with it:
 * the gale blown as the level's wind, the local player and the boat they steer leaned on, the wind heard, and each
 * gust seen and heard coming ([GustSigns]).
 */
object Gales {

    private var told: GalePayload? = null

    /** The gale over [level] now, or null — including where the last word was about another Age. */
    fun blowingIn(level: ClientLevel): Gale.Blowing? = toldAbout(level)?.blowing

    /** The gust over [level] now, warning or blowing, or null. */
    fun gustIn(level: ClientLevel): Gust? {
        val heard = toldAbout(level) ?: return null
        if (heard.blowing == null) return null
        return heard.gusts.at(level.gameTime)
    }

    private fun toldAbout(level: ClientLevel): GalePayload? = told?.takeIf { it.age == level.dimension().identifier() }

    private fun gustsIn(level: ClientLevel): Gusts = toldAbout(level)?.gusts ?: Gusts.NONE

    fun remember(payload: GalePayload) {
        told = payload
    }

    /** For a client leaving a server, whose Age ids mean nothing on the next. */
    fun forget() {
        told = null
        inTheWind = ConeExposure.Remembered()
        blewIn = null
    }

    private var inTheWind = ConeExposure.Remembered()

    /** The level this client last blew a gale over, so the wind it laid there can be lifted when the gale goes. */
    private var blewIn: ClientLevel? = null

    /**
     * The gale blown as [client]'s level's wind, gust and all, and the signs of a gust coming. Each tick rather than
     * on the payload, since a gust comes and goes between two of them.
     */
    fun blow(client: Minecraft) {
        val level = client.level
        val blowing = level?.let(::blowingIn)
        if (level == null || blowing == null) {
            blewIn?.let { LevelWind.blowInstead(it, null) }
            blewIn = null
            return
        }
        val gust = gustIn(level)
        LevelWind.blowInstead(level, Gale.windOf(blowing, gust))
        blewIn = level
        val player = client.player
        if (gust != null && player != null && !client.isPaused) {
            GustSigns.show(level, player, Gale.drivingDegrees(blowing, gust), gust, blowing.lying)
        }
    }

    /**
     * The gale leaning on the local player, or on the boat they steer, as exposed as they stand: here rather than on
     * the server because a client moves its own player and its own boat. A player crouching on the ground holds
     * against a gust and feels only the steady lean.
     */
    fun push(client: Minecraft) {
        val level = client.level ?: return
        val player = client.player ?: return
        val blowing = blowingIn(level) ?: return
        if (client.isPaused) return
        val isHoldingOn = player.isShiftKeyDown && player.onGround()
        val gust = gustIn(level).takeUnless { isHoldingOn }
        val strength = Gale.pushStrength(AgeConfig.galeWind.get(), blowing, gustsIn(level), gust)
        val driving = Gale.drivingDegrees(blowing, gust)
        val towardsX = cos(Math.toRadians(driving))
        val towardsZ = sin(Math.toRadians(driving))
        val exposed = Blizzard.exposureAt(
            level,
            player.blockPosition(),
            inTheWind.read(level, player.eyePosition, ConeExposure.upwindAt(driving)),
        )
        val boat = player.vehicle as? AbstractBoat
        val isSteeringABoat = boat != null && boat.controllingPassenger === player
        if (boat != null && isSteeringABoat) {
            val push = Blizzard.windPush(strength, exposed, Blizzard.ORDINARY_FRICTION) * SAILING
            boat.deltaMovement = boat.deltaMovement.add(towardsX * push, 0.0, towardsZ * push)
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
        player.deltaMovement = player.deltaMovement.add(towardsX * push, 0.0, towardsZ * push)
    }

    /**
     * Keep the two winds playing while a gale blows, the open one louder the more sky is over the player, and a howl
     * rising under each gust from the moment it can be heard coming.
     */
    fun heard(client: Minecraft) {
        val level = client.level ?: return stop(client)
        if (client.player == null || !isHeardIn(level)) return stop(client)
        hearTheGust(client, level)
        if (playing.isNotEmpty() && playing.none(Wind::isStopped)) return
        stopTheWinds(client)
        playing = listOf(Wind(AgeContent.BLIZZARD_EXPOSED, whenOpen = true), Wind(AgeContent.BLIZZARD_SHELTERED, whenOpen = false))
        playing.forEach(client.soundManager::play)
    }

    /** Whether a gale is to be heard in [level]: one blows, and no blizzard is already howling with its own wind. */
    private fun isHeardIn(level: ClientLevel): Boolean = blowingIn(level) != null && Storms.blowingIn(level) == null

    private fun hearTheGust(client: Minecraft, level: ClientLevel) {
        val gust = gustIn(level) ?: return
        if (howling?.slot == gust.slot && howling?.isStopped == false) return
        howling = Howl(gust.slot).also(client.soundManager::play)
    }

    private fun stop(client: Minecraft) {
        stopTheWinds(client)
        howling?.let(client.soundManager::stop)
        howling = null
    }

    private fun stopTheWinds(client: Minecraft) {
        playing.forEach(client.soundManager::stop)
        playing = emptyList()
    }

    private var playing: List<Wind> = emptyList()
    private var howling: Howl? = null

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

    /**
     * One gust heard: swelling and rising in pitch through its warning, loudest as it hits, and gone with it. Muffled
     * rather than silenced under cover, since a gust is heard through a wall.
     */
    private class Howl(val slot: Long) :
        AbstractTickableSoundInstance(AgeContent.GUST, SoundSource.WEATHER, RandomSource.create()) {

        init {
            looping = true
            delay = 0
            relative = true
            volume = SILENT
            pitch = LOWEST_PITCH
        }

        override fun canStartSilent(): Boolean = true

        override fun tick() {
            val client = Minecraft.getInstance()
            val level = client.level
            val player = client.player
            if (level == null || player == null || !isHeardIn(level)) return stop()
            val blowing = blowingIn(level) ?: return stop()
            val gust = gustIn(level)?.takeIf { it.slot == slot } ?: return stop()
            val swell = gust.presence
            val open = Sampling.exposureAt(level, player.blockPosition())
            val heardThrough = MUFFLED + (ALL_OF_IT - MUFFLED) * open
            volume = (swell * blowing.lying * LOUDEST_HOWL).toFloat() * heardThrough
            pitch = Mth.lerp(swell.toFloat(), LOWEST_PITCH, HIGHEST_PITCH)
        }
    }

    /** How much more a boat takes of the gale than a walker, its side being a sail of sorts. */
    private const val SAILING = 0.5

    private const val QUIETEST = 0.4
    private const val LOUDEST = 0.9
    private const val SILENT = 0.0f
    private const val ALL_OF_IT = 1.0f

    private const val LOUDEST_HOWL = 1.0
    private const val MUFFLED = 0.3f
    private const val LOWEST_PITCH = 0.7f
    private const val HIGHEST_PITCH = 1.3f
}
