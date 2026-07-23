package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.AgeManager
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

/**
 * A Mystcraft-style Descriptive Book. On use it authors (or re-enters) the Age it's bound to
 * and teleports the holder there.
 *
 * The bound Age is stored in the stack's [AgeContent.AGE_ID] component and assigned lazily on
 * first use from a persistent counter — so every fresh book writes a distinct new Age, while
 * the same book always links back to its own.
 */
class DescriptiveBookItem(properties: Properties) : Item(properties) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        // Run the real logic only on the server; the client just predicts the arm swing.
        if (level !is ServerLevel || player !is ServerPlayer) {
            return InteractionResultHolder.success(stack)
        }
        val server = level.server

        if (!AgeManager.isSupported()) {
            player.displayClientMessage(Component.literal("Ages aren't supported on this loader yet."), true)
            return InteractionResultHolder.fail(stack)
        }

        var id = stack.get(AgeContent.AGE_ID)
        val firstWrite = id == null
        if (id == null) {
            id = AgeManager.allocateAgeId(server)
            stack.set(AgeContent.AGE_ID, id)
        }

        val age = AgeManager.ensureAge(server, id)
        if (age == null) {
            player.displayClientMessage(Component.literal("Could not open the Age."), true)
            return InteractionResultHolder.fail(stack)
        }

        AgeManager.teleport(player, age)
        val verb = if (firstWrite) "Wrote and entered" else "Linked to"
        player.displayClientMessage(Component.literal("$verb Age '${id.path}'"), true)
        return InteractionResultHolder.success(stack)
    }
}
