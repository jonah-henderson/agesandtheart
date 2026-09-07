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
 * The violet cast a meteor storm throws over the ground under it (design §5.2).
 *
 * **Because Minecraft's light has no colour, and this is the seam that does.** The lighting engine carries
 * one number per block and no hue, so nothing placed in the world can light a room blue — a soul lantern
 * is a blue *object* casting the same warm light as a torch. What *is* tintable is the light after it
 * arrives: `BLOCK_LIGHT_TINT` colours everything block light touches, which is how a wound drains the
 * colour out of a place ([Corruption]) and how this puts one back in.
 *
 * So the storm's markers do the *lighting* — real light blocks, so the ground is genuinely bright and
 * nothing spawns on it — and this does the *colour*. Neither could manage the other.
 *
 * **Read off the storms themselves, near to far.** Nothing is sent and nothing is remembered: a storm is
 * an entity the client already has, so the tint follows it, fades with distance, and is gone the instant
 * the storm is.
 */
object MeteorGlow {

    /**
     * Lay the cast over whatever the Age's air had already made of the light.
     *
     * Composed like the rest of [AgeLooks]' layers rather than replacing them: an Age that was already
     * strange stays strange, and a storm makes it violet on top of that.
     */
    fun paint(level: ClientLevel, layers: EnvironmentAttributeSystem.Builder): EnvironmentAttributeSystem.Builder {
        // **The guard is the point, not the tint.** This runs many times a frame and almost no position in
        // almost any Age is under a storm, so the common case is one loop over a handful of entities and a
        // return of exactly what came in.
        layers.addPositionalLayer(EnvironmentAttributes.BLOCK_LIGHT_TINT) { was, at, _ ->
            val how = strengthAt(level, at)
            if (how <= NONE) was else Rgba.of(was).lerp(COLD_FIRE, how).packed()
        }
        return layers
    }

    /**
     * How much of a storm's light reaches this point, one at the middle of the fall and nothing outside it.
     *
     * **Squared off at the edge rather than cut**, so walking into the lit ground is a place getting
     * stranger rather than a line you cross.
     */
    private fun strengthAt(level: ClientLevel, at: Vec3): Float {
        var strongest = NONE
        for (entity in level.entitiesForRendering()) {
            val storm = entity as? MeteorStorm ?: continue
            if (!storm.lighting()) continue
            val awayX = storm.x - at.x
            val awayZ = storm.z - at.z
            val away = sqrt(awayX * awayX + awayZ * awayZ).toFloat()
            val how = Mth.clamp(ONE - away / CAST_OVER, NONE, ONE)
            if (how > strongest) strongest = how
        }
        return strongest
    }

    /** Violet, and the same violet the sky is coming in at, so the two read as one arrival. */
    private val COLD_FIRE = Rgba(0.55f, 0.42f, 1.0f)

    /** A good deal wider than the fall itself, so the light arrives before the bodies do. */
    private const val CAST_OVER = 46.0f

    private const val NONE = 0.0f
    private const val ONE = 1.0f
}
