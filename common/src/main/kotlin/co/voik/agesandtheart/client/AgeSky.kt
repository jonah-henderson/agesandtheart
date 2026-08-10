package co.voik.agesandtheart.client

import co.voik.agesandtheart.sky.Appearance
import co.voik.agesandtheart.sky.CelestialBody
import co.voik.agesandtheart.sky.KnownLooks
import co.voik.agesandtheart.sky.Orbit
import co.voik.agesandtheart.sky.SkySpec
import net.minecraft.client.Minecraft
import net.minecraft.world.level.MoonPhase
import org.joml.Quaternionf

/**
 * The sky **any** generated Age can have: whatever suns and moons it was written with, and its own stars.
 *
 * This is the painter — it decides which bodies go where and how bright, and says so to a [SkyCanvas]. It
 * knows nothing about how a frame is drawn, which is what keeps the whole of Blaze3D inside
 * [Blaze3dSkyCanvas].
 *
 * **In `common` so both loaders draw the same sky.** Each owns only the mixin that calls [draw]; the
 * arguments are the ones vanilla's own `SkyRenderer.renderSunMoonAndStars` is given, so nothing is
 * reconstructed that vanilla already worked out.
 *
 * **Only the bodies and the stars.** The sky disc, the sunrise glow and the dark disc are vanilla's own
 * calls and are left running — per-Age colour is data now (`minecraft:visual/sky_color` and friends on the
 * dimension type or the biome), so taking them over would buy nothing.
 */
object AgeSky {

    /** Below this the stars are too faint to be worth the draw. */
    private const val STARS_WORTH_DRAWING = 0.01f

    private const val FULL_TURN_RADIANS = (2.0 * Math.PI).toFloat()

    /** Vanilla reaches the axis it swings its sky about with this turn about the vertical. */
    private const val SKY_AXIS_DEGREES = -90.0f

    /**
     * Draws the Age's bodies and stars, or returns **false** having drawn nothing.
     *
     * False means "vanilla should draw this one", and it is the ordinary answer in three cases: the level
     * is not an Age, the server has not told us its sky yet, or the sky it told us is one vanilla can
     * already draw. Falling through is better than imitating in all three — a plain Age then runs
     * vanilla's own code rather than our copy of it.
     *
     * [sunAngle], [moonAngle] and [starAngle] are vanilla's, in radians, and are used verbatim for bodies
     * on vanilla's own path. Everything else turns on the Age's own clock.
     */
    fun draw(
        canvas: SkyCanvas,
        sunAngle: Float,
        moonAngle: Float,
        starAngle: Float,
        moonPhase: MoonPhase,
        rainBrightness: Float,
        starBrightness: Float,
    ): Boolean {
        val level = Minecraft.getInstance().level ?: return false
        val spec = KnownLooks.of(level.dimension()) ?: return false
        if (spec.isOrdinary) return false

        val clockTime = level.defaultClockTime
        drawBodies(canvas, spec, clockTime, sunAngle, moonAngle, moonPhase, rainBrightness)

        val stars = spec.stars
        // A reveal dims by where the viewer is, on top of vanilla's night curve.
        val revealed = stars.reveal?.visibilityAt(eyeHeight()) ?: 1.0f
        val visibility = starBrightness * revealed
        if (stars.count > 0 && visibility > STARS_WORTH_DRAWING) {
            canvas.drawStarfield(stars.seed, stars.count, aroundVanillasAxis(starAngle), visibility, clockTime)
        }
        return true
    }

    /**
     * Where the viewer's eye is, for a [co.voik.agesandtheart.sky.StarReveal] to read.
     *
     * The *camera*, not the player: in third person or spectator the sky should answer to where it is
     * being looked at from, and that is also the only position available while no player is embodied.
     */
    private fun eyeHeight(): Double = Minecraft.getInstance().gameRenderer.mainCamera.position().y

