package co.voik.agesandtheart.content

import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemContainerContents
import net.minecraft.world.level.Level

/**
 * Holds pages, so a collection of them costs one inventory slot rather than one per word.
 *
 * The pages stay **pages** rather than being absorbed into a list of words: a found page is still what a
 * book gets written from, so collating must not destroy it. Use gathers, sneak-use tips out.
 */
class NotebookItem(properties: Properties) : Item(properties) {

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        if (level !is ServerLevel || player !is ServerPlayer) return InteractionResult.SUCCESS
        val moved = if (player.isShiftKeyDown) tipOut(stack, player) else gather(stack, player)
        if (moved == 0) {
            player.sendSystemMessage(Component.translatable("item.agesandtheart.notebook.nothing"), true)
            return InteractionResult.FAIL
        }
        player.sendSystemMessage(
            Component.translatable(
                if (player.isShiftKeyDown) "item.agesandtheart.notebook.tipped_out"
                else "item.agesandtheart.notebook.gathered",
                moved,
            ),
            true,
        )
        return InteractionResult.SUCCESS
    }

    /** Moves every loose page in the player's inventory into [notebook]. @return how many. */
    private fun gather(notebook: ItemStack, player: Player): Int {
        val held = contentsOf(notebook).toMutableList()
        var moved = 0
        val inventory = player.inventory
        for (slot in 0 until inventory.containerSize) {
            if (held.size >= CAPACITY) break
            val candidate = inventory.getItem(slot)
            if (candidate.item !== AgeContent.PAGE) continue
            held += candidate.copy()
            moved += candidate.count
            inventory.setItem(slot, ItemStack.EMPTY)
        }
        notebook.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(held))
        return moved
    }

    /** Gives the pages back, dropping what will not fit. @return how many. */
    private fun tipOut(notebook: ItemStack, player: Player): Int {
        val held = contentsOf(notebook)
        if (held.isEmpty()) return 0
        var moved = 0
        for (page in held) {
            moved += page.count
            if (!player.inventory.add(page)) player.drop(page, false)
        }
        notebook.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
        return moved
    }

    private fun contentsOf(notebook: ItemStack): List<ItemStack> =
        notebook.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
            .nonEmptyItemCopyStream().toList()

    private companion object {
        /** What `ItemContainerContents` itself will hold. */
        const val CAPACITY = 256
    }
}
