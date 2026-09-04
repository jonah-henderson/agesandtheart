package co.voik.agesandtheart.book

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.Ages
import co.voik.agesandtheart.content.AgeContent
import io.netty.buffer.ByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack

/**
 * "Take me there" — the click on a book's linking panel.
 *
 * Carries only which hand held the book, because everything else is on the server already and a payload
 * naming an Age would be a request to go anywhere at all.
 */
data class LinkRequest(val hand: InteractionHand) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<LinkRequest> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<LinkRequest> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "link"),
        )

        val STREAM_CODEC: StreamCodec<ByteBuf, LinkRequest> =
            ByteBufCodecs.idMapper({ LinkRequest(InteractionHand.entries[it]) }, { it.hand.ordinal })
    }
}

/**
 * Linking: the act the whole mod is built around.
 *
 * **The book does not travel.** It is spent to the spot you were standing on, which is what makes going
 * somewhere a decision rather than a door — link away with no way back and you are stranded until you
 * find a star fissure (design §7.8), and that is intended.
 */
object Linking {

    fun handle(player: ServerPlayer, request: LinkRequest) {
        val level = player.level() as? ServerLevel ?: return
        val stack = player.getItemInHand(request.hand)
        if (stack.item !== AgeContent.DESCRIPTIVE_BOOK) return
        link(player, level, stack, request.hand)
    }

    fun link(player: ServerPlayer, level: ServerLevel, stack: ItemStack, hand: InteractionHand) {
        val server = level.server
        if (!Ages.isSupported()) {
            return complain(player, "unsupported")
        }
        val existing = stack.get(AgeContent.AGE_ID)
        // **A book is a door to somewhere else.** Carry one into the Age it describes — through a linking
        // book, say — and using it would spend the book on the room you are already standing in, which is
        // the same claim `LinkingBookItem` refuses and for the same reason.
        if (existing == level.dimension().identifier()) return complain(player, "same_world")
        // `BookAge` and not a second copy of this: the linking panel opens the same Age from the same
        // stack, and a book that previewed one world and sent you to another would be the worst fault
        // this could have.
        val age = BookAge.of(server, stack) ?: return complain(player, "failed")
        val ageId = age.dimension().identifier()

        // The book is left where the player stood, before the teleport moves them — otherwise it would
        // come to rest in the Age they are going to.
        val leftAt = player.position()
        val left = stack.copy()
        player.setItemInHand(hand, ItemStack.EMPTY)

        Ages.teleport(player, age)
        BookEntity.leaveBehind(level, leftAt, left)

        val called = left.get(AgeContent.BOOK_TITLE) ?: ageId.path
        player.sendSystemMessage(Component.translatable("book.agesandtheart.linked", called), true)
        Constants.LOG.debug("{} linked to '{}', book left at {}", player.gameProfile.name, called, leftAt)
    }

    /** The same chain `/age compose` uses, so a written book and a typed command are one act. */
    private fun recipeFor(
        stack: ItemStack,
        server: net.minecraft.server.MinecraftServer,
        ageId: Identifier,
    ): AgeRecipe = DescriptiveBookRecipe.of(stack, server, ageId)

    private fun complain(player: ServerPlayer, reason: String) {
        player.sendSystemMessage(Component.translatable("book.agesandtheart.$reason"), true)
    }
}
