package co.voik.agesandtheart.book

import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/**
 * A reader's book opened in [hand], or shut where [hand] is null: the book in that hand marked with when it was opened
 * ([AgeComponents.BOOK_OPENED_AT]), so everyone who sees them sees it open, and the mark gone from both hands as it shuts.
 * Those near see its panel too, as they would a book lying open ([BookBeingRead.InAnothersHand]).
 */
data class BookHeldOpen(val hand: InteractionHand?) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<BookHeldOpen> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<BookHeldOpen> = CustomPacketPayload.Type("book_held_open".location())

        private const val SHUT = 0

        val STREAM_CODEC: StreamCodec<ByteBuf, BookHeldOpen> = ByteBufCodecs.VAR_INT.map(
            { which -> BookHeldOpen(InteractionHand.entries.getOrNull(which - 1)) },
            { open -> open.hand?.let { it.ordinal + 1 } ?: SHUT },
        )

        /**
         * The book [holderId] has open in [hand], where [viewer] stands near enough to see its panel, as a book lying open
         * is seen; null where they are not near, or it is not open, or not ours.
         */
        fun openBookSeenBy(viewer: Player, holderId: Int, hand: InteractionHand): ItemStack? {
            val holder = viewer.level().getEntity(holderId) as? Player ?: return null
            val isNearEnough = viewer.position().closerThan(holder.position(), LecternBooks.PANEL_HELD_WITHIN_BLOCKS)
            if (holder === viewer || !isNearEnough) return null
            val book = holder.getItemInHand(hand)
            val isOpen = book.has(AgeComponents.BOOK_OPENED_AT)
            return book.takeIf { isOpen && LecternBooks.isOurs(it) }
        }

        fun handle(player: ServerPlayer, open: BookHeldOpen) {
            InteractionHand.entries.forEach { player.getItemInHand(it).remove(AgeComponents.BOOK_OPENED_AT) }
            val hand = open.hand ?: return
            val book = player.getItemInHand(hand)
            val isABookOfOurs = book.`is`(AgeContent.DESCRIPTIVE_BOOK) || book.`is`(AgeContent.LINKING_BOOK)
            if (isABookOfOurs) book.set(AgeComponents.BOOK_OPENED_AT, player.level().gameTime)
        }
    }
}
