package co.voik.agesandtheart.content

import co.voik.agesandtheart.age.AgePreset
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.Ages
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
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

    /**
     * The Age this book describes.
     *
     * Page order is word order, so the stored list *is* the sentence — it goes through the same grammar
     * and resolver a written `/age` command does, which is what makes a desk-bound book and a typed
     * command the same act.
     */
    private fun recipeFor(stack: ItemStack, server: MinecraftServer, ageId: Identifier): AgeRecipe {
        val words = stack.get(AgeContent.BOOK_WORDS).orEmpty()
        if (words.isEmpty()) return AgeRecipe.of(AgePreset.SPIRE, ageId)
        val vocabulary = Vocabulary.of(server)
        val spoken = words.map { it.path }
        val read = Grammar.read(vocabulary, spoken)
        if (read.isEmpty) return AgeRecipe.of(AgePreset.SPIRE, ageId)
        val seed = AgeRecipe.seedFor(ageId)
        return AgeRecipe.written(server, Resolver.resolve(vocabulary, read, seed), seed)
    }
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResult {
        val stack = player.getItemInHand(hand)
        // Run the real logic only on the server; the client just predicts the arm swing.
        if (level !is ServerLevel || player !is ServerPlayer) {
            return InteractionResult.SUCCESS
        }
        val server = level.server

        if (!Ages.isSupported()) {
            player.sendSystemMessage(Component.literal("Ages aren't supported on this loader yet."), true)
            return InteractionResult.FAIL
        }

        val existingAgeId = stack.get(AgeContent.AGE_ID)
        val ageId = existingAgeId ?: Ages.allocateId(server).also { stack.set(AgeContent.AGE_ID, it) }
        val isFirstWrite = existingAgeId == null

        // A book bound at the desk carries its sentence; one from creative or a command has none and
        // still gets the old fixed world, so `/give` keeps working as a debug route.
        val age = Ages.ensure(server, ageId, recipeFor(stack, server, ageId))
        if (age == null) {
            player.sendSystemMessage(Component.literal("Could not open the Age."), true)
            return InteractionResult.FAIL
        }

        Ages.teleport(player, age)
        val verb = if (isFirstWrite) "Wrote and entered" else "Linked to"
        val called = stack.get(AgeContent.BOOK_TITLE) ?: ageId.path
        player.sendSystemMessage(Component.literal("$verb Age '$called'"), true)
        return InteractionResult.SUCCESS
    }
}
