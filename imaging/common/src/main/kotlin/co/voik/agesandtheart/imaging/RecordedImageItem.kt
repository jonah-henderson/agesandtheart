package co.voik.agesandtheart.imaging

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.TooltipDisplay
import java.util.function.Consumer

/** A picture taken: named for where it was taken, as a linking book is for where it goes, and renamed at an anvil. */
class RecordedImageItem(properties: Properties) : Item(properties) {

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
        val picture = stack.get(ImagingComponents.PICTURE) ?: return
        // The place as well as the name, since the name may be one it was given.
        builder.accept(Component.literal(picture.place).withStyle(ChatFormatting.GRAY))
        builder.accept(
            Component.literal("${picture.position.x}, ${picture.position.y}, ${picture.position.z}")
                .withStyle(ChatFormatting.DARK_GRAY),
        )
    }
}
