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
     * How much of the storm is actually on [player], from none of it to all of it.
     *
     * **Graded rather than a switch** (Jonah, 2026-09-07), and read off both lights, because a blizzard has
     * two kinds of shelter and the design turns on the second one.
     *
     * **A roof, by sky light.** It walks round an overhang and down through a canopy, so a lip of rock buys
     * a little quiet, a stand of trees buys some, and a cave or a roofed room buys all of it. There is
     * nothing here about what a roof *is*; the lighting engine already knows.
     *
     * **And a lamp, by block light**, which is the half it would have been easy to drop. Design §5.2's
     * whole counterplay is that light of ten stops the snow settling, stops the water icing and stops you
     * freezing — *the thing that protects your ground already protects you* — and `Blizzard.chill` still
     * enforces exactly that. Grading the sound on sky light alone would have left a torchlit field roaring
     * while the cold had already stopped, which is the one place the sound and the harm must agree.
     *
     * The two are taken at whichever is **more** sheltering, so lighting a path home quietens it the same
     * way roofing it does.
     */
    fun exposure(level: ClientLevel, player: LocalPlayer): Float {
        val at = player.blockPosition()
        val underTheSky = level.getBrightness(LightLayer.SKY, at).toFloat() / OPEN_TO_THE_SKY
        val underALamp = level.getBrightness(LightLayer.BLOCK, at).toFloat() / SHELTERED_BY_LIGHT
        val sheltered = maxOf(ALL_OF_IT - underTheSky, underALamp)
        return (ALL_OF_IT - sheltered).coerceIn(NONE, ALL_OF_IT)
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
     * Keep both winds playing, and let the shelter decide the balance between them.
     *
     * Two sounds: **sheltered** is the storm going on without you, which is what makes a dugout feel like
     * one, and **exposed** is standing in it.
     *
     * **Both at once, crossfaded** (Jonah, 2026-09-07). They used to be a switch, which put a hard cut on
     * a doorway and made every shelter equal — where what a player is actually doing is *finding* cover,
     * and cover comes in degrees. Playing the pair and moving [exposure] between them costs one extra
     * looping sound and makes an overhang audibly worth standing under.
     */
    fun heard(client: Minecraft) {
        val level = client.level ?: return stop(client)
        val player = client.player ?: return stop(client)
        if (blowingIn(level) == null) return stop(client)
        if (playing.isNotEmpty() && playing.none(Wind::isStopped)) return
        stop(client)
        playing = listOf(
            Wind(AgeContent.BLIZZARD_EXPOSED, whenOpen = true),
            Wind(AgeContent.BLIZZARD_SHELTERED, whenOpen = false),
        )
        playing.forEach(client.soundManager::play)
    }

    private fun stop(client: Minecraft) {
        playing.forEach(client.soundManager::stop)
        playing = emptyList()
    }

    private var playing: List<Wind> = emptyList()

    /**
     * How loud a storm this hard is at all, before shelter takes its share.
     *
     * Unchanged from when there was one wind: fierceness is what makes a blizzard louder, and the crossfade
     * only ever divides this between the two.
     */
    private fun loudnessOf(severity: Double): Float =
        (QUIETEST + (LOUDEST - QUIETEST) * (severity - ORDINARY).coerceIn(NONE.toDouble(), ALL_OF_IT.toDouble()))
            .toFloat()

    /**
     * One of the two winds, looping while the storm holds.
     *
     * Tickable so it can stop itself the moment the storm does, rather than playing on to the end of a
     * two-minute file in an Age that has gone quiet.
     */
    private class Wind(val sound: SoundEvent, private val whenOpen: Boolean) :
        AbstractTickableSoundInstance(sound, SoundSource.WEATHER, net.minecraft.util.RandomSource.create()) {

        init {
            looping = true
            delay = 0
            relative = true
            volume = SILENT
        }

        /**
         * **Started silent on purpose**, which vanilla refuses unless a sound says it can take it: whichever
         * of the two is wrong for where you are standing begins at nothing and fades up as you move. Without
         * this the engine drops it before its first tick — "Skipped playing sound, volume was zero" — and a
         * player who started a storm outdoors would never hear the sheltered wind at all.
         */
        override fun canStartSilent(): Boolean = true

        /**
         * Its share of the storm, re-read every tick.
         *
         * The engine recalculates a *tickable* sound's volume on every tick and pushes it to the channel,
         * so a crossfade is this and nothing else — no second sound to schedule, no fade to time.
         */
        override fun tick() {
            val client = Minecraft.getInstance()
            val level = client.level
            val player = client.player
            val blowing = level?.let(::blowingIn)
            if (level == null || player == null || blowing == null) return stop()
            val open = exposure(level, player)
            volume = loudnessOf(blowing.severity) * (if (whenOpen) open else ALL_OF_IT - open)
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
        val ordinary = was * CLEAREST
        if (hard <= HARDEST_EARNED) {
            val bite = ((hard - ORDINARY) / (HARDEST_EARNED - ORDINARY)).coerceIn(0.0, 1.0)
            return (ordinary + (EARNED_WHITEOUT - ordinary) * bite).toFloat()
        }
        // Past what any Age can earn, and only `/age weather blizzard` goes here.
        val over = ((hard - HARDEST_EARNED) / (HARDEST_FORCED - HARDEST_EARNED)).coerceIn(0.0, 1.0)
        return (EARNED_WHITEOUT + (FORCED_WHITEOUT - EARNED_WHITEOUT) * over).toFloat()
    }

    /** The sky light of open ground, so the first block of cover is already worth something. */
    private const val OPEN_TO_THE_SKY = 15.0f

    /**
     * And the block light that answers a blizzard outright — vanilla's own threshold, the one the snow
     * obeys, and the one `Blizzard.chill` stops the cold at.
     *
     * Reached at ten rather than fifteen for that reason: the crossfade must be fully quiet exactly where
     * the freezing stops, or the sound would go on promising a harm that is no longer there.
     */
    private const val SHELTERED_BY_LIGHT = 10.0f

    private const val NONE = 0.0f
    private const val ALL_OF_IT = 1.0f
    private const val SILENT = 0.0f

    /** The air in a whiteout: not white, which reads as a bug, but the grey-white of snow with no sun on it. */
    private val DRIVEN_SNOW = Rgba.of(0xFFC8CFD8u.toInt())

    /** What an ordinary blizzard leaves of the view, as a share of what you could otherwise see. */
    private const val CLEAREST = 0.35f

    /**
     * Where the view is gone at the worst storm an **Age can earn**, in blocks.
     *
     * **The ramp stops just short of the floor rather than on it** (Jonah, 2026-09-05). Landing the
     * hardest earnable blizzard exactly on the limit made the top of the dial read as a clamp; a hair
     * above leaves the absolute worst for something no Age can write, which is where it belongs.
     *
     * **Well short of `Engulfing`'s sandfall either way, and that is the right way round.** A sandfall is a
     * wall of ground you are standing inside and closes at a couple of blocks; a blizzard is weather you
     * walk through, and has to leave you enough to place a torch by.
     */
    private const val EARNED_WHITEOUT = 11.0f

    /** And where it is gone for a storm somebody asked for by hand, past anything instability can buy. */
    private const val FORCED_WHITEOUT = 8.0f

    /**
     * Where the white *begins*, as a share of where it becomes total.
     *
     * **The number that was actually wrong** the first time: at a quarter, fog started under a block from
     * the camera and the whole view was washed even where it had not closed, so a storm meant to leave a
     * few blocks of sight left none.
     *
     * Derived from the pair a walk settled on rather than chosen, so the two numbers that were actually
     * judged — six clear, gone by eleven — are the ones written down.
     */
    private const val EARNED_CLEAR = 6.0f
    private const val BEGINS_AT = EARNED_CLEAR / EARNED_WHITEOUT

    private const val ORDINARY = 1.0

    /** Everything an Age's instability can buy — see `Blizzard.howHardOf`. */
    private const val HARDEST_EARNED = 3.0

    /** The top of `/age weather blizzard`'s own range, which no Age reaches. */
    private const val HARDEST_FORCED = 5.0

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
