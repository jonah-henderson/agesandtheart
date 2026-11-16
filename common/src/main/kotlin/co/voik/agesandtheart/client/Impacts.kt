package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.cataclysm.Blast
import co.voik.agesandtheart.age.cataclysm.CataclysmPayload
import co.voik.agesandtheart.age.cataclysm.Doom
import co.voik.agesandtheart.age.cataclysm.Ejecta
import co.voik.agesandtheart.age.cataclysm.ImpactPhase
import co.voik.agesandtheart.age.cataclysm.ImpactSounds
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance
import co.voik.agesandtheart.age.phenomena.Sampling
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.Vec3
import kotlin.math.exp

/**
 * What the client has been told of an end coming to the Age it stands in (design §5.3 "The asteroid"), and what
 * is heard and felt of it: the roar of entry, the rumble arriving long after the light, the shock front, the blast of
 * the debris reaching overhead, the view shaken by each and by fragments landing close, the crackle under the burning sky and the ash after. [ImpactSky] draws it and [ImpactAir]
 * colours the air, both from the same schedule.
 */
object Impacts {

    private val told = mutableMapOf<Identifier, Doom>()

    /** The tick each Age's sounds were last played up to, so each is heard once as the clock passes it. */
    private val heardUpTo = mutableMapOf<Identifier, Long>()

    fun remember(payload: CataclysmPayload) {
        val doom = payload.doom
        if (doom == null) told.remove(payload.age) else told[payload.age] = doom
    }

    fun forget() {
        ImpactLoops.forget()
        told.clear()
        heardUpTo.clear()
        landed = NONE
    }

    /** The end coming to [level], or null where none is. */
    fun comingTo(level: ClientLevel): Doom? = told[level.dimension().identifier()]

    /** How brightly the sky glows with the debris coming back down at [time], from nought to one. */
    fun heatAt(doom: Doom, time: Double): Float = when (doom.phaseAt(time.toLong())) {
        ImpactPhase.TRAVEL -> HEAT_WHILE_TRAVELLING * squared(doom.progressThrough(ImpactPhase.TRAVEL, time))
        ImpactPhase.HEAT -> FULL
        ImpactPhase.DUST -> {
            val fading = FULL - (doom.progressThrough(ImpactPhase.DUST, time) * HEAT_FADES_FASTER).coerceAtMost(1.0).toFloat()
            (FULL - doom.chokeAt(time)) * fading
        }
        else -> NONE
    }

    fun dustAt(doom: Doom, time: Double): Float = doom.dustAt(time)

    /** How bright the impact's flash still is at [time]: blinding for a moment, then gone in a few seconds. */
    fun flashAt(doom: Doom, time: Double): Float {
        val since = time - doom.impactAt
        if (since < 0.0 || since > FLASH_LASTS) return NONE
        if (since < FLASH_BLINDS_FOR) return FULL
        return exp(-(since - FLASH_BLINDS_FOR) / FLASH_FADES).toFloat()
    }

    /** How strongly the flash washes out [player]'s picture, in [ScreenEffects.FLASH_LEVELS]; null where it does not. */
    fun flashLevelFor(player: LocalPlayer): Int? {
        val level = player.level() as? ClientLevel ?: return null
        val doom = comingTo(level) ?: return null
        val flash = flashAt(doom, level.gameTime.toDouble())
        return Math.round(flash * ScreenEffects.FLASH_LEVELS).takeIf { it > 0 }
    }

    /**
     * How hard the air shimmers over [player] in the debris's heat, in [ScreenEffects.HAZE_LEVELS]; null where it does
     * not, or where they are under cover.
     */
    fun hazeLevelFor(player: LocalPlayer): Int? {
        val level = player.level() as? ClientLevel ?: return null
        val doom = comingTo(level) ?: return null
        val heat = heatAt(doom, level.gameTime.toDouble())
        val isUnderTheSky = level.canSeeSky(BlockPos.containing(player.eyePosition))
        if (!isUnderTheSky) return null
        return Math.round(heat * HAZE_UNDER_A_BURNING_SKY).takeIf { it > 0 }
    }

    private fun squared(value: Double) = (value * value).toFloat()

    fun tick(minecraft: Minecraft) {
        val level = minecraft.level ?: return
        val doom = comingTo(level) ?: return
        val now = level.gameTime
        val age = level.dimension().identifier()
        val heardTo = heardUpTo[age] ?: now
        heardUpTo[age] = now
        hear(level, minecraft, doom, heardTo, now)
        ImpactLoops.hear(minecraft, level)
        if (Blast.shockAt(doom) in (heardTo + 1)..now) thrownByTheShock(level, minecraft, doom)
        scatterAsh(level, minecraft, doom, now)
    }

