package co.voik.agesandtheart.desk

import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import java.util.Optional

/** What a run of typed text turned out to be. */
enum class WordState {
    /** A word the writer has learned: it has its D'ni, and it can be bound. */
    LEARNED,

    /** A real word the writer has not learned: no highlight, no D'ni, and it blocks the bind. */
    UNLEARNED,

    /** Not a word at all — a typo, or a particle the Art never writes. Red, and it blocks the bind. */
    UNKNOWN,
}

/**
 * One word read out of a template: where it sits in the text, what it is, and whether it may be written.
 *
 * [start] and [end] are character offsets into the text the server read, end exclusive, so a screen can
 * underline exactly the span the reader took as one word.
 */
data class ReadWord(val start: Int, val end: Int, val word: Identifier?, val state: WordState) {
    companion object {
        private val STATE: StreamCodec<ByteBuf, WordState> =
            ByteBufCodecs.idMapper({ WordState.entries[it] }, WordState::ordinal)

        private val WORD: StreamCodec<ByteBuf, Optional<Identifier>> = ByteBufCodecs.optional(Identifier.STREAM_CODEC)

        val STREAM_CODEC: StreamCodec<ByteBuf, ReadWord> = StreamCodec.of(
            { buffer, value ->
                ByteBufCodecs.VAR_INT.encode(buffer, value.start)
                ByteBufCodecs.VAR_INT.encode(buffer, value.end)
                WORD.encode(buffer, Optional.ofNullable(value.word))
                STATE.encode(buffer, value.state)
            },
            { buffer ->
                ReadWord(
                    start = ByteBufCodecs.VAR_INT.decode(buffer),
                    end = ByteBufCodecs.VAR_INT.decode(buffer),
                    word = WORD.decode(buffer).orElse(null),
                    state = STATE.decode(buffer),
                )
            },
        )
    }
}

/**
 * Every name a writer may type, and the word each one means.
 *
 * A word answers to its id's path with the underscores as spaces — `waxed weathered copper stairs` — and
 * to the name the word list shows it by, where a translation gives it another (`East Rising` for
 * `rising_east`). Lower case throughout, so recognition ignores case.
 */
class TemplateNames(private val byName: Map<String, Identifier>) {

    /** How many typed parts the longest name spans, so a match never looks further than it could reach. */
    val longest: Int = byName.keys.maxOfOrNull { it.split(' ').size } ?: 1

    fun wordNamed(name: String): Identifier? = byName[name]

    companion object {
        /** [displayName] is the word's translated name, or null where it has none of its own. */
        fun of(words: Collection<Identifier>, displayName: (Identifier) -> String?): TemplateNames {
            val byPath = words.associateBy { normalise(it.path) }
            val byDisplayName = words.mapNotNull { word -> displayName(word)?.let { normalise(it) to word } }
            // The path wins a clash, being the one name every word is sure to have.
            return TemplateNames(byDisplayName.toMap() + byPath)
        }

        /** A name as the reader compares it: lower case, underscores as spaces, single-spaced. */
        fun normalise(name: String): String =
            name.lowercase().split(PART).filter { it.isNotEmpty() }.joinToString(" ")

        private val PART = Regex("[\\s_]+")
    }
}

/**
 * Reading a template: the text a writer typed, taken apart into the words it names.
 *
 * **Longest match wins** (Jonah, 2026-09-18), the way a lexer takes the longest token: reading left to
 * right, the longest run of typed parts that names a word is taken as that word, so `weathered copper
 * stairs` is one word even where `copper` is another. A part that begins no name at all is read alone and
 * marked unknown.
 *
 * Pure, so it is checked offline; the server runs it against the whole vocabulary, which the client is
 * never sent.
 */
object TemplateReading {

    fun read(text: String, names: TemplateNames, isLearned: (Identifier) -> Boolean): List<ReadWord> {
        val parts = PART.findAll(text).toList()
        val read = mutableListOf<ReadWord>()
        var at = 0
        while (at < parts.size) {
            val (span, word) = longestNameFrom(parts, at, names)
            val first = parts[at].range.first
            val last = parts[at + span - 1].range.last + 1
            val state = when {
                word == null -> WordState.UNKNOWN
                isLearned(word) -> WordState.LEARNED
                else -> WordState.UNLEARNED
            }
            read += ReadWord(first, last, word, state)
            at += span
        }
        return read
    }

    /** The words a template names, in order — only those that could be bound. */
    fun learnedWords(read: List<ReadWord>): List<Identifier> =
        read.filter { it.state == WordState.LEARNED }.mapNotNull { it.word }

    /** Whether every run in the template is a word the writer may write. */
    fun isWritable(read: List<ReadWord>): Boolean = read.all { it.state == WordState.LEARNED }

    /** How many parts from [from] name a word, longest first; one part and no word where none does. */
    private fun longestNameFrom(parts: List<MatchResult>, from: Int, names: TemplateNames): Pair<Int, Identifier?> {
        val reach = minOf(names.longest, parts.size - from)
        for (span in reach downTo 1) {
            val name = parts.subList(from, from + span).joinToString(" ") { it.value.lowercase() }
            names.wordNamed(name)?.let { return span to it }
        }
        return 1 to null
    }

    /** A typed part: anything between spaces or underscores. */
    private val PART = Regex("[^\\s_]+")
}
