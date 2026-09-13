package co.voik.agesandtheart.book

import io.kotest.core.spec.style.FunSpec
import io.netty.buffer.Unpooled
import io.netty.handler.codec.DecoderException
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionHand

/** Where a book is has to cross the wire whole — a lectern's position as much as a hand. */
class BookBeingReadCheck : FunSpec({

    fun roundTrip(book: BookBeingRead): BookBeingRead {
        val buffer = Unpooled.buffer()
        BookBeingRead.STREAM_CODEC.encode(buffer, book)
        val decoded = BookBeingRead.STREAM_CODEC.decode(buffer)
        check(!buffer.isReadable) { "${buffer.readableBytes()} bytes left unread after $book" }
        return decoded
    }

    test("either hand comes back as itself") {
        for (hand in InteractionHand.entries) {
            val book = BookBeingRead.InHand(hand)
            check(roundTrip(book) == book)
        }
    }

    test("a lectern comes back at its own position") {
        val book = BookBeingRead.OnALectern(BlockPos(-1203, -60, 30_000_017))
        check(roundTrip(book) == book)
    }

    test("a place the codec does not know is refused rather than guessed at") {
        val buffer = Unpooled.buffer().writeByte(99)
        val refused = runCatching { BookBeingRead.STREAM_CODEC.decode(buffer) }.exceptionOrNull()
        check(refused is DecoderException) { "decoded anyway, or failed with $refused" }
    }
})