    /** The local player thrown by the shock front as it passes, here because a client moves its own player. */
    private fun thrownByTheShock(level: ClientLevel, minecraft: Minecraft, doom: Doom) {
        val player = minecraft.player ?: return
        val isOutOfIt = player.isSpectator || player.abilities.flying || player.isPassenger
        if (isOutOfIt) return
        Blast.throwOff(player, doom, Sampling.exposureAt(level, player.blockPosition()))
    }

    /**
     * How hard the view shakes now, for [co.voik.ephemeris.client.CameraShake]: a jolt as the rumble through the ground
     * arrives and a tremor growing under it, the shock front's slam, the blast buffeting whoever is out in it, a jolt
     * as the debris arrives overhead and another as the wall of ash does, and a fragment landing close.
     */
    fun shake(): Float {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return NONE
        val player = minecraft.player ?: return NONE
        val time = level.gameTime + minecraft.deltaTracker.getGameTimeDeltaPartialTick(false).toDouble()
        val fromALanding = landed * exp(-(time - landedAt) / LANDING_FADES).toFloat()
        val doom = comingTo(level) ?: return fromALanding
        val rumbleAt = doom.impactAt + RUMBLE_ARRIVES_AFTER
        val shockAt = Blast.shockAt(doom)
        val isTrembling = time >= rumbleAt && time < shockAt
        val tremor = if (isTrembling) TREMOR + (TREMOR_BY_THE_SHOCK - TREMOR) * ((time - rumbleAt) / (shockAt - rumbleAt)).toFloat() else NONE
        val blowing = Blast.blowingAt(doom, time)
        val buffeting = if (blowing == null) NONE else {
            val gusting = Gales.gustIn(level)?.gusting?.toFloat() ?: NONE
            val exposed = Sampling.exposureAt(level, player.blockPosition())
            (BUFFETED + GUSTS_BUFFET * gusting) * blowing.lying.toFloat() * exposed
        }
        val jolts = jolt(time, rumbleAt, RUMBLE_JOLT, RUMBLE_JOLT_FADES) +
            jolt(time, shockAt, SHOCK_JOLT, SHOCK_JOLT_FADES) +
            jolt(time, doom.arrivalAt, ARRIVAL_JOLT, ARRIVAL_JOLT_FADES) +
            jolt(time, doom.ashArrivesAt, ASH_JOLT, ASH_JOLT_FADES)
        val reached = doom.ashReachedAt(time)
        val ashRumbles = if (reached < 1.0) ASH_RUMBLE * smoothStepUp(ASH_HEARD_FROM, 1.0, reached) else NONE
        return tremor + buffeting + jolts + fromALanding + ashRumbles
    }

    private fun smoothStepUp(from: Double, to: Double, at: Double): Float {
        val along = ((at - from) / (to - from)).coerceIn(0.0, 1.0)
        return (along * along * (3.0 - 2.0 * along)).toFloat()
    }

    private fun jolt(time: Double, at: Long, size: Float, fades: Double): Float {
        val since = time - at
        return if (since < 0.0) NONE else size * exp(-since / fades).toFloat()
    }

    /** How hard the last fragment to land shook the view, and when, so a nearer one coming after outdoes it. */
    private var landed = NONE
    private var landedAt = 0.0

    /** A fragment landing at [at]: felt the harder the closer it came down. */
    fun landedNear(at: Vec3) {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val player = minecraft.player ?: return
        val away = player.position().distanceTo(at)
        val closeness = (1.0 - (away - FELT_WHOLLY_WITHIN) / (FELT_UNTIL - FELT_WHOLLY_WITHIN)).coerceIn(0.0, 1.0).toFloat()
        val now = level.gameTime.toDouble()
        val stillShaking = landed * exp(-(now - landedAt) / LANDING_FADES).toFloat()
        landed = maxOf(stillShaking, LANDING_JOLT * closeness)
        landedAt = now
    }

    /**
     * Plays each moment of the end that falls between [after] and [upTo], so each is heard once however the ticks
     * fall: the first roar as it burns, the rumble through the ground, the shock front, the debris arriving overhead
     * and the wall of ash. What is held rather than heard once is [ImpactLoops]'.
     */
    private fun hear(level: ClientLevel, minecraft: Minecraft, doom: Doom, after: Long, upTo: Long) {
        fun passed(moment: Long) = moment in (after + 1)..upTo
        val listener = minecraft.player ?: return
        fun play(sound: SoundEvent) =
            level.playLocalSound(listener.x, listener.y, listener.z, sound, SoundSource.AMBIENT, LOUD, AS_WRITTEN, false)

        if (passed(doom.startOf(ImpactPhase.ENTRY))) play(ImpactSounds.ENTRY)
        if (passed(doom.impactAt + RUMBLE_ARRIVES_AFTER)) play(ImpactSounds.RUMBLE)
        if (passed(Blast.shockAt(doom))) play(ImpactSounds.SHOCK)
        if (passed(doom.arrivalAt)) play(ImpactSounds.ARRIVAL)
        if (passed(doom.ashArrivesAt)) play(ImpactSounds.ASH_ARRIVAL)
    }

