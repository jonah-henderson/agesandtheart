package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.cataclysm.CataclysmPayload
import co.voik.agesandtheart.age.cataclysm.Doom
import co.voik.agesandtheart.age.cataclysm.ImpactPhase
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
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
    private const val CLEAR_OF_THE_EYE = 4.0
}
