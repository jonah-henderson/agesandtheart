package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.MeteorStorm
import co.voik.ephemeris.Rgba
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.util.Mth
import net.minecraft.world.attribute.EnvironmentAttributeSystem
import net.minecraft.world.attribute.EnvironmentAttributes
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
        val how = strengthAt(level, at)
        return if (how <= NONE) was else Rgba.of(was).lerp(toward, how).packed()
    }

    /**
     * How much of a storm's violet reaches this point, one under it and nothing outside its reach.
     *
     * **Uniform across the whole disc and well past it**, rather than falling off from the middle: since
     * the lightmap asks about the camera, a gradient here would be the world changing colour as you walked
     * about under a storm rather than as you walked *out* of one. The falloff is at the far edge only, and
     * it is wide — a storm you can see should not have untouched country standing behind it.
     */
    private fun strengthAt(level: ClientLevel, at: Vec3): Float {
        var strongest = NONE
        for (entity in level.entitiesForRendering()) {
            val storm = entity as? MeteorStorm ?: continue
            val awayX = storm.x - at.x
            val awayZ = storm.z - at.z
            val away = sqrt(awayX * awayX + awayZ * awayZ).toFloat()
            val within = Mth.clamp((CAST_OVER - away) / (CAST_OVER - CAST_WHOLLY), NONE, ONE)
            val how = within * storm.castStrength()
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
     * How far the cast carries, in blocks — **out past what a default render distance draws** (Jonah).
     *
     * The point is that nothing untouched is left in view: a violet ring with ordinary country beyond it
     * reads as an effect, where violet to the horizon reads as the place. Twelve chunks is 192, so this is
     * whole to a comfortable distance past the impacts and gone a little past what is drawn.
     */
    private const val CAST_WHOLLY = 160.0f
    private const val CAST_OVER = 224.0f

    private const val NONE = 0.0f
    private const val ONE = 1.0f
}
