package co.voik.agesandtheart.imaging

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Item
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import java.util.function.Consumer

/** A picture taken: named for where it was taken, as a linking book is for where it goes, and renamed at an anvil. */
class RecordedImageItem(properties: Properties) : Item(properties) {

    /** Hung against the side of a block, as a painting is, where it fits and has a wall behind it. */
    override fun useOn(context: UseOnContext): InteractionResult {
        val face = context.clickedFace
        val player = context.player
        val stack = context.itemInHand
        val at = context.clickedPos.relative(face)
        val cannotHangThere = face.axis.isVertical || (player != null && !player.mayUseItemAt(at, face, stack))
        if (cannotHangThere) return InteractionResult.FAIL
        val level = context.level
        val picture = HungPicture(level, at, face, stack)
        if (!picture.survives()) return InteractionResult.FAIL
        if (level is ServerLevel) {
            picture.playPlacementSound()
            level.gameEvent(player, GameEvent.ENTITY_PLACE, picture.position())
            level.addFreshEntity(picture)
        }
        stack.shrink(1)
        return InteractionResult.SUCCESS
    }

    override fun getName(itemStack: ItemStack): Component {
        val picture = itemStack.get(ImagingComponents.PICTURE) ?: return super.getName(itemStack)
        return Component.translatable("item.agesandtheart_imaging.recorded_image.of", picture.place)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        builder: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        stack.framedIn?.let { planks ->
            builder.accept(Component.translatable("item.agesandtheart_imaging.recorded_image.framed_in", planks.name).withStyle(ChatFormatting.GRAY))
        }
        val picture = stack.get(ImagingComponents.PICTURE) ?: return
        // The place as well as the name, since the name may be one it was given.
        builder.accept(Component.literal(picture.place).withStyle(ChatFormatting.GRAY))
        builder.accept(
            Component.literal("${picture.position.x}, ${picture.position.y}, ${picture.position.z}")
                .withStyle(ChatFormatting.DARK_GRAY),
        )
    }

    companion object {
        /** A picture just taken, as it comes from the recorder: the picture alone, to be framed at a crafting table. */
        fun taken(picture: Picture): ItemStack = ItemStack(ImagingContent.RECORDED_IMAGE).apply {
            set(ImagingComponents.PICTURE, picture)
            set(ImagingComponents.FRAME, PictureFrame.UNFRAMED)
        }
    }
}
