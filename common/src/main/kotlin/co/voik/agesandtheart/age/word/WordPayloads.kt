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
    /** Where each word may be aimed, by id — empty meaning anywhere. The desk's word list shows it with a grammar guide by. */
    val reach: Map<Identifier, Set<Aspect>>,
    /** Every word that is a material, and so can be what a [shapes] word is made of. */
    val materials: Set<Identifier>,
    /** Every word that mints a shape out of a material — `rings`, `arches`, `veins`. */
    val shapes: Set<Identifier>,
    /** How rare each word's page is, as a player is told it, for a list ordered by rarity. */
    val rarity: Map<Identifier, WordHeadings.Rarity> = emptyMap(),
    /** The words a list ordered by what words apply to is headed by, which [appliesTo] counts into. */
    val headings: List<Identifier> = emptyList(),
    /** Which of [headings] each word applies to, by its place there. */
    val appliesTo: Map<Identifier, List<Int>> = emptyMap(),
    /** Every structure page, which a book may carry beside its words. */
    val structural: Set<Identifier> = emptySet(),
    /** Every retired id that leads to a word now, and that word's id. */
    val renamed: Map<Identifier, Identifier> = emptyMap(),
    /** Every word naming members of a part of the world, which a list ordered by what words apply to sets apart. */
    val members: Set<Identifier> = emptySet(),
    /** Every word naming a whole part of the world. */
    val parts: Set<Identifier> = emptySet(),
) : CustomPacketPayload {

    override fun type(): CustomPacketPayload.Type<LexiconPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<LexiconPayload> = CustomPacketPayload.Type(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "lexicon"),
        )

        /** What [vocabulary] tells a client. */
        fun of(vocabulary: Vocabulary): LexiconPayload {
            val headings = vocabulary.appliesTo.values.flatten().distinct().sorted()
            val headingAt = headings.withIndex().associate { (at, heading) -> heading to at }
            return LexiconPayload(
                vocabulary.script,
                vocabulary.words.associate { it.id to vocabulary.lexicon.placesFor(it) } + vocabulary.phrases,
                materials = vocabulary.words.filter { it.block != null }.map { it.id }.toSet(),
                shapes = vocabulary.words.filter { it.mints != null }.map { it.id }.toSet(),
                rarity = vocabulary.words.associate { it.id to WordHeadings.rarityOf(vocabulary, it) },
                headings = headings,
                appliesTo = vocabulary.appliesTo.mapValues { (_, under) -> under.mapNotNull(headingAt::get) },
                structural = vocabulary.structurePages.map { it.id }.toSet(),
                renamed = vocabulary.renamed,
                members = vocabulary.namingMembers,
                parts = vocabulary.namingParts,
            )
        }

        private val RARITY_STREAM_CODEC: StreamCodec<ByteBuf, WordHeadings.Rarity> =
            ByteBufCodecs.idMapper({ WordHeadings.Rarity.entries[it] }, WordHeadings.Rarity::ordinal)

        /** By ordinal, which is stable: aspects are only ever appended (see [Aspect]). */
        private val ASPECT_STREAM_CODEC: StreamCodec<ByteBuf, Aspect> =
            ByteBufCodecs.idMapper({ Aspect.entries[it] }, Aspect::ordinal)

        private val ASPECTS: StreamCodec<ByteBuf, Set<Aspect>> = ASPECT_STREAM_CODEC.apply(ByteBufCodecs.collection(::LinkedHashSet))

        private val INDICES: StreamCodec<ByteBuf, List<Int>> = ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list())

        /**
         * **Every id written once**, in a table at the head, and each mention of it after as its place there: a word
         * is named in four or five of the lists, and its id written out each time was most of the payload.
         */
        val STREAM_CODEC: StreamCodec<ByteBuf, LexiconPayload> = StreamCodec.of(::write, ::read)

        private fun idsIn(payload: LexiconPayload): List<Identifier> = with(payload) {
            (reach.keys + materials + shapes + rarity.keys + headings + appliesTo.keys + structural + renamed.keys + renamed.values +
                members + parts).distinct()
        }

        private fun write(buffer: ByteBuf, payload: LexiconPayload) {
            val table = idsIn(payload)
            val at = table.withIndex().associate { (index, id) -> id to index }
            fun id(id: Identifier) = ByteBufCodecs.VAR_INT.encode(buffer, at.getValue(id))
            fun ids(ids: Collection<Identifier>) = INDICES.encode(buffer, ids.map(at::getValue))
            fun <V> map(map: Map<Identifier, V>, value: (V) -> Unit) {
                ByteBufCodecs.VAR_INT.encode(buffer, map.size)
                for ((key, held) in map) {
                    id(key)
                    value(held)
                }
            }
            Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buffer, table)
            Script.STREAM_CODEC.encode(buffer, payload.script)
            map(payload.reach) { ASPECTS.encode(buffer, it) }
            ids(payload.materials)
            ids(payload.shapes)
            map(payload.rarity) { RARITY_STREAM_CODEC.encode(buffer, it) }
            ids(payload.headings)
            map(payload.appliesTo) { INDICES.encode(buffer, it) }
            ids(payload.structural)
            map(payload.renamed) { id(it) }
            ids(payload.members)
            ids(payload.parts)
        }

        private fun read(buffer: ByteBuf): LexiconPayload {
            val table = Identifier.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buffer)
            fun id(): Identifier = table[ByteBufCodecs.VAR_INT.decode(buffer)]
            fun ids(): List<Identifier> = INDICES.decode(buffer).map(table::get)
            fun <V> map(value: () -> V): Map<Identifier, V> {
                val size = ByteBufCodecs.VAR_INT.decode(buffer)
                val read = LinkedHashMap<Identifier, V>(size)
                repeat(size) { read[id()] = value() }
                return read
            }
            return LexiconPayload(
                script = Script.STREAM_CODEC.decode(buffer),
                reach = map { ASPECTS.decode(buffer) },
                materials = ids().toCollection(LinkedHashSet()),
                shapes = ids().toCollection(LinkedHashSet()),
                rarity = map { RARITY_STREAM_CODEC.decode(buffer) },
                headings = ids(),
                appliesTo = map { INDICES.decode(buffer) },
                structural = ids().toCollection(LinkedHashSet()),
                renamed = map { id() },
                members = ids().toCollection(LinkedHashSet()),
                parts = ids().toCollection(LinkedHashSet()),
            )
        }
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
