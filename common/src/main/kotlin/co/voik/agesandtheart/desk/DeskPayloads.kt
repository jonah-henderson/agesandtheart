package co.voik.agesandtheart.desk

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.word.grammar.ProseClause
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * One word disagreeing with another, as the template needs it.
 *
 * **A pair, never a loser** (design §7.3). Which of two tied words is displaced is a function of the seed,
 * so naming one as the casualty would be a guess dressed as a fact — where "these two are asking for
 * different things" is true however the draw falls. Both words are marked and each tooltip names the other.
 *
 * [register] is `Register.key`, kept as a string so the screen can say *how* they disagree without the
 * client needing the instability model.
 */
data class Quarrel(val word: Identifier, val against: Identifier, val register: String)

private val TIER_STREAM: StreamCodec<ByteBuf, InkTier> =
    ByteBufCodecs.idMapper({ InkTier.entries[it] }, { it.ordinal })

/**
 * Everything the open desk screen needs, sent whenever it changes.
 *
 * **The template is read here, on the server**, because telling a real word the writer has not learned
 * from a typo takes the whole vocabulary, and the client is never sent that (`decisions.md`). What crosses
 * is bounded by what the writer typed: the words it names, and a price for each page still to be written.
 */
data class DeskSyncPayload(
    val ink: Map<InkTier, Long>,
    val paper: Map<InkTier, Int>,
    val binding: Int,
    val inkCapacity: Long,
    val capabilities: Set<DeskCapability>,
    /** Null meaning no limit, which is the complete desk. */
    val pageLimit: Int?,
    /** The text these were read from, so a screen can tell a reading of what it now shows from a stale one. */
    val template: String,
    val read: List<ReadWord>,
    /** One price per page no archive or inventory holds, in order. */
    val toWrite: List<PagePrice>,
    /** How many of the book's pages are already written and will be drawn at the bind. */
    val drawn: Int,
    /**
     * What is wrong with the sentence, resolved against the seed the book will actually use — empty without
     * the implement that reveals it (design §7.3).
     */
    val quarrels: List<Quarrel>,
    /**
     * The sentence as the bound book will read it — empty without the grammar guide in the room.
     */
    val reading: List<ProseClause>,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<DeskSyncPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<DeskSyncPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "desk_sync"),
        )

        private val CAPABILITY_STREAM: StreamCodec<ByteBuf, DeskCapability> =
            ByteBufCodecs.idMapper({ DeskCapability.entries[it] }, { it.ordinal })

        private val INK_STREAM: StreamCodec<ByteBuf, LinkedHashMap<InkTier, Long>> =
            ByteBufCodecs.map(::LinkedHashMap, TIER_STREAM, ByteBufCodecs.VAR_LONG)

        private val PAPER_STREAM: StreamCodec<ByteBuf, LinkedHashMap<InkTier, Int>> =
            ByteBufCodecs.map(::LinkedHashMap, TIER_STREAM, ByteBufCodecs.VAR_INT)

        private val QUARREL_STREAM: StreamCodec<ByteBuf, Quarrel> = StreamCodec.composite(
            Identifier.STREAM_CODEC, Quarrel::word,
            Identifier.STREAM_CODEC, Quarrel::against,
            ByteBufCodecs.STRING_UTF8, Quarrel::register,
            ::Quarrel,
        )

        private val READING_STREAM: StreamCodec<ByteBuf, List<ProseClause>> =
            ByteBufCodecs.fromCodec(ProseClause.CODEC.listOf())

        val STREAM_CODEC: StreamCodec<ByteBuf, DeskSyncPayload> = StreamCodec.of(
            { buffer, value ->
                INK_STREAM.encode(buffer, LinkedHashMap(value.ink))
                PAPER_STREAM.encode(buffer, LinkedHashMap(value.paper))
                ByteBufCodecs.VAR_INT.encode(buffer, value.binding)
                ByteBufCodecs.VAR_LONG.encode(buffer, value.inkCapacity)
                CAPABILITY_STREAM.apply(ByteBufCodecs.list()).encode(buffer, value.capabilities.toList())
                ByteBufCodecs.optional(ByteBufCodecs.VAR_INT).encode(buffer, java.util.Optional.ofNullable(value.pageLimit))
                ByteBufCodecs.stringUtf8(DeskTemplatePayload.MAX_TEMPLATE).encode(buffer, value.template)
                ReadWord.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buffer, value.read)
                PagePrice.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buffer, value.toWrite)
                ByteBufCodecs.VAR_INT.encode(buffer, value.drawn)
                QUARREL_STREAM.apply(ByteBufCodecs.list()).encode(buffer, value.quarrels)
                READING_STREAM.encode(buffer, value.reading)
            },
            { buffer ->
                DeskSyncPayload(
                    ink = INK_STREAM.decode(buffer),
                    paper = PAPER_STREAM.decode(buffer),
                    binding = ByteBufCodecs.VAR_INT.decode(buffer),
                    inkCapacity = ByteBufCodecs.VAR_LONG.decode(buffer),
                    capabilities = CAPABILITY_STREAM.apply(ByteBufCodecs.list()).decode(buffer).toSet(),
                    pageLimit = ByteBufCodecs.optional(ByteBufCodecs.VAR_INT).decode(buffer).orElse(null),
                    template = ByteBufCodecs.stringUtf8(DeskTemplatePayload.MAX_TEMPLATE).decode(buffer),
                    read = ReadWord.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buffer),
                    toWrite = PagePrice.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buffer),
                    drawn = ByteBufCodecs.VAR_INT.decode(buffer),
                    quarrels = QUARREL_STREAM.apply(ByteBufCodecs.list()).decode(buffer),
                    reading = READING_STREAM.decode(buffer),
                )
            },
        )
    }
}

/**
 * Why the desk refused.
 *
 * A screen covers the action bar, so a refusal sent there is invisible exactly when the player is looking
 * for it. [reason] is the suffix of a `container.agesandtheart.writers_desk.` key.
 */
data class DeskNoticePayload(val reason: String) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<DeskNoticePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<DeskNoticePayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "desk_notice"),
        )

        val STREAM_CODEC: StreamCodec<ByteBuf, DeskNoticePayload> =
            ByteBufCodecs.STRING_UTF8.map(::DeskNoticePayload, DeskNoticePayload::reason)
    }
}

/** What the writer has typed, sent as it changes. The desk reads it and answers with a [DeskSyncPayload]. */
data class DeskTemplatePayload(val text: String) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<DeskTemplatePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<DeskTemplatePayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "desk_template"),
        )

        /** Room for a long book, and short enough that nobody can send a novel. */
        const val MAX_TEMPLATE = 4096

        val STREAM_CODEC: StreamCodec<ByteBuf, DeskTemplatePayload> =
            ByteBufCodecs.stringUtf8(MAX_TEMPLATE).map(::DeskTemplatePayload, DeskTemplatePayload::text)
    }
}

/**
 * Bind the template: pay for it and make the book.
 *
 * [paper] is what the missing pages are written on.
 */
data class DeskBindPayload(val title: String, val paper: InkTier) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<DeskBindPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<DeskBindPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "desk_bind"),
        )

        /** Long enough for a name worth giving an Age, short enough that nobody can send a novel. */
        const val MAX_TITLE = 64

        val STREAM_CODEC: StreamCodec<ByteBuf, DeskBindPayload> = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(MAX_TITLE), DeskBindPayload::title,
            TIER_STREAM, DeskBindPayload::paper,
            ::DeskBindPayload,
        )
    }
}
