package co.voik.agesandtheart.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.serialization.MapCodec
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.special.SpecialModelRenderer
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemContainerContents
import org.joml.Vector3f
import org.joml.Vector3fc
import java.util.function.Consumer

/**
 * What a toolbox in hand or in a slot holds, standing in it as in a placed one ([ToolboxContents]), over the box's own
 * model in the same item model. The contents are the argument, so a slot's picture is drawn again only when they change.
 */
class ToolboxItemRenderer : SpecialModelRenderer<ItemContainerContents> {

    override fun extractArgument(stack: ItemStack): ItemContainerContents? = stack.get(DataComponents.CONTAINER)

    override fun submit(
        contents: ItemContainerContents?,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        lightCoords: Int,
        overlayCoords: Int,
        hasFoil: Boolean,
        outlineColor: Int,
    ) {
        contents ?: return
        val minecraft = Minecraft.getInstance()
        val shown = ToolboxContents.shownOf(contents.nonEmptyItemCopyStream().toList())
        for ((index, stack) in shown.withIndex()) {
            // A state apiece: the collector keeps what is submitted until the frame is drawn.
            val itemState = ItemStackRenderState()
            minecraft.itemModelResolver.updateForTopItem(itemState, stack, ItemDisplayContext.FIXED, minecraft.level, null, index)
            ToolboxContents.submitStanding(index, itemState, ToolboxContents.Stance.of(stack), poseStack, collector, lightCoords, outlineColor)
        }
    }

    /** The box and what leans out of it, in the model's pixels. */
    override fun getExtents(output: Consumer<Vector3fc>) {
        for (x in listOf(LOW_ACROSS, HIGH_ACROSS)) {
            for (y in listOf(FLOOR, TOP)) {
                for (z in listOf(LOW_ALONG, HIGH_ALONG)) output.accept(Vector3f(x, y, z).div(UNITS_PER_BLOCK))
            }
        }
    }

    class Unbaked : SpecialModelRenderer.Unbaked<ItemContainerContents> {
        override fun bake(context: SpecialModelRenderer.BakingContext): SpecialModelRenderer<ItemContainerContents> =
            ToolboxItemRenderer()

        override fun type(): MapCodec<Unbaked> = MAP_CODEC

        companion object {
            val MAP_CODEC: MapCodec<Unbaked> = MapCodec.unit(Unbaked())
        }
    }

    private companion object {
        const val LOW_ACROSS = 1.0f
        const val HIGH_ACROSS = 15.0f
        const val FLOOR = 0.0f
        const val TOP = 12.0f
        const val LOW_ALONG = 2.0f
        const val HIGH_ALONG = 14.0f
        const val UNITS_PER_BLOCK = 16.0f
    }
}
