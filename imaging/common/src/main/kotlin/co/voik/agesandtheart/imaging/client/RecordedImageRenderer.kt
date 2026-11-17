package co.voik.agesandtheart.imaging.client

import co.voik.agesandtheart.imaging.ImagingComponents
import co.voik.agesandtheart.imaging.PictureSizes
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.serialization.MapCodec
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.special.SpecialModelRenderer
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import net.minecraft.world.item.ItemStack
import org.joml.Vector3f
import org.joml.Vector3fc
import java.util.function.Consumer

/**
 * A recorded image as an item: its own picture across a square frame, front and back, as it hangs on a wall. Black
 * until the picture has come, as a hung one is, and developing from black as it arrives.
 */
class RecordedImageRenderer : SpecialModelRenderer<RecordedImageRenderer.Shown> {

    /**
     * What is drawn, which is also what the inventory's item atlas keeps a drawing by: the texture and how far it has
     * developed, so a picture arriving or developing is drawn again there rather than left black.
     */
    data class Shown(val texture: Identifier, val developedSteps: Int)

    override fun extractArgument(stack: ItemStack): Shown {
        val id = stack.get(ImagingComponents.PICTURE)?.id ?: return Shown(UNDEVELOPED, DEVELOPING_STEPS)
        val texture = PictureTextures.textureFor(id, PictureSizes.Size.MIDDLE) ?: return Shown(UNDEVELOPED, DEVELOPING_STEPS)
        return Shown(texture, (PictureTextures.development(id) * DEVELOPING_STEPS).toInt())
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
        collector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(FRAME)) { pose, buffer ->
            face(pose, buffer, lightCoords, overlayCoords, 0.0f, 1.0f, FRONT - SHY)
            face(pose, buffer, lightCoords, overlayCoords, 0.0f, 1.0f, BACK + SHY, facingAway = true)
        }
        collector.submitCustomGeometry(poseStack, RenderTypes.entitySolid(texture)) { pose, buffer ->
            face(pose, buffer, lightCoords, overlayCoords, PICTURE_LOW, PICTURE_HIGH, FRONT, colour = developed)
            face(pose, buffer, lightCoords, overlayCoords, PICTURE_LOW, PICTURE_HIGH, BACK, facingAway = true, colour = developed)
        }
    }

    /** A face across the item's whole width from [low] to [high] up it, at [z], facing the viewer or away. */
    private fun face(
        pose: PoseStack.Pose, buffer: VertexConsumer, light: Int, overlay: Int,
        low: Float, high: Float, z: Float, facingAway: Boolean = false, colour: Int = WHITE,
    ) {
        val corners = if (facingAway) BACK_CORNERS else FRONT_CORNERS
        val normalZ = if (facingAway) -1.0f else 1.0f
        for ((x, up, u) in corners) {
            buffer.addVertex(pose, x, if (up) high else low, z)
                .setColor(colour)
                .setUv(u, if (up) 0.0f else 1.0f)
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
        val FRAME: Identifier = Identifier.withDefaultNamespace("textures/block/dark_oak_planks.png")
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
