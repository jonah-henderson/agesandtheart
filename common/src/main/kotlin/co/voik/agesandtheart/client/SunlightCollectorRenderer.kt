package co.voik.agesandtheart.client

import co.voik.agesandtheart.optics.CollectorPiece
import co.voik.agesandtheart.optics.SunlightCollectorBlock
import co.voik.agesandtheart.optics.SunlightCollectorBlockEntity
import co.voik.ephemeris.client.SkyPainter
import co.voik.ephemeris.sky.BodyReading
import co.voik.ephemeris.sky.LevelLooks
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.BlockModelRenderState
import net.minecraft.client.renderer.block.BlockModelResolver
import net.minecraft.client.renderer.block.model.BlockDisplayContext
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.exp

class SunlightCollectorRenderState : BeamRenderState() {
    val dish = BlockModelRenderState()
    var dishFacing: Vector3f = Vector3f(UP)
}

private val UP = Vector3f(0f, 1f, 0f)

/**
 * A collector's diffuse beam, and its dish turning to face the sun it follows ([SunTracking]), easing round rather
 * than jumping when that sun changes, and resting face up while none is up.
 */
class SunlightCollectorRenderer(context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<SunlightCollectorBlockEntity, SunlightCollectorRenderState> {

    private val blockModels: BlockModelResolver = context.blockModelResolver()

    override fun createRenderState(): SunlightCollectorRenderState = SunlightCollectorRenderState()

    override fun extractRenderState(
        collectorEntity: SunlightCollectorBlockEntity,
        state: SunlightCollectorRenderState,
        partialTicks: Float,
        cameraPosition: Vec3,
        breakProgress: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(collectorEntity, state, partialTicks, cameraPosition, breakProgress)
        BeamDrawing.extract(collectorEntity, collectorEntity.level, state, partialTicks)
        blockModels.update(state.dish, collectorEntity.blockState.setValue(SunlightCollectorBlock.PIECE, CollectorPiece.DISH), DISH)
        val level = collectorEntity.level as? ClientLevel
        val wanted = level?.let { SunTracking.followedSun(it, partialTicks) } ?: UP
        state.dishFacing = eased(collectorEntity, wanted)
    }

    /** Turns the dish part of the way toward [wanted], by how long it has been since it was last turned. */
    private fun eased(collectorEntity: SunlightCollectorBlockEntity, wanted: Vector3f): Vector3f {
        val now = System.nanoTime()
        val secondsSince = (now - collectorEntity.dishLastTurnedNanos) / NANOS_PER_SECOND
        collectorEntity.dishLastTurnedNanos = now
        val share = 1f - exp(-secondsSince.toFloat() * TURNING_RATE)
        val facing = Vector3f(collectorEntity.dishFacing).lerp(wanted, share.coerceIn(0f, 1f))
        if (facing.lengthSquared() > 0f) facing.normalize()
        collectorEntity.dishFacing = facing
        return facing
    }

    override fun submit(state: SunlightCollectorRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        BeamDrawing.submit(state, poseStack, collector)
        poseStack.pushPose()
        poseStack.translate(MIDDLE, PIVOT_HEIGHT, MIDDLE)
        poseStack.rotate(Quaternionf().rotationTo(UP, state.dishFacing))
        poseStack.translate(-MIDDLE, -PIVOT_HEIGHT, -MIDDLE)
        state.dish.submit(poseStack, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, NO_OUTLINE)
        poseStack.popPose()
    }

    /** Its diffuse beam reaches past its block, and the dish swings out of it. */
    override fun shouldRenderOffScreen(): Boolean = true

    private companion object {
        const val MIDDLE = 0.5f

        /** Where the dish's model turns about, in blocks: the top of its stem. */
        const val PIVOT_HEIGHT = 13f / 16f
        const val NO_OUTLINE = 0
        const val NANOS_PER_SECOND = 1_000_000_000.0

        /** How quickly the dish closes on where it is going, per second. */
        const val TURNING_RATE = 1.5f
        val DISH: BlockDisplayContext = BlockDisplayContext.create()
    }
}

/**
 * The sun a collector's dish follows: the level's primary sun while it is up, otherwise the brightest of those up (the
 * least dimmed, then the highest), and in a level with vanilla's sky, vanilla's sun.
 */
object SunTracking {
    private const val DEGREES_TO_RADIANS = (Math.PI / 180.0).toFloat()

    /** The direction toward the followed sun, or null while none is up. */
    fun followedSun(level: ClientLevel, partialTicks: Float): Vector3f? {
        if (!level.dimensionType().hasSkyLight()) return null
        val probe = Minecraft.getInstance().gameRenderer.mainCamera().attributeProbe()
        val sunAngle = probe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTicks) * DEGREES_TO_RADIANS
        val moonAngle = probe.getValue(EnvironmentAttributes.MOON_ANGLE, partialTicks) * DEGREES_TO_RADIANS
        val look = LevelLooks.of(level.dimension())
        if (look == null || look.sky.isOrdinary) return vanillasSun(sunAngle).takeIf { it.y > 0f }
        val clock = level.defaultClockTime
        val reading = look.readAt(clock)
        val primary = reading.primaryUnder(look.rules)?.takeIf { it.isSun && it.isUp }
        val brightest = reading.suns.filter { it.isUp }
            .sortedWith(compareBy<BodyReading> { it.body.dimming }.thenByDescending { it.altitudeDegrees })
            .firstOrNull()
        val followed = primary ?: brightest ?: return null
        return SkyPainter.directionOf(followed.body, clock, sunAngle, moonAngle)
    }

    /** Where vanilla's sky renderer draws its sun at [sunAngle], in radians. */
    private fun vanillasSun(sunAngle: Float): Vector3f =
        Quaternionf().rotateY(-Math.PI.toFloat() / 2f).rotateX(sunAngle).transform(Vector3f(UP))
}
