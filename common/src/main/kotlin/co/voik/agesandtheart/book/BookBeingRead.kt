package co.voik.agesandtheart.book

import io.netty.buffer.ByteBuf
import io.netty.handler.codec.DecoderException
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.InteractionHand

/**
 * Where a book being read is: in a hand, or lying open on a lectern — which decides whose panel it is. Or a
 * book not yet bound, whose sentence is laid out at a desk and looked at through a crystal viewer.
 *
 * A hand's panel belongs to its screen and closes with it, and so does a viewer's. A lectern's belongs to
 * the lectern, and everybody standing at it sees the same one (design §7.8.2).
 */
sealed interface BookBeingRead {

    data class InHand(val hand: InteractionHand) : BookBeingRead

    data class OnALectern(val pos: BlockPos) : BookBeingRead

    /** The sentence laid out at a desk, through the crystal viewer whose menu the writer has open. */
    data object AtACrystalViewer : BookBeingRead

    companion object {
        private const val IN_HAND = 0
        private const val ON_A_LECTERN = 1
        private const val AT_A_CRYSTAL_VIEWER = 2

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
                AT_A_CRYSTAL_VIEWER -> AtACrystalViewer
                else -> throw DecoderException("No such place for a book as $kind")
            }
    }
}
