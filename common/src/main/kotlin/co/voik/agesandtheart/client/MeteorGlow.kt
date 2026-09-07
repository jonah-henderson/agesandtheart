package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.MeteorStorm
import co.voik.ephemeris.Rgba
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.level.LightLayer
import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * The violet a meteor storm turns a place, for as long as it is over it (design §5.2).
 *
 * **Everything you can see, not a lit patch of ground.** This began as light blocks laid over the impact
 * disc, and over a disc a hundred and twenty blocks across that read as a spotlight on somewhere else, with
 * ordinary untouched country visible past its edge (Jonah, walked). What a player wants is the one thing
 * that says *you are standing in it* — so it works the way a darkroom's red light does: the world keeps its
 * shapes and loses its colours.
 *
 * **Which means all four of the lightmap's colours, not one.** `LightmapRenderStateExtractor` builds the
 * light texture by summing an ambient floor, sky light and block light, each with its own tint, and takes
 * the greater of the floor and night vision. Tinting only [EnvironmentAttributes.BLOCK_LIGHT_TINT] leaves
 * the daylight white, the shadows black and — the tell from the last walk — night vision grey. All four go
 * violet together, and [EnvironmentAttributes.SKY_LIGHT_FACTOR] comes down with them so the sum cannot
 * clip its way back to white on a bright day.
 *
 * **The lightmap is sampled at the camera**, so this is a whole-view cast rather than a per-block one. That
 * is exactly what is wanted here and is worth knowing before reaching for these layers for anything else:
 * what a positional layer decides is what the *viewer* stands in, not what a block does.
 *
 * **Read off the storms themselves, near to far.** Nothing is sent and nothing is remembered beyond the
 * two numbers the storm entity already carries: it is an entity the client has, so the cast follows it,
 * wells up as it arrives, fades after the last body, and is gone the instant the storm is.
 */
object MeteorGlow {

    /**
     * Lay the cast over whatever the Age's air had already made of the light.
     *
     * Composed like the rest of [AgeLooks]' layers rather than replacing them: an Age that was already
     * strange stays strange, and a storm makes it violet on top of that.
     */
    fun paint(level: ClientLevel, layers: EnvironmentAttributeSystem.Builder): EnvironmentAttributeSystem.Builder {
        // **The guard is the point, not the tint.** These run many times a frame and almost no position in
        // almost any Age is under a storm, so the common case is one loop over a handful of entities and a
        // return of exactly what came in.
        layers.addPositionalLayer(EnvironmentAttributes.AMBIENT_LIGHT_COLOR) { was, at, _ ->
            turnedViolet(level, was, at, THE_FLOOR)
        }
        layers.addPositionalLayer(EnvironmentAttributes.SKY_LIGHT_COLOR) { was, at, _ ->
            turnedViolet(level, was, at, THE_CAST)
        }
        layers.addPositionalLayer(EnvironmentAttributes.BLOCK_LIGHT_TINT) { was, at, _ ->
            turnedViolet(level, was, at, THE_CAST)
        }
        layers.addPositionalLayer(EnvironmentAttributes.NIGHT_VISION_COLOR) { was, at, _ ->
            turnedViolet(level, was, at, THE_CAST)
        }
        // Held down so the violet stays violet. Sky light is the biggest term in the sum by a long way, and
        // at full strength it drives every channel to one — which is the definition of white.
        layers.addPositionalLayer(EnvironmentAttributes.SKY_LIGHT_FACTOR) { was, at, _ ->
            val how = strengthAt(level, at)
            if (how <= NONE) was else was + (HELD_DOWN - was) * how
        }
        return layers
    }

    /** One packed colour dragged [toward] the violet by however much of a storm reaches [at]. */
    private fun turnedViolet(level: ClientLevel, was: Int, at: Vec3, toward: Rgba): Int {
        val how = easedStrengthAt(level, at)
        return if (how <= NONE) was else Rgba.of(was).lerp(toward, how).packed()
    }

