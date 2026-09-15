package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.Ages
import co.voik.agesandtheart.age.word.Resolution
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.inventory.ContainerData

/** What an instrument in a writer's room is reading, which is what decides how a calm reading is worded. */
enum class ReadingSource {
    /** Pages laid out at a desk in the room, which is what the instrument is really for. */
    A_SENTENCE,

    /** A desk in the room with nothing on it, or nothing on it that reads as a book yet. */
    AN_IDLE_DESK,

    /** No desk in the room, and the world it stands in was never written. */
    A_PLAIN_WORLD,

    /** No desk in the room, and the world it stands in is an Age. */
    AN_AGE,
    ;

    companion object {
        /** The source a synced ordinal names, or [A_PLAIN_WORLD] for one out of range. */
        fun ofOrdinal(ordinal: Int): ReadingSource = entries.getOrNull(ordinal) ?: A_PLAIN_WORLD
    }
}

/**
 * What an instrument in a writer's room currently says, worked out on the server and pushed down its menu's
 * data slots. [Reading] is what one instrument makes of a sentence, an idle desk or a world.
 *
 * **It always reads something.** With a sentence laid out at a desk in the room it reads that; with a desk
 * whose surface holds nothing that reads as a book, the idle desk; with no desk, **the world it is standing
 * in**. There is no state in which the screen is a complaint about where it was put.
 *
 * **Read rather than remembered**: there is no event that says "somebody laid a page at a desk five blocks
 * away", so asking is both simpler than invalidating and always right. **But not on every ask.** Vanilla
 * polls each data slot on every broadcast, and one reading is a room scan, a parse of the whole sentence and
 * a resolve against the corpus — so it is worked out at most once every [SETTLES_EVERY] ticks and every slot
 * reads the same cached answer. The fields are the exception to no-mutable-state, and a narrow one: they
 * belong to one open screen.
 */
abstract class InstrumentReading<Reading>(private val player: ServerPlayer?, pos: BlockPos) : ContainerData {

    private var settledAt = NOT_YET_SETTLED

    /** Which desk it is reading, and the remembering of it — see [NearbyDesk]. */
    private val desk = NearbyDesk(pos)

    /** What the last reading was of. */
    protected var source = ReadingSource.A_PLAIN_WORLD
        private set

    /** What [resolved], the sentence laid out at the desk, comes to. */
    protected abstract fun ofTheSentence(writer: ServerPlayer, resolved: Resolution): Reading

    /** What a desk in the room with nothing readable laid on it comes to. */
    protected abstract fun ofAnIdleDesk(writer: ServerPlayer): Reading

    /** What the world the instrument is standing in comes to, given its [recipe] — null for a world never written. */
    protected abstract fun ofTheWorld(writer: ServerPlayer, recipe: AgeRecipe?): Reading

    /** Keeps [reading] in the fields the data slots answer from. */
    protected abstract fun record(reading: Reading)

    /** Nothing to set: this is an instrument, and a client that tried would be told otherwise next tick. */
    override fun set(index: Int, value: Int) = Unit

    /** Works the reading out afresh, if the last one is at least [SETTLES_EVERY] ticks old. */
    protected fun settle() {
        val writer = player ?: return
        val now = writer.level().gameTime
        if (now - settledAt < SETTLES_EVERY) return
        settledAt = now
        // **A desk in the room answers even when it is bare**, which is the distinction the wording turns
        // on: an instrument beside an empty desk has nothing to read, and saying the *world* is stable
        // there would be answering a question nobody asked while a book was being started (Jonah,
        // 2026-09-09).
        val laid = desk.laidOutBy(writer)
        val said = laid?.let { resolvedFrom(writer, it) }
        when {
            said != null -> report(ofTheSentence(writer, said), ReadingSource.A_SENTENCE)
            laid != null -> report(ofAnIdleDesk(writer), ReadingSource.AN_IDLE_DESK)
            else -> {
                // Whether the world it stands in was written is the difference between a world and an Age.
                val recipe = Ages.recipeOf(writer.level())
                val standingIn = if (recipe != null) ReadingSource.AN_AGE else ReadingSource.A_PLAIN_WORLD
                report(ofTheWorld(writer, recipe), standingIn)
            }
        }
    }

    /** What a desk in the room has laid out, resolved — or null where it has nothing that reads as a book. */
    private fun resolvedFrom(writer: ServerPlayer, laid: List<Identifier>): Resolution? {
        if (laid.isEmpty()) return null
        val vocabulary = Vocabulary.of(writer.level().server)
        val said = Grammar.read(vocabulary, laid.map(Identifier::getPath)) ?: return null
        return Resolver.resolve(vocabulary, said, writer.writingSeed)
    }

    private fun report(reading: Reading, from: ReadingSource) {
        record(reading)
        source = from
    }

    private companion object {
        /** How often the instrument settles, in ticks. Five a second is far more than a readout needs. */
        const val SETTLES_EVERY = 4L

        /** Before the first reading. Any negative would do; this one cannot overflow a subtraction. */
        const val NOT_YET_SETTLED = -1L
    }
}
