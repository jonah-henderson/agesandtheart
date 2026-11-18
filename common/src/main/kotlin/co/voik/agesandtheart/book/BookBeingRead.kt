package co.voik.agesandtheart.book

import io.netty.buffer.ByteBuf
import io.netty.handler.codec.DecoderException
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.InteractionHand

/**
 * Where a book being read is: in a hand, lying open on a lectern, fallen open where someone linked from it, or open in
 * another player's hand —
 * which decides whose panel it is. Or a book not yet bound, whose sentence is laid out at a desk and looked at
 * through a crystal viewer.
 *
 * A hand's panel belongs to its screen and closes with it, and so does a viewer's. A lectern's belongs to
 * the lectern, and everybody standing at it sees the same one (design §7.8.2); a fallen book's likewise, and a book
 * open in another's hand, which everybody near but its reader sees as one lying open.
 */
sealed interface BookBeingRead {

    data class InHand(val hand: InteractionHand) : BookBeingRead

    data class OnALectern(val pos: BlockPos) : BookBeingRead

    /** A [BookEntity], by the id the server and its clients share for it. */
    data class OnTheGround(val entityId: Int) : BookBeingRead

    /** A book another player is reading in [hand], by the id the server and its clients share for them ([BookHeldOpen]). */
    data class InAnothersHand(val holderId: Int, val hand: InteractionHand) : BookBeingRead

    /** A book lying open at [place] on a stand of ours at [pos] ([BookStand]): a wheel, a book lift or a finder. */
    data class OnAStand(val pos: BlockPos, val place: Int) : BookBeingRead

    /** The sentence laid out at a desk, through the crystal viewer whose menu the writer has open. */
    data object AtACrystalViewer : BookBeingRead

    companion object {
        private const val IN_HAND = 0
        private const val ON_A_LECTERN = 1
        private const val AT_A_CRYSTAL_VIEWER = 2
        private const val ON_THE_GROUND = 3
        private const val IN_ANOTHERS_HAND = 4
        private const val ON_A_STAND = 5

        val STREAM_CODEC: StreamCodec<ByteBuf, BookBeingRead> = StreamCodec.of(
            { buffer, book -> encode(buffer, book) },
            { buffer -> decode(buffer) },
        )

        private fun encode(buffer: ByteBuf, book: BookBeingRead) {
            when (book) {
                is InHand -> {
                    ByteBufCodecs.VAR_INT.encode(buffer, IN_HAND)
                    ByteBufCodecs.VAR_INT.encode(buffer, book.hand.ordinal)
                }
                is OnALectern -> {
                    ByteBufCodecs.VAR_INT.encode(buffer, ON_A_LECTERN)
                    BlockPos.STREAM_CODEC.encode(buffer, book.pos)
                }
                is OnTheGround -> {
                    ByteBufCodecs.VAR_INT.encode(buffer, ON_THE_GROUND)
                    ByteBufCodecs.VAR_INT.encode(buffer, book.entityId)
                }
                is InAnothersHand -> {
                    ByteBufCodecs.VAR_INT.encode(buffer, IN_ANOTHERS_HAND)
                    ByteBufCodecs.VAR_INT.encode(buffer, book.holderId)
                    ByteBufCodecs.VAR_INT.encode(buffer, book.hand.ordinal)
                }
                is OnAStand -> {
                    ByteBufCodecs.VAR_INT.encode(buffer, ON_A_STAND)
                    BlockPos.STREAM_CODEC.encode(buffer, book.pos)
                    ByteBufCodecs.VAR_INT.encode(buffer, book.place)
                }
                AtACrystalViewer -> ByteBufCodecs.VAR_INT.encode(buffer, AT_A_CRYSTAL_VIEWER)
            }
        }

        private fun decode(buffer: ByteBuf): BookBeingRead =
            when (val kind = ByteBufCodecs.VAR_INT.decode(buffer)) {
                IN_HAND -> {
                    val ordinal = ByteBufCodecs.VAR_INT.decode(buffer)
                    InHand(InteractionHand.entries.getOrNull(ordinal) ?: throw DecoderException("No hand $ordinal"))
                }
                ON_A_LECTERN -> OnALectern(BlockPos.STREAM_CODEC.decode(buffer))
                ON_THE_GROUND -> OnTheGround(ByteBufCodecs.VAR_INT.decode(buffer))
                ON_A_STAND -> OnAStand(BlockPos.STREAM_CODEC.decode(buffer), ByteBufCodecs.VAR_INT.decode(buffer))
                IN_ANOTHERS_HAND -> {
                    val holderId = ByteBufCodecs.VAR_INT.decode(buffer)
                    val ordinal = ByteBufCodecs.VAR_INT.decode(buffer)
                    InAnothersHand(holderId, InteractionHand.entries.getOrNull(ordinal) ?: throw DecoderException("No hand $ordinal"))
                }
                AT_A_CRYSTAL_VIEWER -> AtACrystalViewer
                else -> throw DecoderException("No such place for a book as $kind")
            }
    }
}
