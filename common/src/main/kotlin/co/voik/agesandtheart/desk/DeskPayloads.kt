package co.voik.agesandtheart.desk

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.word.InkTier
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * Everything the open desk screen needs, sent whenever it changes.
 *
 * The screen is driven from this rather than from the menu's slots, because most of what it shows —
 * an unbounded archive, three tanks, a set of capabilities — has no slot to live in. Word *search* is
 * absent on purpose: the client already holds the learned set, so filtering never leaves the machine.
 */
/**
 * One page disagreeing with another, as the work surface needs it.
 *
 * **A pair, never a loser** (design §7.3). Which of two tied words is displaced is a function of the seed,
 * so naming one as the casualty would be a guess dressed as a fact — where "these two are asking for
 * different things" is true however the draw falls. Both pages are marked and each tooltip names the other.
 *
 * [register] is `Register.key`, kept as a string so the screen can say *how* they disagree without the
 * client needing the instability model.
 */
data class Quarrel(val word: Identifier, val against: Identifier, val register: String)

data class DeskSyncPayload(
    val archive: Map<Identifier, Int>,
    val ink: Map<InkTier, Long>,
    val paper: Map<InkTier, Int>,
    val binding: Int,
    val inkCapacity: Long,
    val capabilities: Set<DeskCapability>,
    /** Null meaning no limit, which is the complete desk. */
    val pageLimit: Int?,
    /** The pages laid out in the composer, in order — order being word order. */
    val composing: List<Identifier>,
    /**
     * What is wrong with the sentence as laid out, resolved against the seed the book will actually use.
     *
     * **Sent as pairs of words rather than as flaws**, because the screen's job is to mark pages: a page is
     * red because something disagrees with it, and its tooltip names what. Severity, register and aspect
     * are the writer's business at the bind screen and the recipe's afterwards, not the work surface's.
     *
     * A word that failed *alone* — `unbacked`, nothing in the aspect could ever be it — appears paired with
     * itself, which is how "nothing here can be this" reaches a display that only knows how to mark pairs.
     */
    val quarrels: List<Quarrel>,
    /**
     * The pages laid out, **said back as a sentence** — design §4.3.1's scratch mode.
     *
     * From the server because reading one takes the whole corpus, which is a server's: the client knows
     * which words are laid out and nothing whatever about how they attach. It is the same `Readout` a bound
     * book carries, so what the desk says while you are writing and what the book says afterwards can never
     * be two different sentences.
     *
     * Prose rather than the book's `List<Said>` columns: those carry the script glyph per column and need a
     * registry-aware buffer, where this panel wants one wrapped paragraph in the reader's own language.
     */
    val reading: String,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<DeskSyncPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<DeskSyncPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "desk_sync"),
        )

        private val TIER_STREAM: StreamCodec<ByteBuf, InkTier> =
            ByteBufCodecs.idMapper({ InkTier.entries[it] }, { it.ordinal })

        private val CAPABILITY_STREAM: StreamCodec<ByteBuf, DeskCapability> =
            ByteBufCodecs.idMapper({ DeskCapability.entries[it] }, { it.ordinal })

        private val ARCHIVE_STREAM: StreamCodec<ByteBuf, LinkedHashMap<Identifier, Int>> =
            ByteBufCodecs.map(::LinkedHashMap, Identifier.STREAM_CODEC, ByteBufCodecs.VAR_INT)

        private val INK_STREAM: StreamCodec<ByteBuf, LinkedHashMap<InkTier, Long>> =
            ByteBufCodecs.map(::LinkedHashMap, TIER_STREAM, ByteBufCodecs.VAR_LONG)

        private val PAPER_STREAM: StreamCodec<ByteBuf, LinkedHashMap<InkTier, Int>> =
            ByteBufCodecs.map(::LinkedHashMap, TIER_STREAM, ByteBufCodecs.VAR_INT)

        private val QUARREL_STREAM: StreamCodec<ByteBuf, Quarrel> = StreamCodec.of(
            { buffer, value ->
                Identifier.STREAM_CODEC.encode(buffer, value.word)
                Identifier.STREAM_CODEC.encode(buffer, value.against)
                ByteBufCodecs.STRING_UTF8.encode(buffer, value.register)
            },
            { buffer ->
                Quarrel(
                    Identifier.STREAM_CODEC.decode(buffer),
                    Identifier.STREAM_CODEC.decode(buffer),
                    ByteBufCodecs.STRING_UTF8.decode(buffer),
                )
            },
        )

        val STREAM_CODEC: StreamCodec<ByteBuf, DeskSyncPayload> = StreamCodec.of(
            { buffer, value ->
                ARCHIVE_STREAM.encode(buffer, LinkedHashMap(value.archive))
                INK_STREAM.encode(buffer, LinkedHashMap(value.ink))
                PAPER_STREAM.encode(buffer, LinkedHashMap(value.paper))
                ByteBufCodecs.VAR_INT.encode(buffer, value.binding)
                ByteBufCodecs.VAR_LONG.encode(buffer, value.inkCapacity)
                CAPABILITY_STREAM.apply(ByteBufCodecs.list()).encode(buffer, value.capabilities.toList())
                ByteBufCodecs.optional(ByteBufCodecs.VAR_INT).encode(buffer, java.util.Optional.ofNullable(value.pageLimit))
                Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buffer, value.composing)
                QUARREL_STREAM.apply(ByteBufCodecs.list()).encode(buffer, value.quarrels)
                ByteBufCodecs.STRING_UTF8.encode(buffer, value.reading)
            },
            { buffer ->
                DeskSyncPayload(
                    archive = ARCHIVE_STREAM.decode(buffer),
                    ink = INK_STREAM.decode(buffer),
                    paper = PAPER_STREAM.decode(buffer),
                    binding = ByteBufCodecs.VAR_INT.decode(buffer),
                    inkCapacity = ByteBufCodecs.VAR_LONG.decode(buffer),
                    capabilities = CAPABILITY_STREAM.apply(ByteBufCodecs.list()).decode(buffer).toSet(),
                    pageLimit = ByteBufCodecs.optional(ByteBufCodecs.VAR_INT).decode(buffer).orElse(null),
                    composing = Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buffer),
                    quarrels = QUARREL_STREAM.apply(ByteBufCodecs.list()).decode(buffer),
                    reading = ByteBufCodecs.STRING_UTF8.decode(buffer),
                )
            },
        )
    }
}

