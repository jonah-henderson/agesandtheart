package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.BlizzardPayload
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.level.LightLayer
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes

/**
 * What the client has been told about the storm it is standing in.
 *
 * Server-owned, like [KnownWords]: whether an Age has a blizzard is a fact about its recipe, its fierceness
 * folds in an instability the client is never told, and its bearing comes from a seed a `ClientLevel` does
 * not have. Only *is it raining* crosses on its own, and that cannot tell snow from a shower.
 */
object Storms {

    private var told: BlizzardPayload? = null

    /** What is blowing in [level], or nothing — including where the last word was about another Age. */
    fun blowingIn(level: ClientLevel): BlizzardPayload? {
        val heard = told ?: return null
        if (heard.age != level.dimension().identifier()) return null
        if (!heard.blowing || !level.isRaining) return null
        return heard
    }

    fun remember(payload: BlizzardPayload) {
        told = payload
    }

    /**
     * Whether the storm is actually on [player], as opposed to going on over their head.
     *
     * **The same test the snow itself obeys**, which is the point: what keeps the drift off your ground is
     * what keeps the wind off you, so a player who has worked out one has worked out the other. A roof or a
     * lit room is shelter; standing out in it is not.
     */
    fun exposed(level: ClientLevel, player: LocalPlayer): Boolean {
        val at = player.blockPosition()
        if (!level.canSeeSky(at)) return false
        return level.getBrightness(LightLayer.BLOCK, at) < SHELTERED_BY_LIGHT
    }

    /**
     * The fog closing in while a blizzard blows, laid over whatever the Age already paints.
     *
     * **The same mechanism the corruption gradient uses** — an attribute layer rather than a renderer of
     * ours, so a whiteout costs nothing but a multiplier on two distances. It scales with severity, which
     * is what makes a fierce blizzard a different thing to be out in rather than merely a longer one.
     */
    fun paint(level: ClientLevel, layers: EnvironmentAttributeSystem.Builder): EnvironmentAttributeSystem.Builder {
        val blowing = blowingIn(level) ?: return layers
        // How far you can see at this fierceness: a plain blizzard shortens the view, a bought one is a
        // whiteout. Positional rather than flat, because the same layer has to leave the inside of a
        // shelter alone — a roof over your head is the whole of the counterplay and it must be visible.
        layers.addPositionalLayer(EnvironmentAttributes.FOG_END_DISTANCE) { was, at, _ ->
            if (level.canSeeSky(BlockPos.containing(at))) seenThrough(was, blowing.severity) else was
        }
        layers.addPositionalLayer(EnvironmentAttributes.FOG_START_DISTANCE) { was, at, _ ->
            if (level.canSeeSky(BlockPos.containing(at))) seenThrough(was, blowing.severity) else was
        }
        return layers
    }

    /**
     * Keep the right wind playing, and only one of them.
     *
     * Two sounds and the switch between them is the mechanic: **sheltered** is the storm going on without
     * you, which is what makes a dugout feel like one, and **exposed** is standing in it, which plays while
     * the cold is on you so the sound and the harm are learned together.
     */
    fun heard(client: Minecraft) {
        val level = client.level
        val player = client.player
        if (level == null || player == null) {
            stop(client)
            return
        }
        val blowing = blowingIn(level)
        if (blowing == null) {
            stop(client)
            return
        }
        val wanted = if (exposed(level, player)) AgeContent.BLIZZARD_EXPOSED else AgeContent.BLIZZARD_SHELTERED
        if (playing?.sound === wanted && playing?.isStopped == false) return
        stop(client)
        playing = Wind(wanted, blowing.severity).also(client.soundManager::play)
    }

    private fun stop(client: Minecraft) {
        playing?.let(client.soundManager::stop)
        playing = null
    }

    private var playing: Wind? = null

    /**
     * One of the two winds, looping while the storm holds.
     *
     * Tickable so it can stop itself the moment the storm does, rather than playing on to the end of a
     * two-minute file in an Age that has gone quiet.
     */
    private class Wind(val sound: SoundEvent, severity: Double) :
        AbstractTickableSoundInstance(sound, SoundSource.WEATHER, net.minecraft.util.RandomSource.create()) {

        init {
            looping = true
            delay = 0
            volume = (QUIETEST + (LOUDEST - QUIETEST) * (severity - 1.0).coerceIn(0.0, 1.0)).toFloat()
            relative = true
        }

        override fun tick() {
            val client = Minecraft.getInstance()
            val level = client.level
            if (level == null || blowingIn(level) == null) stop()
        }
    }

    /** How much of [was] survives a storm of this severity, never closer than [NEAREST]. */
    private fun seenThrough(was: Float, severity: Double): Float {
        val left = CLEAREST - (CLEAREST - SEES_LEAST) * (severity - 1.0).coerceIn(0.0, 1.0)
        return (was * left).toFloat().coerceAtLeast(NEAREST)
    }

    /** Vanilla's own threshold, and the one the snow obeys. */
    private const val SHELTERED_BY_LIGHT = 10

    /** What a blizzard leaves of the view at its worst, as a share of what you could see. */
    private const val SEES_LEAST = 0.12
    private const val CLEAREST = 0.5

    /** How near the world closes in at the very worst of it. Blinding would be unfair; this is not. */
    private const val NEAREST = 8.0f

    private const val QUIETEST = 0.5
    private const val LOUDEST = 1.0
}