    /**
     * Every sun and moon the Age has, **farthest first**, which is what lets a moon cover a sun behind it:
     * moons orbit inside every sun and do not draw additively. Ordering is the only tool available, the sky
     * pass writing no depth.
     */
    private fun drawBodies(
        canvas: SkyCanvas,
        spec: SkySpec,
        clockTime: Long,
        sunAngle: Float,
        moonAngle: Float,
        moonPhase: MoonPhase,
        rainBrightness: Float,
    ) {
        for (body in spec.bodies.sortedByDescending { it.orbit.distance }) {
            val sprite = body.appearance as? Appearance.Sprite ?: continue
            val progress = progressOf(body, clockTime, sunAngle, moonAngle)
            val shape = sprite.shapes[shapeIndexOf(body, sprite, clockTime, moonPhase)]
            // A body that waxes and wanes is lit rather than luminous, and so covers rather than glows.
            val luminous = body.phase == null
            val tint = if (luminous) sprite.tint.dimmed(LUMINOUS_ADDS) else sprite.tint
            canvas.drawBody(
                shape = shape,
                orientation = body.orbit.rotationAtProgress(progress),
                distance = body.orbit.distance,
                angularSize = sprite.angularSize,
                tint = tint.copy(alpha = tint.alpha * rainBrightness),
                emitsOwnLight = luminous,
            )
        }
    }

    /**
     * How much of an authored tint a **luminous** body actually adds to the sky (Jonah, 2026-08-09, walked:
     * "the sun is still coming out almost white rather than red giant red").
     *
     * **A luminous body is blended additively** — `RenderPipelines.CELESTIAL` carries
     * `BlendFunction.OVERLAY`, which is `(SRC_ALPHA, ONE)`, so what is drawn is *summed* onto the sky rather
     * than covering it. A saturated tint at full brightness therefore drives its strongest channel to one
     * while the sky's other two are already high, and the middle of the disc comes out white with the colour
     * surviving only at the rim.
     *
     * Dimming what is added is the lever: the sun contributes less, so its dominant channel saturates over
     * a smaller area and the hue holds across more of the disc.
     *
     * **Know its ceiling, because it has a hard one.** Addition cannot take the sky's green and blue *away*,
     * so against a bright daytime sky no amount of dimming yields a deep red — the best it can do is stop
     * the core clipping. A red giant reads properly against the dim sky such a star would actually give, and
     * an Age that wants one should be written with both. If a body must be dark against a bright sky, the
     * answer is not here but the occluding pipeline (`Blaze3dSkyCanvas.OCCLUDING_BODY_PIPELINE`), which
     * covers instead of adding and costs the body its glow.
     */
    private const val LUMINOUS_ADDS = 0.55f

    /**
     * How far around its circle a body is, in `0.0..1.0`.
     *
     * **A body on vanilla's own path takes vanilla's own angle.** That is exact where reconstructing the
     * day curve would only be close, and it survives vanilla changing that curve — which it has, the sun's
     * schedule now being a timeline rather than a formula. Everything else turns on the Age's clock.
     */
    private fun progressOf(body: CelestialBody, clockTime: Long, sunAngle: Float, moonAngle: Float): Float =
        when (body.orbit) {
            Orbit.VANILLA_SUN -> sunAngle / FULL_TURN_RADIANS
            Orbit.VANILLA_MOON -> moonAngle / FULL_TURN_RADIANS
            else -> body.orbit.progressAt(clockTime)
        }

    /**
     * Which of the body's shapes it is showing. A body with one shape never changes; vanilla's own moon
     * takes vanilla's phase, for the same reason its orbit does.
     */
    private fun shapeIndexOf(
        body: CelestialBody,
        sprite: Appearance.Sprite,
        clockTime: Long,
        moonPhase: MoonPhase,
    ): Int {
        val step = when {
            body.orbit == Orbit.VANILLA_MOON -> moonPhase.index()
            else -> body.phase?.stepAt(clockTime) ?: 0
        }
        return step.coerceIn(0, sprite.shapes.size - 1)
    }

    /**
     * Vanilla turns its sky about the world's ±X axis, reaching it with a quarter turn about the vertical
     * first. An [Orbit] carries that turn in its own ascending node, so this is only for the starfield,
     * which has no orbit.
     */
    private fun aroundVanillasAxis(angle: Float): Quaternionf =
        Quaternionf().rotateY(Math.toRadians(SKY_AXIS_DEGREES.toDouble()).toFloat()).rotateX(angle)
}
