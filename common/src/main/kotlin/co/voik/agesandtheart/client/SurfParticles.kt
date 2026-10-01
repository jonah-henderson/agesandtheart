package co.voik.agesandtheart.client

import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.state.level.QuadParticleRenderState
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.data.AtlasIds
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf

/**
 * The two marks a wave leaves — see [Surf]. Client particles added straight to the engine, so neither has a
 * registered type: nothing about the surf crosses the network.
 *
 * **Both lie flat**, drawn about a fixed rotation rather than turned to the camera, the way vanilla's
 * `ShriekParticle` lays its rings. Their sprite is vanilla's own `generic_7`, a small white disc, until the
 * asset pass: crowded and overlapping, it reads as a band of foam in the game's own pixels.
 */
object SurfParticles {

    /** The sprite both marks are drawn with, off the particle atlas. */
    fun disc(): TextureAtlasSprite =
        Minecraft.getInstance().atlasManager.getAtlasOrThrow(AtlasIds.PARTICLES).getSprite(DISC)

    private val DISC: Identifier = Identifier.withDefaultNamespace("generic_7")

    /**
     * **How high a mark lies over its surface: foam always over wet sand, and each new mark a hair from the
     * last** (Jonah, walked 2026-10-01: the wave fought the wet patches). Two flat quads at one height
     * flicker through each other, so the foam keeps a band of its own above the wet sand's, and within each
     * band the next mark takes the next of a few slivers, so two that overlap are rarely level.
     */
    private var nextSliver = 0

    private fun sliver(): Double {
        nextSliver = (nextSliver + 1) % SLIVERS
        return nextSliver * SLIVER
    }

    /** Flat on the ground or the water, at [yaw] about the vertical. */
    private fun lyingFlat(yaw: Float): Quaternionf = Quaternionf().rotationYXZ(yaw, -Mth.HALF_PI, 0.0f)

    /**
     * A fleck of foam in a wave: it rides in landward from [from] to [furthest], slowing as it goes, runs up
     * the sand, and pulls back part of the way as it fades. Where it gets to it leaves wet sand behind it,
     * if that is past the waterline.
     *
     * [landward] is the direction it runs in, and the path is measured along it from the waterline: below
     * zero is over the water and above is over the sand, so its height eases from one to the other as it
     * crosses.
     */
    class Foam(
        level: ClientLevel,
        private val waterline: Vec3,
        private val landward: Vec3,
        private val from: Double,
        private val furthest: Double,
        private val waterTop: Double,
        private val sandTop: Double,
        lifetime: Int,
        size: Float,
        private val yaw: Float,
    ) : SingleQuadParticle(level, waterline.x, waterTop, waterline.z, disc()) {

        private var wetted = false
        private val lift = FOAM_LIFT + sliver()

        init {
            this.lifetime = lifetime
            this.quadSize = size
            this.hasPhysics = false
            this.gravity = 0.0f
            setColor(FOAM_WHITE, FOAM_WHITE, FOAM_WHITE)
            setAlpha(FOAM_ALPHA)
            placeAt(from)
            xo = x
            yo = y
            zo = z
        }

        override fun tick() {
            xo = x
            yo = y
            zo = z
            if (age++ >= lifetime) {
                remove()
                return
            }
            val through = age.toFloat() / lifetime
            placeAt(along(through))
            setAlpha(FOAM_ALPHA * fadeAt(through))
            if (!wetted && through >= TURNS_AT) {
                wetted = true
                if (furthest > WETS_PAST) {
                    Minecraft.getInstance().particleEngine.add(
                        WetSand(level as ClientLevel, x, sandTop + WET_LIFT + sliver(), z, quadSize * WET_WIDER, yaw),
                    )
                }
            }
        }

        /** How far along its path it is at [through] of its life: in fast and slowing, then back a little. */
        private fun along(through: Float): Double {
            if (through < TURNS_AT) {
                val inbound = through / TURNS_AT
                val easedOut = 1.0 - (1.0 - inbound) * (1.0 - inbound)
                return from + (furthest - from) * easedOut
            }
            val outbound = (through - TURNS_AT) / (1.0f - TURNS_AT)
            val back = furthest - (furthest - from) * PULLS_BACK
            return furthest + (back - furthest) * outbound * outbound
        }

        private fun placeAt(along: Double) {
            x = waterline.x + landward.x * along
            z = waterline.z + landward.z * along
            // Up the ninth of a block from the water's face to the sand's top as it crosses the line.
            val onShore = Mth.clamp(along / CROSSING, 0.0, 1.0)
            y = Mth.lerp(onShore, waterTop, sandTop) + lift
        }

        private fun fadeAt(through: Float): Float =
            if (through < FADES_FROM) 1.0f else 1.0f - (through - FADES_FROM) / (1.0f - FADES_FROM)

        override fun extract(state: QuadParticleRenderState, camera: Camera, partialTickTime: Float) {
            extractRotatedQuad(state, camera, lyingFlat(yaw), partialTickTime)
        }

        override fun getLayer(): Layer = Layer.TRANSLUCENT
    }

    /** Sand the wash reached, darker for a few seconds and drying back to white. */
    class WetSand(
        level: ClientLevel,
        x: Double,
        y: Double,
        z: Double,
        size: Float,
        private val yaw: Float,
    ) : SingleQuadParticle(level, x, y, z, disc()) {

        init {
            lifetime = Mth.nextInt(level.random, SHORTEST_DRYING, LONGEST_DRYING)
            quadSize = size
            hasPhysics = false
            gravity = 0.0f
            setColor(WET_RED, WET_GREEN, WET_BLUE)
            setAlpha(WET_ALPHA)
        }

        override fun tick() {
            xo = x
            yo = y
            zo = z
            if (age++ >= lifetime) {
                remove()
                return
            }
            setAlpha(WET_ALPHA * (1.0f - age.toFloat() / lifetime))
        }

        override fun extract(state: QuadParticleRenderState, camera: Camera, partialTickTime: Float) {
            extractRotatedQuad(state, camera, lyingFlat(yaw), partialTickTime)
        }

        override fun getLayer(): Layer = Layer.TRANSLUCENT
    }

    private const val FOAM_WHITE = 0.97f
    private const val FOAM_ALPHA = 0.85f

    /** How much of its life a fleck spends rushing in; the rest it spends pulling back and fading. */
    private const val TURNS_AT = 0.5f
    private const val FADES_FROM = 0.45f

    /** What share of the way back to where it started a fleck pulls back before it is gone. */
    private const val PULLS_BACK = 0.6

    /** How far past the waterline, in blocks, a fleck has to have run to leave the sand wet. */
    private const val WETS_PAST = 0.15

    /** How far along the path the climb from the water's face onto the sand takes. */
    private const val CROSSING = 0.15

    /** Clear of the surface each lies on: wet sand just over it, foam over the wet sand's whole band. */
    private const val WET_LIFT = 0.01
    private const val FOAM_LIFT = 0.03
    private const val SLIVERS = 8
    private const val SLIVER = 0.0015

    private const val WET_WIDER = 1.4f
    private const val WET_RED = 0.45f
    private const val WET_GREEN = 0.42f
    private const val WET_BLUE = 0.36f
    private const val WET_ALPHA = 0.3f
    private const val SHORTEST_DRYING = 100
    private const val LONGEST_DRYING = 180
}
