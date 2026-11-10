package co.voik.agesandtheart.client

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack

/**
 * The first few things in a toolbox, stood upright in it with their heads leaning out over its sides, two either side
 * of the handle so none reaches its bar; armour stands as it is drawn, nearer the middle. Laid out in the model's own
 * space, facing north as it does unturned, for the placed box ([ToolboxRenderer]) and the item ([ToolboxItemRenderer]).
 */
object ToolboxContents {

    /** As many as stand in the box without crowding: two each side of the handle. */
    const val SHOWN = 4

    /** What of [held] is shown: the first things in it, in its order. */
    fun shownOf(held: List<ItemStack>): List<ItemStack> = held.filterNot { it.isEmpty }.take(SHOWN)

    /** [itemState], drawn as the [index]th thing shown, standing as [stance] says. */
    fun submitStanding(
        index: Int,
        itemState: ItemStackRenderState,
        stance: Stance,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        lightCoords: Int,
        outlineColor: Int,
    ) {
        val place = PLACES[index]
        val across = MIDDLE_ACROSS + place.side * stance.outFromTheMiddle
        poseStack.pushPose()
        poseStack.translate(across / UNITS_PER_BLOCK, stance.standingHeight / UNITS_PER_BLOCK, place.along / UNITS_PER_BLOCK)
        poseStack.rotate(Axis.ZP.rotationDegrees(stance.upright - place.side * stance.lean))
        poseStack.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE)
        itemState.submit(poseStack, collector, lightCoords, OverlayTexture.NO_OVERLAY, outlineColor)
        poseStack.popPose()
    }

    /** Where a thing stands: on one [side] of the handle, -1 or 1 across the box, and [along] it from the end facing you, in the model's pixels. */
    private data class Place(val side: Int, val along: Double)

    /**
     * How a thing stands in the box, by what its sprite is: a tool's runs corner to corner and is stood on end, leaning
     * out over the side; armour is drawn upright and stands nearer the middle, where it is too wide to lean far, and
     * boots, drawn low in their sprite, stand higher to show over the side. A shield is drawn upright and face on, and
     * stands as armour does, leaning a little less. Each is [outFromTheMiddle] pixels from the
     * handle, leans [lean] degrees outward, and has its middle [standingHeight] pixels up.
     */
    enum class Stance(val upright: Float, val outFromTheMiddle: Double, val lean: Float, val standingHeight: Double) {
        TOOL(upright = -45.0f, outFromTheMiddle = 2.0, lean = 20.0f, standingHeight = 5.5),
        ARMOUR(upright = 0.0f, outFromTheMiddle = 0.75, lean = 10.0f, standingHeight = 5.5),
        BOOTS(upright = 0.0f, outFromTheMiddle = 0.75, lean = 0.0f, standingHeight = 7.0),
        SHIELD(upright = 0.0f, outFromTheMiddle = 0.75, lean = 6.0f, standingHeight = 5.5),
        ;

        companion object {
            fun of(stack: ItemStack): Stance {
                val slot = stack.get(DataComponents.EQUIPPABLE)?.slot()
                return when {
                    stack.has(DataComponents.BLOCKS_ATTACKS) -> SHIELD
                    slot == EquipmentSlot.FEET -> BOOTS
                    slot != null && slot.isArmor -> ARMOUR
                    else -> TOOL
                }
            }
        }
    }

    private val PLACES = listOf(
        Place(side = TOWARD_WEST, along = 4.5),
        Place(side = TOWARD_EAST, along = 6.5),
        Place(side = TOWARD_WEST, along = 9.5),
        Place(side = TOWARD_EAST, along = 11.5),
    )

    /** The handle's line, down the middle of the box. */
    private const val MIDDLE_ACROSS = 8.0

    /** Across the box: west is toward smaller x, where a positive turn about z takes a thing's head, so a lean out is turned against its side. */
    private const val TOWARD_WEST = -1
    private const val TOWARD_EAST = 1

    /** About eight pixels from foot to head, which stands it on the floor of the box and its head under the bar. */
    private const val ITEM_SCALE = 0.4f

    private const val UNITS_PER_BLOCK = 16.0
}
