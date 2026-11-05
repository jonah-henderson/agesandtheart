package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.Blizzard
import co.voik.agesandtheart.age.phenomena.BlizzardPayload
import co.voik.agesandtheart.age.phenomena.Sampling
import co.voik.agesandtheart.age.phenomena.ConeExposure
import co.voik.agesandtheart.age.phenomena.Gale
import kotlin.math.cos
import kotlin.math.sin
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.ephemeris.Rgba
import net.minecraft.core.BlockPos
import net.minecraft.util.ARGB
import net.minecraft.world.phys.Vec3
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes
import org.joml.Vector3fc

/**
 * What the client has been told about the storm it is standing in, and what is the storm's own: the whiteout and
 * the snow thrown past. Its wind, which pushes and is heard, is the gale's ([Gales]), and so is its bearing.
 *
 * Server-owned, like [KnownWords]: whether an Age has a blizzard is a fact about its recipe, and its fierceness
 * folds in an instability the client is never told. Only *is it raining* crosses on its own, and that cannot tell
 * snow from a shower.
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

    /** For a client leaving a server, whose Age ids mean nothing on the next. */
    fun forget() {
        told = null
        inTheWind = ConeExposure.Remembered()
        seen = Easing()
    }

    /** The local player's last [ConeExposure] reading, which every sample below is capped by. */
    private var inTheWind = ConeExposure.Remembered()

    /**
     * How much of [blowing] is actually on [at], from none of it to all of it — [Blizzard.exposureAt], the
     * same definition the cold uses, with the wind read from where the local player stands.
     *
     * **Cover only, deliberately.** A fire warms you without sheltering you, so it takes the edge off the
     * cold and changes nothing here: you are still standing in the wind, and the sound should say so.
     */
    private fun exposure(level: ClientLevel, at: BlockPos): Float {
        val player = Minecraft.getInstance().player ?: return Sampling.exposureAt(level, at)
        val driving = Gales.blowingIn(level)?.bearingDegrees ?: return seen.towards(Sampling.exposureAt(level, at))
        val reading = inTheWind.read(level, player.eyePosition, ConeExposure.snowFrom(driving))
        return seen.towards(Gale.exposureAt(level, at, reading))
    }

    /** What the eye is shown, easing towards its reading. */
    private var seen = Easing()

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
            blowingIn(level)?.let { whitenedBy(level, at, was) } ?: was
        }
        layers.addPositionalLayer(EnvironmentAttributes.SKY_COLOR) { was, at, _ ->
            blowingIn(level)?.let { whitenedBy(level, at, was) } ?: was
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
        val exposed = exposure(level, BlockPos.containing(at))
        if (exposed <= NONE) return null
        return blowing.severity * exposed
    }

    /** How far toward a whiteout this sample is — the colour follows the distances rather than snapping. */
    private fun whitenedBy(level: ClientLevel, at: Vec3, was: Vector3fc): Vector3fc {
        val exposed = exposure(level, BlockPos.containing(at))
        return ARGB.srgbLerp(exposed, was, DRIVEN_SNOW.rgb())
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
     * in front of you while the air says light flurries. These are thrown along the wind's bearing at a
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
        val wind = Gales.blowingIn(level) ?: return
        val drivingX = cos(Math.toRadians(wind.bearingDegrees))
        val drivingZ = sin(Math.toRadians(wind.bearingDegrees))
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
                drivingX * hurry,
                -Blizzard.SNOW_FALLS_PER_BLOCK * hurry,
                drivingZ * hurry,
            )
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
        if (hard <= Blizzard.HARDEST_EARNED) {
            val bite = ((hard - Rung.ORDINARY) / (Blizzard.HARDEST_EARNED - Rung.ORDINARY)).coerceIn(0.0, 1.0)
            return (ordinary + (EARNED_WHITEOUT - ordinary) * bite).toFloat()
        }
        // Past what any Age can earn, and only `/age weather blizzard` goes here.
        val over = ((hard - Blizzard.HARDEST_EARNED) / (Blizzard.HARDEST_FORCED - Blizzard.HARDEST_EARNED))
            .coerceIn(0.0, 1.0)
        return (EARNED_WHITEOUT + (FORCED_WHITEOUT - EARNED_WHITEOUT) * over).toFloat()
    }

    private const val NONE = 0.0f

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

    /** How far around the player the storm is drawn, in blocks. */
    private const val AROUND = 14
    private const val ABOVE = 10
    private const val BELOW = 4

    /** How many flakes an ordinary storm throws past you each tick, and the ceiling on a furious one. */
    private const val FLAKES = 60
    private const val MOST_FLAKES = 220

    /** How fast they go sideways. */
    private const val RUSHING = 0.9
    private const val FASTEST = 2.4

}
