package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.Constants
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * The script, on its way to the client.
 *
 * Sent rather than read locally because spellings and rules are datapack content while the screen that
 * draws them is on the client — so a pack ships its script without every client installing it. A client
 * that never receives one falls back to the readable alphabet.
 */
data class LexiconPayload(val script: Script) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<LexiconPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<LexiconPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "lexicon"),
        )

        val STREAM_CODEC: StreamCodec<ByteBuf, LexiconPayload> =
            Script.STREAM_CODEC.map(::LexiconPayload, LexiconPayload::script)
    }
}

/** Which words a player knows, on its way to that player's client. */
data class LearnedWordsPayload(
    val words: List<Identifier>,
    /**
     * Whole set (a join) or an addition (a page just read). Only an addition is announced, or every login
     * would toast a player's whole library back at them.
     */
    val replacing: Boolean,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<LearnedWordsPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<LearnedWordsPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "learned_words"),
        )

        val STREAM_CODEC: StreamCodec<ByteBuf, LearnedWordsPayload> = StreamCodec.composite(
            Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()),
            LearnedWordsPayload::words,
            ByteBufCodecs.BOOL,
            LearnedWordsPayload::replacing,
            ::LearnedWordsPayload,
        )

        fun whole(words: Collection<Identifier>) = LearnedWordsPayload(words.toList(), replacing = true)

        /** Several at once, which is what studying a book teaches. The toast cycles rather than stacking. */
        fun added(words: Collection<Identifier>) = LearnedWordsPayload(words.toList(), replacing = false)
    }
}