    /**
     * [strengthAt], but it can only ever *fall* by a little each tick.
     *
     * **Because the thing this is read off can vanish rather than recede.** A storm is an entity, and an
     * entity stops being tracked at the player's view distance — so walking out of one snapped the world
     * back to its own colours between one step and the next, even though the falloff below is sixteen
     * blocks wide (Jonah, walked). The same cliff is there whenever a storm is discarded, or a player
     * links away. Rising needs no help: the storm's own clock and the falloff already ease it in.
     *
     * The two fields are the exception to no-mutable-state-in-an-object, and a narrow one: they are a
     * smoothing of what is drawn, they are the client's alone, and being wrong about them costs a frame.
     */
    private fun easedStrengthAt(level: ClientLevel, at: Vec3): Float {
        val now = level.gameTime
        if (now != lastStepped) {
            lastStepped = now
            showing += (strongestLastTick - showing).coerceIn(-FADES_BY, FADES_BY)
            strongestLastTick = NONE
        }
        val here = strengthAt(level, at)
        // The strongest of everything asked this tick, since these layers are read at more than one place
        // and the eased value has to follow the brightest of them rather than the last.
        if (here > strongestLastTick) strongestLastTick = here
        return maxOf(showing, here)
    }

    private var showing = NONE
    private var strongestLastTick = NONE
    private var lastStepped = Long.MIN_VALUE

    /** How much of the cast may go out in one tick — a second from full to nothing. */
    private const val FADES_BY = 0.05f

    /**
     * How much of a storm's violet reaches this point.
     *
     * **Uniform across the whole disc**, rather than falling off from the middle: since the lightmap asks
     * about the camera, a gradient here would be the world changing colour as you walked about *under* a
     * storm rather than as you walked *out* of one. The feather is at the edge only.
     *
     * **And it is the disc the bodies land on, exactly** (Jonah) — the lit ground and the pounded ground
     * being different sizes is the same lie either way round.
     *
     * **Scaled by how much of the sky gets in**, which is the lighting engine's own answer and much better
     * than any test we could write: sky light walks round an overhang and through a canopy, so a tree does
     * not switch the warning off, and it is nought in a place genuinely closed in. Sheltering from a
     * meteor storm therefore dims it, which is the right thing for it to mean.
     */
    private fun strengthAt(level: ClientLevel, at: Vec3): Float {
        val open = Mth.clamp(level.getBrightness(LightLayer.SKY, BlockPos.containing(at)) / MOSTLY_OPEN, NONE, ONE)
        if (open <= NONE) return NONE
        var strongest = NONE
        for (entity in level.entitiesForRendering()) {
            val storm = entity as? MeteorStorm ?: continue
            val awayX = storm.x - at.x
            val awayZ = storm.z - at.z
            val away = sqrt(awayX * awayX + awayZ * awayZ).toFloat()
            val castOver = storm.reach + FEATHERED_BY
            val within = Mth.clamp((castOver - away) / FEATHERED_BY, NONE, ONE)
            val how = within * storm.castStrength() * open
            if (how > strongest) strongest = how
        }
        return strongest
    }

    /** The violet everything is lit in. Deep rather than pale, so what it lights keeps no colour of its own. */
    private val THE_CAST = Rgba(0.55f, 0.16f, 1.0f)

    /**
     * And what the darkness under it comes up to.
     *
     * The ambient floor is what makes this visible at all where nothing else lights the ground, and it is
     * what replaced the light blocks: a floor costs nothing, fades smoothly, needs no block in the world
     * and cannot be left burning by an unloading chunk. Dimmer than [THE_CAST] so that adding sky or torch
     * light on top of it still lands under one.
     */
    private val THE_FLOOR = Rgba(0.30f, 0.09f, 0.58f)

    /** What sky light is held down to under a full cast, so the sum stays short of white. */
    private const val HELD_DOWN = 0.35f

    /**
     * How far past the impact disc the cast carries, in blocks.
     *
     * **The disc itself is read off the storm rather than off [MeteorStorm.REACH]**, which is the whole
     * of what a lure changes: a drawn storm falls in a chunk and must therefore light a chunk. Taking the
     * constant lit two hundred and seventy blocks of ground for sixteen blocks of pounding — the lie this
     * is supposed to refuse, in the direction that also wastes the warning.
     *
     * There is nothing left in view to go untouched, because the lightmap is read at the camera: standing
     * anywhere inside this turns the whole world violet to the horizon, rather than painting a ring on the
     * ground with ordinary country beyond it.
     *
     * The feather has to stay well inside the smallest view distance a server is likely to run, or the
     * storm stops being tracked before the cast has faded and [easedStrengthAt] is doing all the work. It
     * does not scale with the disc: what it smooths is a boundary you walk across, and that is the same
     * walk however small the thing inside it.
     */
    private const val FEATHERED_BY = 16.0f

    /** The sky light at which the cast is at full strength; below it, it dims away to nothing. */
    private const val MOSTLY_OPEN = 10.0f

    private const val NONE = 0.0f
    private const val ONE = 1.0f
}
