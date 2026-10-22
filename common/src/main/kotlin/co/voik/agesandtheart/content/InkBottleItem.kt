package co.voik.agesandtheart.content

import net.minecraft.util.Prediction
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.InkSacItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignTextSlot

/**
 * A bottle of ink, which does to a sign what an ink sac does — takes its glow off — and leaves the empty bottle,
 * since the sign spends the stack it was used with.
 */
class InkBottleItem(properties: Properties) : InkSacItem(properties) {

    override fun tryApplyToSign(level: Level, sign: SignBlockEntity, slot: SignTextSlot, stack: ItemStack, player: Player): Boolean {
        if (!super.tryApplyToSign(level, sign, slot, stack, player)) return false
        if (!player.hasInfiniteMaterials()) player.inventory.placeItemBackInInventory(ItemStack(Items.GLASS_BOTTLE), Prediction.SERVER_ONLY)
        return true
    }
}
