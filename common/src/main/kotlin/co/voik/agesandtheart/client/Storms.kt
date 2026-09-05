package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.BlizzardPayload
import co.voik.agesandtheart.content.AgeContent
import co.voik.ephemeris.Rgba
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import net.minecraft.core.particles.ParticleTypes
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
        // **Every layer is laid unconditionally, and each one asks about the storm when it is sampled.**
        // This runs once, when the level's attribute system is built — so an early return for "no blizzard
        // right now" adds nothing and can never add anything later, and the whiteout simply never appeared
        // however hard it blew. `Corruption` has always done it this way; its layers check the wound at the
        // sample rather than at registration, and that is the only shape that works for a condition that
        // comes and goes.
        layers.addPositionalLayer(EnvironmentAttributes.FOG_COLOR) { was, at, _ ->
            if (outInIt(level, at) == null) was else DRIVEN_SNOW.packed()
        }
        layers.addPositionalLayer(EnvironmentAttributes.SKY_COLOR) { was, at, _ ->
            if (outInIt(level, at) == null) was else DRIVEN_SNOW.packed()
        }
        layers.addPositionalLayer(EnvironmentAttributes.FOG_START_DISTANCE) { was, at, _ ->
            outInIt(level, at)?.let { seenThrough(was, it) * BEGINS_AT } ?: was
        }
        layers.addPositionalLayer(EnvironmentAttributes.FOG_END_DISTANCE) { was, at, _ ->
            outInIt(level, at)?.let { seenThrough(was, it) } ?: was
        }
        // **And upward, which is the lesson `Engulfing` already paid for**: ordinary fog is measured to
        // what it is drawn over and the sky is drawn over nothing, so looking straight up out of a
        // whiteout showed clouds sailing past in clear blue. These two are the only way to close it.
        layers.addPositionalLayer(EnvironmentAttributes.SKY_FOG_END_DISTANCE) { was, at, _ ->
            outInIt(level, at)?.let { seenThrough(was, it) } ?: was
        }
        layers.addPositionalLayer(EnvironmentAttributes.CLOUD_FOG_END_DISTANCE) { was, at, _ ->
            outInIt(level, at)?.let { seenThrough(was, it) } ?: was
        }
        return layers
    }

    /**
     * How hard the storm is where this sample is taken, or null where it does not reach.
     *
     * Asked per sample rather than once, because both halves of it change: a storm comes and goes, and a
     * roof is a fact about the place. Shelter has to keep its own air, or the counterplay is invisible.
     */
    private fun outInIt(level: ClientLevel, at: Vec3): Double? {
        val blowing = blowingIn(level) ?: return null
        if (!level.canSeeSky(BlockPos.containing(at))) return null
        return blowing.severity
    }

    /**
     * Whether we are drawing the weather ourselves here, and vanilla should not.
     *
     * Asked from `WeatherEffectRendererMixin`, on the render thread, where there is no level to hand in —
     * so it reads the client's own rather than taking one.
     */
    @JvmStatic
    fun drawingItsOwn(): Boolean {
        val level = Minecraft.getInstance().level ?: return false
        return blowingIn(level) != null
    }

    /**
     * Snow going past you sideways, at the speed it is actually landing.
     *
     * **Vanilla's own precipitation is a gentle drift and it reads as a lie here** — the ground is filling
     * in front of you while the air says light flurries. These are thrown along the storm's bearing at a
     * speed that rises with its severity, so what you see and what the world is doing agree.
     *
     * Drawn around the player rather than from the sky: a blizzard is what you are *in*, and particles
     * spawned overhead would spend their lives falling into view instead of tearing across it. Skipped
     * where the sky cannot be seen, so a shelter is quiet as well as warm.
     */
    fun blow(client: Minecraft) {
        val level = client.level ?: return
        val player = client.player ?: return
        val blowing = blowingIn(level) ?: return
        val driving = blowing.driving()
        val hurry = (RUSHING * blowing.severity).coerceAtMost(FASTEST)
        val at = player.blockPosition()
        val random = level.random
        repeat((FLAKES * blowing.severity).toInt().coerceAtMost(MOST_FLAKES)) {
            val here = at.offset(
                random.nextInt(-AROUND, AROUND),
                random.nextInt(-BELOW, ABOVE),
                random.nextInt(-AROUND, AROUND),
            )
            if (!level.canSeeSky(here)) return@repeat
            if (!level.getBlockState(here).isAir) return@repeat
            level.addParticle(
                ParticleTypes.SNOWFLAKE,
                here.x + random.nextDouble(),
                here.y + random.nextDouble(),
                here.z + random.nextDouble(),
                driving.stepX * hurry,
                -FALLING * hurry,
                driving.stepZ * hurry,
            )
        }
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

    /**
     * How far you can see through a storm this hard, in blocks.
     *
     * **The far end runs to a sandfall's** (Jonah, 2026-09-05): an ordinary blizzard shortens the view, and
     * one bought at the top of the ladder is as blind as standing inside a column of falling sand, which
     * `Engulfing` puts at a couple of blocks. Between the two it is linear in fierceness rather than in
     * anything the storm is doing, because what a player is judging is how bad *this Age* is.
     */
    private fun seenThrough(was: Float, hard: Double): Float {
        val bite = ((hard - ORDINARY) / (HARDEST - ORDINARY)).coerceIn(0.0, 1.0)
        val ordinary = was * CLEAREST
        return (ordinary + (WHITEOUT - ordinary) * bite).toFloat().coerceAtLeast(WHITEOUT)
    }

    /** Vanilla's own threshold, and the one the snow obeys. */
    private const val SHELTERED_BY_LIGHT = 10

    /** The air in a whiteout: not white, which reads as a bug, but the grey-white of snow with no sun on it. */
    private val DRIVEN_SNOW = Rgba.of(0xFFC8CFD8u.toInt())

    /** What an ordinary blizzard leaves of the view, as a share of what you could otherwise see. */
    private const val CLEAREST = 0.35f

    /**
     * Where the view is gone entirely at the fiercest, in blocks.
     *
     * A little past `Engulfing`'s sandfall, which is a wall of ground you are standing inside where this is
     * weather — and far past anything you could navigate by either way.
     */
    private const val WHITEOUT = 4.0f

    /**
     * Where the white *begins*, as a share of where it becomes total.
     *
     * **The number that was actually wrong** (Jonah, 2026-09-05, walked): at a quarter, fog started under a
     * block from the camera and the whole view was washed even where it had not closed, so a storm meant to
     * leave two or three blocks of sight left none. At three fifths there is a clear band you can work in —
     * about two and a half blocks at the fiercest — and the white then takes the rest quickly.
     */
    private const val BEGINS_AT = 0.6f

    private const val ORDINARY = 1.0
    private const val HARDEST = 3.0

    /** How far around the player the storm is drawn, in blocks. */
    private const val AROUND = 14
    private const val ABOVE = 10
    private const val BELOW = 4

    /** How many flakes an ordinary storm throws past you each tick, and the ceiling on a furious one. */
    private const val FLAKES = 60
    private const val MOST_FLAKES = 220

    /** How fast they go sideways, and how much of that they also fall at. */
    private const val RUSHING = 0.9
    private const val FASTEST = 2.4
    private const val FALLING = 0.35

    private const val QUIETEST = 0.5
    private const val LOUDEST = 1.0
}
