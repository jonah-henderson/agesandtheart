package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.Sampling
import co.voik.agesandtheart.location
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.state.level.QuadParticleRenderState
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.core.BlockPos
import net.minecraft.data.AtlasIds
import net.minecraft.resources.Identifier
import net.minecraft.util.Mth
import org.joml.Matrix3f
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * A streak of wind, racing along and fading in and out, gone where it would pass under cover or into water: a gust's
 * ([GustSigns]), or one going round a tornado ([TornadoSigns]), which turns it as it goes ([steer]).
 *
 * **Laid along its way and turned about it toward the camera**: a quad is square, so the line drawn across its sprite
 * is what makes it long and thin, and it must lie along the way it moves however it is seen.
 */
open class WindStreak(
    level: ClientLevel,
    x: Double,
    y: Double,
    z: Double,
    xd: Double,
    zd: Double,
    lifetime: Int,
    size: Float,
) : SingleQuadParticle(level, x, y, z, streakSprite()) {

    private val along = Vector3f(xd.toFloat(), 0.0f, zd.toFloat()).normalize()

    init {
        this.lifetime = lifetime
        this.quadSize = size
        this.hasPhysics = false
        this.gravity = 0.0f
        this.xd = xd
        this.yd = 0.0
        this.zd = zd
        setColor(WHITE, WHITE, WHITE)
        setAlpha(0.0f)
    }

    /** Sets where it goes next, [xd] and [zd] in blocks a tick; it lies along that way from then. */
    protected fun goTowards(xd: Double, yd: Double, zd: Double) {
        this.xd = xd
        this.yd = yd
        this.zd = zd
        if (xd * xd + zd * zd > STILL) along.set(xd.toFloat(), 0.0f, zd.toFloat()).normalize()
    }

    /** Turns it each tick before it moves; a gust's goes straight. */
    protected open fun steer() = Unit

    override fun tick() {
        xo = x
        yo = y
        zo = z
        val at = BlockPos.containing(x, y, z)
        if (age++ >= lifetime || !Sampling.openToTheSky(level, at) || !level.getFluidState(at).isEmpty) {
            remove()
            return
        }
        steer()
        move(xd, yd, zd)
        setAlpha(ALPHA * fadeAt(age.toFloat() / lifetime))
    }

    private fun fadeAt(through: Float): Float = when {
        through < FADES_IN_UNTIL -> through / FADES_IN_UNTIL
        through > FADES_OUT_FROM -> (1.0f - through) / (1.0f - FADES_OUT_FROM)
        else -> 1.0f
    }

    override fun extract(state: QuadParticleRenderState, camera: Camera, partialTickTime: Float) {
        extractRotatedQuad(state, camera, facing(camera, partialTickTime), partialTickTime)
    }

    /** The quad's width along its way, and its face turned as far toward the camera as that allows. */
    private fun facing(camera: Camera, partialTickTime: Float): Quaternionf {
        val eye = camera.position()
        val toCamera = Vector3f(
            (eye.x - Mth.lerp(partialTickTime.toDouble(), xo, x)).toFloat(),
            (eye.y - Mth.lerp(partialTickTime.toDouble(), yo, y)).toFloat(),
            (eye.z - Mth.lerp(partialTickTime.toDouble(), zo, z)).toFloat(),
        )
        val face = toCamera.sub(Vector3f(along).mul(toCamera.dot(along)))
        if (face.lengthSquared() < SEEN_END_ON) face.set(0.0f, 1.0f, 0.0f)
        face.normalize()
        val up = Vector3f(face).cross(along)
        return Quaternionf().setFromNormalized(Matrix3f(along, up, face))
    }

    override fun getLayer(): Layer = Layer.TRANSLUCENT

    private companion object {
        /** The streak's sprite, a thin line across the middle of a square, off the particle atlas. */
        fun streakSprite(): TextureAtlasSprite =
            Minecraft.getInstance().atlasManager.getAtlasOrThrow(AtlasIds.PARTICLES).getSprite(STREAK)

        val STREAK: Identifier = "gust_streak".location()

        const val WHITE = 0.95f
        const val ALPHA = 0.5f
        const val FADES_IN_UNTIL = 0.2f
        const val FADES_OUT_FROM = 0.6f
        const val SEEN_END_ON = 1.0e-4f
        const val STILL = 1.0e-6
    }
}
