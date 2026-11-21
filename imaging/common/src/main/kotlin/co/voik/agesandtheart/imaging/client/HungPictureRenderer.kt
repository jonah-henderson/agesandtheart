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
import net.minecraft.client.renderer.texture.TextureAtlasSprite
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
    var frame: TextureAtlasSprite? = null
}

/**
 * A hung picture, at whichever size its width on screen calls for ([PictureTextures]): across the face of its frame, a
 * block square a sixteenth deep in the frame's planks, or on a plate of its own height where it is unframed.
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
        state.frame = entity.frame?.let(FrameWood::spriteOf)
    }

    override fun submit(state: HungPictureRenderState, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        poseStack.pushPose()
        poseStack.rotateDegrees(Axis.YP, TURN_FROM_SOUTH - state.facing.toYRot())
        val light = state.lightCoords
        val frame = state.frame
        if (frame != null) {
            collector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(frame.atlasLocation())) { pose, buffer ->
                box(pose, buffer, light, HALF, Corners.of(frame))
            }
        } else {
            collector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(UNDEVELOPED)) { pose, buffer ->
                box(pose, buffer, light, PICTURE_HALF_HEIGHT, Corners.WHOLE)
            }
        }
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

    /** Where a texture's corners fall: the whole of one, or a sprite's patch of its atlas. */
    private data class Corners(val u0: Float, val u1: Float, val v0: Float, val v1: Float) {
        companion object {
            val WHOLE = Corners(0f, 1f, 0f, 1f)

            fun of(sprite: TextureAtlasSprite) = Corners(sprite.u0, sprite.u1, sprite.v0, sprite.v1)
        }
    }

    /** What the picture sits on as a box a block wide: the face behind it, the back against the wall, and the four edges. */
    private fun box(pose: PoseStack.Pose, buffer: VertexConsumer, light: Int, halfHeight: Float, corners: Corners) {
        val (front, back) = -HALF_DEPTH to HALF_DEPTH
        val (top, bottom) = halfHeight to -halfHeight
        quad(pose, buffer, light, floatArrayOf(HALF, bottom, front, -HALF, bottom, front, -HALF, top, front, HALF, top, front), 0f, 0f, -1f, corners = corners)
        quad(pose, buffer, light, floatArrayOf(-HALF, bottom, back, HALF, bottom, back, HALF, top, back, -HALF, top, back), 0f, 0f, 1f, corners = corners)
        quad(pose, buffer, light, floatArrayOf(HALF, top, front, -HALF, top, front, -HALF, top, back, HALF, top, back), 0f, 1f, 0f, corners = corners)
        quad(pose, buffer, light, floatArrayOf(-HALF, bottom, front, HALF, bottom, front, HALF, bottom, back, -HALF, bottom, back), 0f, -1f, 0f, corners = corners)
        quad(pose, buffer, light, floatArrayOf(HALF, bottom, back, HALF, bottom, front, HALF, top, front, HALF, top, back), 1f, 0f, 0f, corners = corners)
        quad(pose, buffer, light, floatArrayOf(-HALF, bottom, front, -HALF, bottom, back, -HALF, top, back, -HALF, top, front), -1f, 0f, 0f, corners = corners)
    }

    /** The picture, the frame's whole width and nine sixteenths of its height, just proud of its face. */
    private fun picture(pose: PoseStack.Pose, buffer: VertexConsumer, light: Int, colour: Int) {
        val z = -HALF_DEPTH - PROUD
        quad(pose, buffer, light, floatArrayOf(HALF, -PICTURE_HALF_HEIGHT, z, -HALF, -PICTURE_HALF_HEIGHT, z, -HALF, PICTURE_HALF_HEIGHT, z, HALF, PICTURE_HALF_HEIGHT, z), 0f, 0f, -1f, colour)
    }

    /** Four corners counter-clockwise as seen from outside, given the texture's bottom left, bottom right, top right, top left. */
    private fun quad(
        pose: PoseStack.Pose, buffer: VertexConsumer, light: Int, positions: FloatArray,
        normalX: Float, normalY: Float, normalZ: Float, colour: Int = WHITE, corners: Corners = Corners.WHOLE,
    ) {
        val us = floatArrayOf(corners.u0, corners.u1, corners.u1, corners.u0)
        val vs = floatArrayOf(corners.v1, corners.v1, corners.v0, corners.v0)
        for (corner in 0..<CORNERS) {
            buffer.addVertex(pose, positions[corner * 3], positions[corner * 3 + 1], positions[corner * 3 + 2])
                .setColor(colour)
                .setUv(us[corner], vs[corner])
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, normalX, normalY, normalZ)
        }
    }

    companion object {
        private val UNDEVELOPED: Identifier = Identifier.withDefaultNamespace("textures/block/black_concrete.png")

        private const val TURN_FROM_SOUTH = 180.0f
        private const val HALF = 0.5f
        private const val HALF_DEPTH = 1.0f / 32.0f
        private const val PICTURE_HALF_HEIGHT = (HungPicture.PICTURE_HEIGHT / 2).toFloat()
        private const val PROUD = 0.001f
        private const val CORNERS = 4
        private const val WHITE = -1

        /** How close a picture is taken to be, so one touching the camera is not asked for at infinite size. */
        private const val NEAREST = 0.25

        /** A size is used once it is at least this much of the picture's width on screen. */
        private const val SHARPNESS = 0.75
    }
}
