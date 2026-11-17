package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.imaging.HungPicture
import co.voik.agesandtheart.imaging.ImagingComponents
import co.voik.agesandtheart.imaging.PictureSizes
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import java.util.UUID
import kotlin.math.sqrt
import kotlin.math.tan

class HungPictureRenderState : EntityRenderState() {
    var facing: Direction = Direction.SOUTH
    var picture: UUID? = null
    var size: PictureSizes.Size = PictureSizes.Size.SMALL
    var texture: Identifier? = null
    var development = 1.0f
}

/**
 * A hung picture: its frame, a block square a sixteenth deep, and the picture across the frame's face, at whichever
 * size its width on screen calls for ([PictureTextures]). The frame stands in on dark oak until it is drawn.
 */
class HungPictureRenderer(context: EntityRendererProvider.Context) : EntityRenderer<HungPicture, HungPictureRenderState>(context) {

    override fun createRenderState() = HungPictureRenderState()

    override fun extractRenderState(entity: HungPicture, state: HungPictureRenderState, partialTicks: Float) {
        super.extractRenderState(entity, state, partialTicks)
        state.facing = entity.direction
        state.picture = entity.image.get(ImagingComponents.PICTURE)?.id
        state.size = sizeFor(sqrt(state.distanceToCameraSq))
        state.texture = state.picture?.let { PictureTextures.textureFor(it, state.size) }
        state.development = state.picture?.let(PictureTextures::development) ?: 1.0f
    }

    override fun submit(state: HungPictureRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        poseStack.pushPose()
        poseStack.rotateDegrees(Axis.YP, TURN_FROM_SOUTH - state.facing.toYRot())
        val light = state.lightCoords
        collector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(FRAME)) { pose, buffer -> frame(pose, buffer, light) }
        val texture = state.texture ?: UNDEVELOPED
        val developed = ARGB.scaleRGB(WHITE, state.development)
        collector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(texture)) { pose, buffer -> picture(pose, buffer, light, developed) }
        poseStack.popPose()
        super.submit(state, poseStack, collector, camera)
    }

    /** The smallest size at least as wide as the picture is on screen, so a far one never shimmers and a near one is sharp. */
    private fun sizeFor(distance: Double): PictureSizes.Size {
        val minecraft = Minecraft.getInstance()
        val halfFieldOfView = Math.toRadians(minecraft.options.fov().get() / 2.0)
        val pixelsAcross = minecraft.window.height / (2.0 * tan(halfFieldOfView) * distance.coerceAtLeast(NEAREST))
        return PictureSizes.Size.entries.sortedBy { it.width }.firstOrNull { it.width >= pixelsAcross * SHARPNESS } ?: PictureSizes.Size.FULL
    }

    /** The frame as a box: the face behind the picture, the back against the wall, and the four edges. */
    private fun frame(pose: PoseStack.Pose, buffer: VertexConsumer, light: Int) {
        val (front, back) = -HALF_DEPTH to HALF_DEPTH
        quad(pose, buffer, light, floatArrayOf(HALF, -HALF, front, -HALF, -HALF, front, -HALF, HALF, front, HALF, HALF, front), 0f, 0f, -1f)
        quad(pose, buffer, light, floatArrayOf(-HALF, -HALF, back, HALF, -HALF, back, HALF, HALF, back, -HALF, HALF, back), 0f, 0f, 1f)
        quad(pose, buffer, light, floatArrayOf(HALF, HALF, front, -HALF, HALF, front, -HALF, HALF, back, HALF, HALF, back), 0f, 1f, 0f)
        quad(pose, buffer, light, floatArrayOf(-HALF, -HALF, front, HALF, -HALF, front, HALF, -HALF, back, -HALF, -HALF, back), 0f, -1f, 0f)
        quad(pose, buffer, light, floatArrayOf(HALF, -HALF, back, HALF, -HALF, front, HALF, HALF, front, HALF, HALF, back), 1f, 0f, 0f)
        quad(pose, buffer, light, floatArrayOf(-HALF, -HALF, front, -HALF, -HALF, back, -HALF, HALF, back, -HALF, HALF, front), -1f, 0f, 0f)
    }

    /** The picture, the frame's whole width and nine sixteenths of its height, just proud of its face. */
    private fun picture(pose: PoseStack.Pose, buffer: VertexConsumer, light: Int, colour: Int) {
        val z = -HALF_DEPTH - PROUD
        quad(pose, buffer, light, floatArrayOf(HALF, -PICTURE_HALF_HEIGHT, z, -HALF, -PICTURE_HALF_HEIGHT, z, -HALF, PICTURE_HALF_HEIGHT, z, HALF, PICTURE_HALF_HEIGHT, z), 0f, 0f, -1f, colour)
    }

    /** Four corners counter-clockwise as seen from outside, given the texture's bottom left, bottom right, top right, top left. */
    private fun quad(
        pose: PoseStack.Pose, buffer: VertexConsumer, light: Int, corners: FloatArray,
        normalX: Float, normalY: Float, normalZ: Float, colour: Int = WHITE,
    ) {
        for (corner in 0..<CORNERS) {
            buffer.addVertex(pose, corners[corner * 3], corners[corner * 3 + 1], corners[corner * 3 + 2])
                .setColor(colour)
                .setUv(CORNER_U[corner], CORNER_V[corner])
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, normalX, normalY, normalZ)
        }
    }

    companion object {
        private val FRAME: Identifier = Identifier.withDefaultNamespace("textures/block/dark_oak_planks.png")
        private val UNDEVELOPED: Identifier = Identifier.withDefaultNamespace("textures/block/black_concrete.png")

        private const val TURN_FROM_SOUTH = 180.0f
        private const val HALF = 0.5f
        private const val HALF_DEPTH = 1.0f / 32.0f
        private const val PICTURE_HALF_HEIGHT = 9.0f / 32.0f
        private const val PROUD = 0.001f
        private const val CORNERS = 4
        private const val WHITE = -1
        private val CORNER_U = floatArrayOf(0f, 1f, 1f, 0f)
        private val CORNER_V = floatArrayOf(1f, 1f, 0f, 0f)

        /** How close a picture is taken to be, so one touching the camera is not asked for at infinite size. */
        private const val NEAREST = 0.25

        /** A size is used once it is at least this much of the picture's width on screen. */
        private const val SHARPNESS = 0.75
    }
}
