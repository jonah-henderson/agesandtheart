package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.imaging.ImagingComponents
import co.voik.agesandtheart.imaging.PictureSizes
import co.voik.agesandtheart.imaging.framedIn
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.serialization.MapCodec
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.special.SpecialModelRenderer
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import org.joml.Vector3f
import org.joml.Vector3fc
import java.util.function.Consumer

/**
 * A recorded image as an item: its own picture, front and back, across a square frame of its planks where it is framed,
 * as it hangs on a wall. Black until the picture has come, as a hung one is, and developing from black as it arrives.
 */
class RecordedImageRenderer : SpecialModelRenderer<RecordedImageRenderer.Shown> {

    /**
     * What is drawn, which is also what the inventory's item atlas keeps a drawing by: the texture and how far it has
     * developed, so a picture arriving or developing is drawn again there rather than left black, and its frame's planks.
     */
    data class Shown(val texture: Identifier, val developedSteps: Int, val frame: Block?)

    override fun extractArgument(stack: ItemStack): Shown {
        val frame = stack.framedIn
        val id = stack.get(ImagingComponents.PICTURE)?.id ?: return Shown(UNDEVELOPED, DEVELOPING_STEPS, frame)
        val texture = PictureTextures.textureFor(id, PictureSizes.Size.MIDDLE) ?: return Shown(UNDEVELOPED, DEVELOPING_STEPS, frame)
        return Shown(texture, (PictureTextures.development(id) * DEVELOPING_STEPS).toInt(), frame)
    }

    override fun submit(
        shown: Shown?,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        lightCoords: Int,
        overlayCoords: Int,
        hasFoil: Boolean,
        outlineColor: Int,
    ) {
        val texture = shown?.texture ?: UNDEVELOPED
        val developed = ARGB.scaleRGB(WHITE, (shown?.developedSteps ?: DEVELOPING_STEPS).toFloat() / DEVELOPING_STEPS)
        shown?.frame?.let(FrameWood::spriteOf)?.let { wood ->
            collector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(wood.atlasLocation())) { pose, buffer ->
                face(pose, buffer, lightCoords, overlayCoords, 0.0f, 1.0f, FRONT - SHY, sprite = wood)
                face(pose, buffer, lightCoords, overlayCoords, 0.0f, 1.0f, BACK + SHY, facingAway = true, sprite = wood)
            }
        }
        collector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(texture)) { pose, buffer ->
            face(pose, buffer, lightCoords, overlayCoords, PICTURE_LOW, PICTURE_HIGH, FRONT, colour = developed)
            face(pose, buffer, lightCoords, overlayCoords, PICTURE_LOW, PICTURE_HIGH, BACK, facingAway = true, colour = developed)
        }
    }

    /** A face across the item's whole width from [low] to [high] up it, at [z], facing the viewer or away. */
    private fun face(
        pose: PoseStack.Pose, buffer: VertexConsumer, light: Int, overlay: Int,
        low: Float, high: Float, z: Float, facingAway: Boolean = false, colour: Int = WHITE, sprite: TextureAtlasSprite? = null,
    ) {
        val corners = if (facingAway) BACK_CORNERS else FRONT_CORNERS
        val normalZ = if (facingAway) -1.0f else 1.0f
        fun onTheTexture(u: Float, v: Float) = if (sprite == null) u to v else sprite.getU(u) to sprite.getV(v)
        for ((x, up, u) in corners) {
            val (textureU, textureV) = onTheTexture(u, if (up) 0.0f else 1.0f)
            buffer.addVertex(pose, x, if (up) high else low, z)
                .setColor(colour)
                .setUv(textureU, textureV)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, 0.0f, 0.0f, normalZ)
        }
    }

    override fun getExtents(output: Consumer<Vector3fc>) {
        for (x in listOf(0.0f, 1.0f)) for (y in listOf(0.0f, 1.0f)) for (z in listOf(BACK, FRONT)) output.accept(Vector3f(x, y, z))
    }

    class Unbaked : SpecialModelRenderer.Unbaked<Shown> {
        override fun bake(context: SpecialModelRenderer.BakingContext): SpecialModelRenderer<Shown> = RecordedImageRenderer()

        override fun type(): MapCodec<Unbaked> = MAP_CODEC

        companion object {
            val MAP_CODEC: MapCodec<Unbaked> = MapCodec.unit(Unbaked())
        }
    }

    private companion object {
        val UNDEVELOPED: Identifier = Identifier.withDefaultNamespace("textures/block/black_concrete.png")

        /** Where a flat item's two faces stand, a sixteenth apart about its middle. */
        const val FRONT = 8.5f / 16.0f
        const val BACK = 7.5f / 16.0f
        const val SHY = 0.001f
        const val PICTURE_LOW = 3.5f / 16.0f
        const val PICTURE_HIGH = 12.5f / 16.0f
        const val WHITE = -1

        /** How finely developing is drawn in an inventory, each step a fresh drawing in its item atlas. */
        const val DEVELOPING_STEPS = 32

        /** x, whether at the top, and u, wound so the face shows on its own side; the back mirrored so it reads true. */
        val FRONT_CORNERS = listOf(Triple(0.0f, false, 0.0f), Triple(1.0f, false, 1.0f), Triple(1.0f, true, 1.0f), Triple(0.0f, true, 0.0f))
        val BACK_CORNERS = listOf(Triple(1.0f, false, 0.0f), Triple(0.0f, false, 1.0f), Triple(0.0f, true, 1.0f), Triple(1.0f, true, 0.0f))
    }
}
