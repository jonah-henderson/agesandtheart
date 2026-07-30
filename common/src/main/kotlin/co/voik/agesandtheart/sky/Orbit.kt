package co.voik.agesandtheart.sky

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.Mth
import org.joml.Quaternionf

/**
 * The path one celestial body travels, as a great circle around the camera.
 *
 * **Vanilla is the degenerate case of this, exactly**, which is the property to hold on to when changing it.
 * `LevelRenderer.renderSky` places its sun at local `(0, +100, 0)` and applies
 * `R_y(-90°) · R_x(timeOfDay · 360°)`, so it sweeps one circle about the world +Z axis: east, zenith, west.
 * Here that is [inclinationDegrees] `= 0`, [ascendingNodeDegrees] `= -90`, [distance] `= 100`, and a period of
 * one Minecraft day. Anything else is a tilt and a turn away from it, which is what buys a sky nothing in
 * Minecraft can show.
 *
 * The composite is `R_y(ascendingNode) · R_z(inclination) · R_x(angle)`, read right to left:
 *
 * 1. `R_x(angle)` sweeps the body around its circle, in the plane containing the vertical.
 * 2. `R_z(inclination)` tips that plane over, so the circle no longer passes through the zenith.
 * 3. `R_y(ascendingNode)` spins the whole arrangement about the vertical, choosing which compass direction the
 *    tilt leans toward — the free choice that makes two identically-tilted orbits look unrelated.
 *
 * **The angle is eased with vanilla's own curve rather than advancing uniformly** (see [progressAt]), and that
 * is deliberate rather than mimicry. Tier 1 leaves the lightmap alone, so the world still brightens and dims on
 * `DimensionType.timeOfDay`; a body on a one-day period therefore has to *be* vanilla's sun, or noon would be
 * bright while the sun sat somewhere off to the side. Reusing the curve makes that exact rather than close.
 */
data class Orbit(
    /** How far the circle is tipped out of the plane through the zenith. 0 is vanilla's overhead sweep. */
    val inclinationDegrees: Float,
    /** Which way the tilt leans, as a turn about the vertical. Vanilla's sun is at -90. */
    val ascendingNodeDegrees: Float,
    /** Where on the circle the body sits at day time zero. */
    val phaseDegrees: Float,
    /** Ticks for one revolution. [TICKS_PER_VANILLA_DAY] reproduces vanilla's rate. */
    val periodTicks: Int,
    /** Whether the body travels the other way around its circle. */
    val retrograde: Boolean,
    /** Radius of the circle. Only relative values matter — nothing here is depth-tested (see below). */
    val distance: Float,
) {

    /**
     * Where along its circle this body is, in `0.0..1.0`, at [dayTime].
     *
     * **Steps once per tick, like vanilla, and an earlier version of this was wrong to interpolate.** It took
     * `partialTick` as well and advanced on `dayTime + partialTick`, on the reasoning that
     * `LevelTimeAccess.getTimeOfDay(float)` discards its argument and ours need not. Jonah walked it and saw
     * bodies "slide forward and then tick backwards" — because `partialTick` resets to zero at every tick
     * boundary, and if the value it is added to has not incremented in the same instant, each boundary is a step
     * *backwards*. A sawtooth, not a smooth arc.
     *
     * It very likely does not increment in step, for a reason worth keeping: an Age gets `DerivedLevelData`, so
     * its `getDayTime()` reads the **overworld's**, and the server synchronises time per level only every 20
     * ticks. The value can therefore advance in twenty-tick jumps while a smooth fraction ramps between them.
     *
     * Vanilla discarding `partialTick` now reads as the deliberate choice it probably always was. One tick is
     * 0.015° of arc on a day-long orbit, so there is nothing to see; interpolating a server-authoritative counter
     * needs the previous-and-current pattern entities use, not an addition, and that is not worth carrying for an
     * invisible gain.
     *
     * The easing is `DimensionType.timeOfDay`'s, which is what makes a body linger near the horizon and hurry
     * through the zenith. See the class KDoc for why it is shared rather than reinvented.
     */
    fun progressAt(dayTime: Long): Float {
        val revolutions = dayTime.toDouble() / periodTicks
        val travelled = if (retrograde) -revolutions else revolutions
        val raw = Mth.frac(travelled + phaseDegrees / DEGREES_PER_TURN - QUARTER_TURN)
        val eased = 0.5 - Math.cos(raw * Math.PI) / 2.0
        return ((raw * 2.0 + eased) / 3.0).toFloat()
    }

    /**
     * The rotation placing a body at `(0, distance, 0)` onto its circle at [dayTime].
     *
     * Returned as a quaternion rather than applied to a `PoseStack`, so the caller can bake it straight into
     * vertices — which it must, because `RenderSystem.getModelViewMatrix()` is the identity during the sky pass
     * and the transform has nowhere else to live. See `notes/per-age-skies-research.md` §3.
     */
    fun rotationAt(dayTime: Long): Quaternionf =
        Quaternionf()
            .rotateY(Math.toRadians(ascendingNodeDegrees.toDouble()).toFloat())
            .rotateZ(Math.toRadians(inclinationDegrees.toDouble()).toFloat())
            .rotateX(progressAt(dayTime) * TWO_PI)

    companion object {
        const val TICKS_PER_VANILLA_DAY = 24000

        /** Vanilla's own sun, spelled out — the thing every other orbit is a departure from. */
        val VANILLA_SUN = Orbit(
            inclinationDegrees = 0.0f,
            ascendingNodeDegrees = -90.0f,
            phaseDegrees = 0.0f,
            periodTicks = TICKS_PER_VANILLA_DAY,
            retrograde = false,
            distance = 100.0f,
        )

        private const val DEGREES_PER_TURN = 360.0
        private const val TWO_PI = (Math.PI * 2).toFloat()

        /**
         * `timeOfDay` is offset a quarter turn so that day time 0 is *noon*, which is vanilla's convention
         * (`DimensionType.timeOfDay(6000) == 0`) and easy to get backwards.
         */
        private const val QUARTER_TURN = 0.25

        val CODEC: Codec<Orbit> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.FLOAT.fieldOf("inclination").forGetter(Orbit::inclinationDegrees),
                Codec.FLOAT.fieldOf("ascending_node").forGetter(Orbit::ascendingNodeDegrees),
                Codec.FLOAT.fieldOf("phase").forGetter(Orbit::phaseDegrees),
                Codec.INT.fieldOf("period").forGetter(Orbit::periodTicks),
                Codec.BOOL.optionalFieldOf("retrograde", false).forGetter(Orbit::retrograde),
                Codec.FLOAT.fieldOf("distance").forGetter(Orbit::distance),
            ).apply(instance, ::Orbit)
        }
    }
}
