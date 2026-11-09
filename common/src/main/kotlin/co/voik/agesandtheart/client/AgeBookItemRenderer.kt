package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookBinding
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.serialization.MapCodec
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.special.SpecialModelRenderer
import net.minecraft.client.resources.model.sprite.SpriteGetter
import net.minecraft.world.item.ItemStack
import org.joml.Vector3fc
import java.util.function.Consumer

/**
 * A descriptive or linking book as an item, drawn as the shut book itself so its banner and name show in a slot,
 * a hand or a frame. Its front cover faces +Z, the way a flat item's sprite does, so the item model's display
 * transforms are a flat item's.
 */
class AgeBookItemRenderer(sprites: SpriteGetter) : SpecialModelRenderer<BookLook> {

    private val rendering = AgeBookRendering(sprites)

    override fun extractArgument(stack: ItemStack): BookLook = BookLook.of(stack)

    override fun submit(
        look: BookLook?,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        lightCoords: Int,
        overlayCoords: Int,
        hasFoil: Boolean,
        outlineColor: Int,
    ) {
        look ?: return
        poseStack.pushPose()
        centreInTheItem(look.binding, poseStack)
        rendering.submit(look, SHUT, poseStack, collector, lightCoords, overlayCoords, outlineColor)
        poseStack.popPose()
    }

    override fun getExtents(output: Consumer<Vector3fc>) {
        val poseStack = PoseStack()
        val largest = rendering.modelFor(BookBinding.THICK)
        centreInTheItem(largest.binding, poseStack)
        largest.setupAnim(SHUT)
        largest.root().getExtentsForGui(poseStack, output)
    }

    /** The shut book, spine to fore-edge from 0 and centred on the other two, centred in the item's cube and enlarged. */
    private fun centreInTheItem(binding: BookBinding, poseStack: PoseStack) {
        poseStack.translate(HALF, HALF, HALF)
        poseStack.scale(ENLARGED, ENLARGED, ENLARGED)
        poseStack.translate(-AgeBookModel.shutLength(binding) / 2 / UNITS_PER_BLOCK, 0.0f, 0.0f)
    }

    class Unbaked : SpecialModelRenderer.Unbaked<BookLook> {
        override fun bake(context: SpecialModelRenderer.BakingContext): SpecialModelRenderer<BookLook> =
            AgeBookItemRenderer(context.sprites())

        override fun type(): MapCodec<Unbaked> = MAP_CODEC

        companion object {
            val MAP_CODEC: MapCodec<Unbaked> = MapCodec.unit(Unbaked())
        }
    }

    private companion object {
        val SHUT = AgeBookModel.State(0.0f)
        const val HALF = 0.5f

        /** Eight pixels tall becomes fourteen, near filling a slot as a flat item does. */
        const val ENLARGED = 1.75f
        const val UNITS_PER_BLOCK = 16.0f
    }
}
