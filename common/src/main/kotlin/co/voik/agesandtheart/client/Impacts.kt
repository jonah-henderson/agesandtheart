package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.cataclysm.CataclysmPayload
import co.voik.agesandtheart.age.cataclysm.Doom
import co.voik.agesandtheart.age.cataclysm.ImpactPhase
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import kotlin.math.exp

/**
 * What the client has been told of an end coming to the Age it stands in (design §5.3 "The asteroid"), and what
 * is heard and felt of it: the roar of entry, the rumble arriving long after the light, the blast of the debris
 * reaching overhead, the crackle under the burning sky and the ash after. [ImpactSky] draws it and [ImpactAir]
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
        told.clear()
        heardUpTo.clear()
    }

    /** The end coming to [level], or null where none is. */
    fun comingTo(level: ClientLevel): Doom? = told[level.dimension().identifier()]

    /** How brightly the sky glows with the debris coming back down at [time], from nought to one. */
    fun heatAt(doom: Doom, time: Double): Float = when (doom.phaseAt(time.toLong())) {
        ImpactPhase.TRAVEL -> HEAT_WHILE_TRAVELLING * squared(doom.progressThrough(ImpactPhase.TRAVEL, time))
        ImpactPhase.HEAT -> FULL
        ImpactPhase.DUST -> FULL - (doom.progressThrough(ImpactPhase.DUST, time) * HEAT_FADES_FASTER).coerceAtMost(1.0).toFloat()
        else -> NONE
    }

    /** How thick the dust hangs at [time], from nought to one; the impact winter keeps some for ever. */
    fun dustAt(doom: Doom, time: Double): Float = when (doom.phaseAt(time.toLong())) {
        ImpactPhase.DUST -> (doom.progressThrough(ImpactPhase.DUST, time) * DUST_THICKENS_FASTER).coerceAtMost(1.0).toFloat()
        ImpactPhase.AFTER -> WINTER_DUST
        else -> NONE
    }

    /** How bright the impact's flash still is at [time]: whole at the instant, gone in a few seconds. */
    fun flashAt(doom: Doom, time: Double): Float {
        val since = time - doom.impactAt
        if (since < 0.0 || since > FLASH_LASTS) return NONE
        return exp(-since / FLASH_FADES).toFloat()
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
        scatterAsh(level, minecraft, doom, now)
    }

    /** Plays whatever falls between [after] and [upTo], so a sound is heard once however the ticks fall. */
    private fun hear(level: ClientLevel, minecraft: Minecraft, doom: Doom, after: Long, upTo: Long) {
        fun passed(moment: Long) = moment in (after + 1)..upTo
        val listener = minecraft.player ?: return
        fun play(sound: SoundEvent, volume: Float, pitch: Float) =
            level.playLocalSound(listener.x, listener.y, listener.z, sound, SoundSource.AMBIENT, volume, pitch, false)

        if (passed(doom.startOf(ImpactPhase.ENTRY))) play(SoundEvents.BLAZE_SHOOT, LOUD, DEEPEST)
        if (passed(doom.impactAt + RUMBLE_ARRIVES_AFTER)) {
            play(SoundEvents.LIGHTNING_BOLT_THUNDER, LOUD, DEEPEST)
            play(SoundEvents.GENERIC_EXPLODE.value(), LOUD, DEEPEST)
        }
        if (passed(doom.arrivalAt)) {
            play(SoundEvents.GENERIC_EXPLODE.value(), LOUD, DEEP)
            play(SoundEvents.LIGHTNING_BOLT_IMPACT, LOUD, DEEP)
        }
        val isBurning = doom.phaseAt(upTo) == ImpactPhase.HEAT
        if (isBurning && upTo % CRACKLES_EVERY == 0L) play(SoundEvents.FIRE_AMBIENT, LOUD, DEEP)
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
            val x = camera.x + (random.nextDouble() - HALF) * ASH_ACROSS
            val y = camera.y + (random.nextDouble() - HALF) * ASH_TALL
            val z = camera.z + (random.nextDouble() - HALF) * ASH_ACROSS
            level.addParticle(kind, x, y, z, 0.0, ASH_SINKS, 0.0)
        }
    }

    private const val NONE = 0.0f
    private const val FULL = 1.0f
    private const val HEAT_WHILE_TRAVELLING = 0.6f
    private const val HEAT_FADES_FASTER = 3.0
    private const val DUST_THICKENS_FASTER = 2.0
    private const val WINTER_DUST = 0.55f
    private const val FLASH_LASTS = 100.0
    private const val FLASH_FADES = 14.0

    /** The light comes first and the sound after, which is what says how far away it fell. */
    private const val RUMBLE_ARRIVES_AFTER = 120L
    private const val CRACKLES_EVERY = 13L
    private const val LOUD = 4.0f
    private const val DEEP = 0.6f
    private const val DEEPEST = 0.5f

    private const val SPARKS_IN_THE_HEAT = 6
    private const val ASH_IN_THE_DUST = 30
    private const val ASH_AFTER = 6
    private const val ASH_ACROSS = 32.0
    private const val ASH_TALL = 16.0
    private const val ASH_SINKS = -0.02
    private const val HALF = 0.5
}