/**
 * What one word would cost on each of the three papers.
 *
 * Priced by the server because the client has no vocabulary: the required tier comes from datapack tags
 * on the referent and the amount from the resolver's cost number, and neither is on this side. One small
 * round trip when a word is selected is cheaper than syncing a price table for a corpus of eleven hundred.
 */
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

        val STREAM_CODEC: StreamCodec<ByteBuf, DeskNoticePayload> = StreamCodec.of(
            { buffer, value -> ByteBufCodecs.STRING_UTF8.encode(buffer, value.reason) },
            { buffer -> DeskNoticePayload(ByteBufCodecs.STRING_UTF8.decode(buffer)) },
        )
    }
}

data class DeskPricePayload(
    val word: Identifier,
    /** Per paper tier: which ink it demands, and how much. */
    val prices: Map<InkTier, Pair<InkTier, Long>>,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<DeskPricePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<DeskPricePayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "desk_price"),
        )

        private val TIER: StreamCodec<ByteBuf, InkTier> =
            ByteBufCodecs.idMapper({ InkTier.entries[it] }, { it.ordinal })

        val STREAM_CODEC: StreamCodec<ByteBuf, DeskPricePayload> = StreamCodec.of(
            { buffer, value ->
                Identifier.STREAM_CODEC.encode(buffer, value.word)
                ByteBufCodecs.VAR_INT.encode(buffer, value.prices.size)
                for ((paper, price) in value.prices) {
                    TIER.encode(buffer, paper)
                    TIER.encode(buffer, price.first)
                    ByteBufCodecs.VAR_LONG.encode(buffer, price.second)
                }
            },
            { buffer ->
                val word = Identifier.STREAM_CODEC.decode(buffer)
                val count = ByteBufCodecs.VAR_INT.decode(buffer)
                val prices = buildMap {
                    repeat(count) {
                        val paper = TIER.decode(buffer)
                        put(paper, TIER.decode(buffer) to ByteBufCodecs.VAR_LONG.decode(buffer))
                    }
                }
                DeskPricePayload(word, prices)
            },
        )
    }
}

