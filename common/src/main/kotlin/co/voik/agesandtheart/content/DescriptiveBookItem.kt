package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.AgePreset
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.Ages
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

        if (!Ages.isSupported()) {
            player.displayClientMessage(Component.literal("Ages aren't supported on this loader yet."), true)
            return InteractionResultHolder.fail(stack)
        }

        val existingAgeId = stack.get(AgeContent.AGE_ID)
        val ageId = existingAgeId ?: Ages.allocateId(server).also { stack.set(AgeContent.AGE_ID, it) }
        val isFirstWrite = existingAgeId == null

        // Every book writes the same world for now. This is where the words a player wrote will be
        // resolved into a recipe, and it is the whole point of the Art: see notes/the-art-design.md.
        val age = Ages.ensure(server, ageId, AgeRecipe.forPreset(AgePreset.SPIRE, ageId))
        if (age == null) {
            player.displayClientMessage(Component.literal("Could not open the Age."), true)
            return InteractionResultHolder.fail(stack)
        }

        Ages.teleport(player, age)
        val verb = if (isFirstWrite) "Wrote and entered" else "Linked to"
        player.displayClientMessage(Component.literal("$verb Age '${ageId.path}'"), true)
        return InteractionResultHolder.success(stack)
    }
}
