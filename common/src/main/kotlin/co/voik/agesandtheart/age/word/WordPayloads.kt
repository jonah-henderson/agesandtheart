package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Aspect
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * The script, and which parts of the world each word reaches, on their way to the client.
 *
 * Sent rather than read locally because both are datapack content while the screens that draw them are on
 * the client — so a pack ships its script without every client installing it. A client that never receives
 * one falls back to the readable alphabet, and its desk says nothing of where words apply.
 */
data class LexiconPayload(
    val script: Script,
    /** [Word.aspects] for every word, by id — empty meaning anywhere. The desk's word list shows it with a grammar guide by. */
    val reach: Map<Identifier, Set<Aspect>>,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<LexiconPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<LexiconPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "lexicon"),
        )

        /** What [vocabulary] tells a client. */
        fun of(vocabulary: Vocabulary) =
            LexiconPayload(vocabulary.script, vocabulary.words.associate { it.id to it.aspects })

        /** By ordinal, which is stable: aspects are only ever appended (see [Aspect]). */
        private val ASPECT_STREAM_CODEC: StreamCodec<ByteBuf, Aspect> =
            ByteBufCodecs.idMapper({ Aspect.entries[it] }, Aspect::ordinal)

        val STREAM_CODEC: StreamCodec<ByteBuf, LexiconPayload> = StreamCodec.composite(
            Script.STREAM_CODEC,
            LexiconPayload::script,
            ByteBufCodecs.map(
                ::LinkedHashMap,
                Identifier.STREAM_CODEC,
                ASPECT_STREAM_CODEC.apply(ByteBufCodecs.collection(::LinkedHashSet)),
            ),
            LexiconPayload::reach,
            ::LexiconPayload,
        )
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