    /** A fragment let go to fall near the eye: heard from it as it comes down. */
    fun fragmentLetGo(fragment: Ejecta) {
        val sounds = Minecraft.getInstance().soundManager
        sounds.play(EntityBoundSoundInstance(ImpactSounds.FRAGMENT_FALLING, SoundSource.AMBIENT, FALLING_LOUD, AS_WRITTEN, fragment, fragment.id.toLong()))
    }

    /** Ash falling round the camera while the dust is up, and a little through the winter after. */
    private fun scatterAsh(level: ClientLevel, minecraft: Minecraft, doom: Doom, now: Long) {
        val camera = minecraft.gameRenderer.mainCamera().position()
        val (count, kind) = when (doom.phaseAt(now)) {
            ImpactPhase.HEAT -> SPARKS_IN_THE_HEAT to ParticleTypes.SMOKE
            ImpactPhase.DUST -> ASH_IN_THE_DUST to ParticleTypes.ASH
            ImpactPhase.AFTER -> ASH_AFTER to ParticleTypes.WHITE_ASH
            else -> return
        }
        val random = level.random
        repeat(count) {
            val x = (random.nextDouble() - HALF) * ASH_ACROSS
            val y = (random.nextDouble() - HALF) * ASH_TALL
            val z = (random.nextDouble() - HALF) * ASH_ACROSS
            // Never in the camera's face, where one speck of ash is drawn the size of the screen.
            if (x * x + y * y + z * z < CLEAR_OF_THE_EYE * CLEAR_OF_THE_EYE) return@repeat
            level.addParticle(kind, camera.x + x, camera.y + y, camera.z + z, 0.0, ASH_SINKS, 0.0)
        }
    }

    private const val NONE = 0.0f
    private const val FULL = 1.0f
    private const val HEAT_WHILE_TRAVELLING = 0.6f
    private const val HEAT_FADES_FASTER = 3.0
    private const val FLASH_LASTS = 160.0
    private const val FLASH_BLINDS_FOR = 8.0
    private const val FLASH_FADES = 26.0

    /** The haze at its worst under the burning sky: two thirds of a plasma sea's, whose glare is its own. */
    private const val HAZE_UNDER_A_BURNING_SKY = 6

    /** The light comes first and the sound after, which is what says how far away it fell. */
    private const val RUMBLE_ARRIVES_AFTER = 120L
    /** How loud a moment is played, which is also how far off it carries; how its pitch is left, to `sounds.json`. */
    private const val LOUD = 4.0f
    private const val AS_WRITTEN = 1.0f
    private const val FALLING_LOUD = 3.0f

    /** How hard each moment shakes the view, a blast close by being one, and over how many ticks a jolt dies. */
    private const val RUMBLE_JOLT = 0.5f
    private const val RUMBLE_JOLT_FADES = 40.0
    private const val TREMOR = 0.08f
    private const val TREMOR_BY_THE_SHOCK = 0.25f
    private const val SHOCK_JOLT = 1.0f
    private const val SHOCK_JOLT_FADES = 50.0
    private const val BUFFETED = 0.1f
    private const val GUSTS_BUFFET = 0.25f
    private const val ARRIVAL_JOLT = 0.7f
    private const val ARRIVAL_JOLT_FADES = 40.0
    private const val ASH_JOLT = 0.45f

    /** The wall of ash rumbling as it comes: how hard by the time it arrives, and from how far along its way. */
    private const val ASH_RUMBLE = 0.5f
    private const val ASH_HEARD_FROM = 0.3
    private const val ASH_JOLT_FADES = 60.0
    private const val LANDING_JOLT = 0.8f
    private const val LANDING_FADES = 12.0

    /** How near a fragment lands to be felt wholly, and how far off it is felt at all, in blocks. */
    private const val FELT_WHOLLY_WITHIN = 10.0
    private const val FELT_UNTIL = 90.0

    private const val SPARKS_IN_THE_HEAT = 6
    private const val ASH_IN_THE_DUST = 30
    private const val ASH_AFTER = 6
    private const val ASH_ACROSS = 32.0
    private const val ASH_TALL = 16.0
    private const val ASH_SINKS = -0.02
    private const val HALF = 0.5
    private const val CLEAR_OF_THE_EYE = 4.0
}
