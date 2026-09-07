package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.MeteorStorm
import co.voik.ephemeris.Rgba
import co.voik.ephemeris.client.Blaze3dSkyCanvas
import co.voik.ephemeris.client.LevelRendering
import co.voik.ephemeris.client.SkyMoment
import net.minecraft.resources.Identifier
import org.joml.Quaternionf
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * A meteor storm coming in, drawn where it can be seen from a long way off (design §5.2).
 *
 * **The telegraph is the mechanic, not decoration on it.** A crater that simply appears is a punishment
 * and one you watched arrive for half a minute is a challenge, and they differ in nothing else. So this is
 * not an effect over the storm — it *is* the first thirty seconds of it, and the falling is what happens
 * after.
 *
 * **It sells an arrival from further away than anything can be rendered.** What a player sees is a point
 * of light no bigger than a star, brightening and swelling until it splits into as many pieces as will
 * actually fall, each of those growing on until the real bodies take over and ordinary rendering has them.
 * Nothing here is far away at all — a sky body has no distance, only a direction and a size — which is
 * exactly why it can pretend to be.
 *
 * **It reads the storm and is told nothing.** A [MeteorStorm] is an entity, so a client already has its
 * position and its age; there is no packet, no schedule to keep in step, and the telegraph cannot outlive
 * the thing it is announcing because it is drawn from it.
 *
 * **Blue, and that is a deliberate break** (Jonah, 2026-09-06): the pack's fire, lava, magma, bombs and
 * gobbets are all red and orange, so a hazard arriving out of the sky reads as a different *kind* of thing
 * by being at the other end of the spectrum.
 */
object MeteorSky {

    /** Called from [AgeLooks], which is called from each loader's client entrypoint. */
    fun register() {
        // An overlay rather than a sky of our own: a storm is something an Age hangs *above* its sun and
        // stars, and taking the sky to draw one would take the Age's own sky away for the duration.
        LevelRendering.skyOverlay(::draw)
    }

    private fun draw(moment: SkyMoment) {
        val eye = moment.camera.position()
        for (entity in moment.level.entitiesForRendering()) {
            val storm = entity as? MeteorStorm ?: continue
            val comingIn = storm.approachedBy(NO_PARTIAL)
            if (comingIn >= ARRIVED) continue
            drawApproach(storm, comingIn, eye.x, eye.y, eye.z)
        }
    }

    /**
     * One storm's worth of lights, aimed at where it will actually fall.
     *
     * The aim is the direction from the eye to the storm, so the pieces hang over the ground they are
     * about to hit and a player can read *where* rather than only *that* — which is the whole of a spatial
     * counterplay. The distance is thrown away deliberately: these are meant to look astronomically far,
     * so only the bearing survives.
     */
    private fun drawApproach(storm: MeteorStorm, comingIn: Float, eyeX: Double, eyeY: Double, eyeZ: Double) {
        val awayX = (storm.x - eyeX).toFloat()
        val awayY = (storm.y - eyeY).toFloat()
        val awayZ = (storm.z - eyeZ).toFloat()
        val reach = sqrt(awayX * awayX + awayY * awayY + awayZ * awayZ)
        if (reach <= TOUCHING) return
        val aim = Quaternionf().rotateTo(UP_X, UP_Y, UP_Z, awayX / reach, awayY / reach, awayZ / reach)

        val pieces = if (comingIn < SPLITS_AT) ONE else storm.bodies.coerceAtLeast(ONE)
        // Nothing until it has split, then opening steadily — so the moment of splitting is the moment the
        // sky stops being one strange star and starts being a count of what is coming.
        val opened = ((comingIn - SPLITS_AT) / (ARRIVED - SPLITS_AT)).coerceIn(NONE_OF_IT, ALL_OF_IT)
        val spread = SPREAD_WHEN_OPEN * opened

        for (piece in 0..<pieces) {
            val around = FULL_TURN * piece / pieces
            val orientation = Quaternionf(aim).mul(Quaternionf().rotateY(around).rotateX(spread))
            Blaze3dSkyCanvas.drawBody(
                shape = SHAPE,
                orientation = orientation,
                distance = FAR_OFF,
                // Squared, so most of the growth happens at the end: a light that swelled evenly for
                // thirty seconds would look like it was being turned up rather than coming closer.
                angularSize = LIKE_A_STAR + (ON_ARRIVAL - LIKE_A_STAR) * comingIn * comingIn,
                tint = COLD_FIRE.copy(alpha = COLD_FIRE.alpha * (DIMMEST + (ALL_OF_IT - DIMMEST) * comingIn)),
                veil = Rgba.CLEAR,
                // It is a light in the sky rather than a thing blocking one, so it adds itself and can be
                // seen against daylight — which matters, because a storm does not wait for night.
                emitsOwnLight = true,
            )
        }
    }

    /** Vanilla's own sun sprite off the celestials atlas, which needs no asset of ours to tint. */
    private val SHAPE: Identifier = Identifier.withDefaultNamespace("sun")

    /** Violet-white, and hot at the core. */
    private val COLD_FIRE = Rgba(0.62f, 0.45f, 1.0f, 0.95f)

    /** Where in the approach the one light becomes many. */
    private const val SPLITS_AT = 0.45f

    /** How far the pieces open out by the time they arrive, in radians about the aim. */
    private const val SPREAD_WHEN_OPEN = 0.16f

    /** Vanilla's sun is 30 at a distance of 100, so this starts well under a star and ends under a moon. */
    private const val LIKE_A_STAR = 1.2f
    private const val ON_ARRIVAL = 16.0f
    private const val FAR_OFF = 100.0f

    private const val DIMMEST = 0.35f

    /**
     * Nought, and it costs nothing.
     *
     * The whole animation runs over thirty seconds, so a tick is a three-hundredth of it — smaller than
     * the eye can catch, and smaller than the rounding in the sizes above.
     */
    private const val NO_PARTIAL = 0.0f

    private const val ONE = 1
    private const val ARRIVED = 1.0f
    private const val NONE_OF_IT = 0.0f
    private const val ALL_OF_IT = 1.0f
    private const val TOUCHING = 0.001f
    private const val FULL_TURN = (2.0 * PI).toFloat()

    private const val UP_X = 0.0f
    private const val UP_Y = 1.0f
    private const val UP_Z = 0.0f
}
