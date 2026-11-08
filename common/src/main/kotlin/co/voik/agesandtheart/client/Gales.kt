package co.voik.agesandtheart.client

import co.voik.agesandtheart.AgeConfig
import co.voik.agesandtheart.age.phenomena.ConeExposure
import co.voik.agesandtheart.age.phenomena.Gale
import co.voik.agesandtheart.age.phenomena.GalePayload
import co.voik.agesandtheart.age.phenomena.Gust
import co.voik.agesandtheart.age.phenomena.Gusts
import co.voik.agesandtheart.age.phenomena.Sampling
import co.voik.agesandtheart.content.AgeContent
import co.voik.ephemeris.sky.LevelWind
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.vehicle.boat.AbstractBoat
import kotlin.math.cos
import kotlin.math.sin

/**
 * What the client has been told about the gale over the Age it stands in ([GalePayload]), a blizzard's wind among
 * them, and what it does with it: the gale blown as the level's wind, the local player and the boat they steer leaned
 * on, the wind heard, and each gust seen and heard coming ([GustSigns]).
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
        felt = Easing()
        heard = Easing()
        blewIn = null
    }

    private var inTheWind = ConeExposure.Remembered()

    /** What the body feels of the wind and what the ear hears of it, each easing towards its own reading. */
    private var felt = Easing()
    private var heard = Easing()

    /**
     * How loud the open wind is where the player stands: **the sky over them, not the wind's cone** (Jonah, walk
     * 2026-10-06), so a lee that keeps the snow off still has the wind howling past it.
     */
    private fun heardOpenness(level: ClientLevel, at: BlockPos): Float = heard.towards(Sampling.exposureAt(level, at))

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
        val exposed = felt.towards(
            Gale.exposureAt(level, player.blockPosition(), inTheWind.read(level, player.eyePosition, ConeExposure.upwindAt(driving))),
        )
        val boat = player.vehicle as? AbstractBoat
        val isSteeringABoat = boat != null && boat.controllingPassenger === player
        if (boat != null && isSteeringABoat) {
            val push = Gale.windPush(strength, exposed, Gale.ORDINARY_FRICTION) * SAILING
            boat.deltaMovement = boat.deltaMovement.add(towardsX * push, 0.0, towardsZ * push)
            return
        }
        val isOutOfTheWind = player.isSpectator || player.abilities.flying || player.isPassenger || player.isInWater
        if (isOutOfTheWind) return
        val friction = if (player.onGround()) {
            level.getBlockState(player.blockPosBelowThatAffectsMyMovement).block.friction
        } else {
            Gale.ORDINARY_FRICTION
        }
        val push = Gale.windPush(strength, exposed, friction)
        player.deltaMovement = player.deltaMovement.add(towardsX * push, 0.0, towardsZ * push)
    }

    /**
     * Keep the two winds playing while a gale blows, the open one louder the more sky is over the player, and a howl
     * rising under each gust from the moment it can be heard coming. **A blizzard's wind is heard as a blizzard's**,
     * its own pair rather than the gale's, and the pair changes over when the snow comes or goes.
     */
    fun heard(client: Minecraft) {
        val level = client.level ?: return stop(client)
        if (client.player == null || !isHeardIn(level)) return stop(client)
        hearTheGust(client, level)
        if (playing.isNotEmpty() && playing.none(Wind::isStopped)) return
        stopTheWinds(client)
        val inABlizzard = isSnowingIn(level)
        playing = if (inABlizzard) {
            listOf(Wind(AgeContent.BLIZZARD_EXPOSED, whenOpen = true, inABlizzard), Wind(AgeContent.BLIZZARD_SHELTERED, whenOpen = false, inABlizzard))
        } else {
            listOf(Wind(AgeContent.GALE_EXPOSED, whenOpen = true, inABlizzard), Wind(AgeContent.GALE_SHELTERED, whenOpen = false, inABlizzard))
        }
        playing.forEach(client.soundManager::play)
    }

    private fun isHeardIn(level: ClientLevel): Boolean = blowingIn(level) != null

    private fun isSnowingIn(level: ClientLevel): Boolean = Storms.blowingIn(level) != null

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

    /**
     * One of the two winds, looping while the gale holds and crossfaded by how open the sky is overhead: **both at
     * once** (Jonah, 2026-09-07), since cover comes in degrees and a switch put a hard cut on every doorway.
     */
    private class Wind(soundEvent: SoundEvent, private val whenOpen: Boolean, private val inABlizzard: Boolean) :
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
            if (isSnowingIn(level) != inABlizzard) return stop()
            val blowing = blowingIn(level) ?: return stop()
            val open = heardOpenness(level, player.blockPosition())
            volume = loudnessOf(blowing) * (if (whenOpen) open else ALL_OF_IT - open) * heardAbove(player)
        }
    }

    /**
     * One gust heard: swelling and rising in pitch through its warning, loudest as it hits, and dying away after it,
     * never quieter by more than [HOWL_DIES_AWAY_TICKS] allow. Muffled rather than silenced under cover, since a gust
     * is heard through a wall.
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
            val gust = gustIn(level)?.takeIf { it.slot == slot }
            val swell = gust?.presence ?: NOTHING_LEFT
            val open = Sampling.exposureAt(level, player.blockPosition())
            val heardThrough = MUFFLED + (ALL_OF_IT - MUFFLED) * open
            val heard = (swell * blowing.lying * LOUDEST_HOWL).toFloat() * heardThrough * heardAbove(player)
            volume = maxOf(heard, volume - DIES_AWAY_EACH_TICK)
            pitch = maxOf(Mth.lerp(swell.toFloat(), LOWEST_PITCH, HIGHEST_PITCH), pitch - FALLS_IN_PITCH_EACH_TICK)
            if (gust == null && volume <= SILENT) stop()
        }
    }

    /** How much of the wind is heard: all of it above the water, none with your head under it. */
    private fun heardAbove(player: LocalPlayer): Float = if (player.isUnderWater) SILENT else ALL_OF_IT

    /** How much more a boat takes of the gale than a walker, its side being a sail of sorts. */
    private const val SAILING = 0.5

    private const val QUIETEST = 0.4
    private const val LOUDEST = 0.9
    private const val SILENT = 0.0f
    private const val ALL_OF_IT = 1.0f

    private const val LOUDEST_HOWL = 1.0
    private const val NOTHING_LEFT = 0.0

    /** How many ticks the loudest howl takes to die away: three seconds. */
    private const val HOWL_DIES_AWAY_TICKS = 60.0f
    private const val DIES_AWAY_EACH_TICK = LOUDEST_HOWL.toFloat() / HOWL_DIES_AWAY_TICKS
    private const val MUFFLED = 0.3f
    private const val LOWEST_PITCH = 0.7f
    private const val HIGHEST_PITCH = 1.3f
    private const val FALLS_IN_PITCH_EACH_TICK = (HIGHEST_PITCH - LOWEST_PITCH) / HOWL_DIES_AWAY_TICKS
}