/** What the player asked the desk to do. */
enum class DeskAction {
    /** Write a fresh page and put it in the archive. */
    WRITE_TO_ARCHIVE,

    /** Write a fresh page straight into the composer — the inline write in the book tab. */
    WRITE_TO_BOOK,

    /** Move an archived page into the composer. */
    COMPOSE_FROM_ARCHIVE,

    /**
     * Lay the page being carried into the composer at [DeskCommandPayload.index].
     *
     * The carried stack rather than a named slot, because picking an item up is vanilla's own gesture and
     * dropping it on the work surface is the same motion continued — no second drag mechanic to invent.
     */
    COMPOSE_FROM_HAND,

    /** Take a page out of the composer, returning it to the archive. */
    RETURN_TO_ARCHIVE,

    /** Give the player a physical page from the archive. */
    WITHDRAW,

    /** Tell the desk which tab is open, so it knows which slots exist. */
    SET_TAB,

    /** Ask what a word would cost. Answered with [DeskPricePayload]; changes nothing. */
    PRICE,

    /** Move a laid-out page to another position — order is word order, so this rewrites the sentence. */
    MOVE_IN_BOOK,

    /** Bind what is laid out into a book. */
    FINALISE,
}

/**
 * One instruction from the screen.
 *
 * A single payload for every action rather than one type each: they share almost all their fields, and
 * the alternative is six codecs to keep in step with one enum.
 */
data class DeskCommandPayload(
    val action: DeskAction,
    /** The word acted on, where the action names one. */
    val word: Identifier?,
    /** Which paper to write on. Ignored by actions that do not write. */
    val paperTier: InkTier,
    /**
     * Which ink to spend, where the writer wants better than the word demands.
     *
     * A word's *required* tier is the referent's business and cannot be argued with — a diamond needs the
     * good ink. This is the floor a writer sets under it, so a stock of masterwork can be poured into
     * ordinary words rather than sitting until something demands it. The desk spends whichever is higher.
     */
    val inkTier: InkTier = InkTier.COMMON,
    /** Position in the composer the action starts from. */
    val index: Int,
    /** Where it is going, for [DeskAction.MOVE_IN_BOOK]. Ignored otherwise. */
    val target: Int = -1,
    /** The Age's name, on [DeskAction.FINALISE]. */
    val title: String,
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<DeskCommandPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<DeskCommandPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "desk_command"),
        )

        /** Long enough for a name worth giving an Age, short enough that nobody can send a novel. */
        const val MAX_TITLE = 64

        private val ACTION_STREAM: StreamCodec<ByteBuf, DeskAction> =
            ByteBufCodecs.idMapper({ DeskAction.entries[it] }, { it.ordinal })

        private val TIER_STREAM: StreamCodec<ByteBuf, InkTier> =
            ByteBufCodecs.idMapper({ InkTier.entries[it] }, { it.ordinal })

        val STREAM_CODEC: StreamCodec<ByteBuf, DeskCommandPayload> = StreamCodec.of(
            { buffer, value ->
                ACTION_STREAM.encode(buffer, value.action)
                ByteBufCodecs.optional(Identifier.STREAM_CODEC)
                    .encode(buffer, java.util.Optional.ofNullable(value.word))
                TIER_STREAM.encode(buffer, value.paperTier)
                TIER_STREAM.encode(buffer, value.inkTier)
                ByteBufCodecs.VAR_INT.encode(buffer, value.index)
                ByteBufCodecs.VAR_INT.encode(buffer, value.target)
                ByteBufCodecs.stringUtf8(MAX_TITLE).encode(buffer, value.title)
            },
            { buffer ->
                DeskCommandPayload(
                    action = ACTION_STREAM.decode(buffer),
                    word = ByteBufCodecs.optional(Identifier.STREAM_CODEC).decode(buffer).orElse(null),
                    paperTier = TIER_STREAM.decode(buffer),
                    inkTier = TIER_STREAM.decode(buffer),
                    index = ByteBufCodecs.VAR_INT.decode(buffer),
                    target = ByteBufCodecs.VAR_INT.decode(buffer),
                    title = ByteBufCodecs.stringUtf8(MAX_TITLE).decode(buffer),
                )
            },
        )
    }
}
