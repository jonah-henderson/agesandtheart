package co.voik.agesandtheart.book

import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.generation.AgeName
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.age.word.grammar.Prose
import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.SurveyReport
import com.mojang.serialization.Codec
import net.minecraft.server.MinecraftServer
import net.minecraft.util.StringRepresentable
import net.minecraft.world.item.ItemStack
import kotlin.random.Random

/**
 * Which kind of book somebody else wrote, each written from its own generation grammar (design §4.2).
 *
 * [isCoherent] is what `BookCheck` holds each grammar to: an unstable book is somebody's mistake on
 * purpose, and every other kind is an exemplar that must not argue with itself.
 */
enum class FoundBookKind(val key: String, val isCoherent: Boolean) : StringRepresentable {
    /** Short and plain, from common words — what the scatter and the lost library hold. */
    BASIC("basic", isCoherent = true),

    /** Structurally rich and rarer-worded — the D'ni's own, and where the modifiers are taught. */
    ADVANCED("advanced", isCoherent = true),

    /** A book put away as unfit, kept in the city's quarantine chamber. */
    UNSTABLE("unstable", isCoherent = false),
    ;

    /** The generation grammar it is written from — `art/generation/<key>_book.json`. */
    val grammar: String get() = "${key}_book"

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<FoundBookKind> = StringRepresentable.fromEnum(FoundBookKind::values)
    }
}

/**
 * A book somebody else already wrote (design §4.5).
 *
 * **The same components in the same order the desk sets**, because a found book and a bound one have to be
 * the same object: everything a player can do with one they must be able to do with the other, and the
 * moment the two differ, testing either says nothing about it.
 */
object FoundBook {
    /** What a book is called where the pack ships no name grammar to draw one from. */
    private const val UNNAMED = "Untitled"

    /**
     * [stack] written as a [kind] of book somebody once wrote, at [seed].
     *
     * **Writes the words even where there is nothing to write**, so a blank book is blank *once*: the
     * caller's cue to try is the words being absent, and a pack with no book grammar would otherwise have
     * every book in the world re-reading the corpus for as long as it existed.
     */
    fun write(stack: ItemStack, server: MinecraftServer, seed: Long, kind: FoundBookKind) {
        val vocabulary = Vocabulary.of(server)
        val pages = vocabulary.generation.grammar(kind.grammar)?.expand(Random(seed)).orEmpty()
        writePages(stack, vocabulary, pages) { AgeName.drawn(vocabulary, seed)?.read ?: UNNAMED }
    }

    /**
     * [stack] written as the book of the Age [report] surveyed (design §7.6) — its name, its sentence, and
     * its seed, so every copy of it leads to the same world.
     */
    fun writeSurveyed(stack: ItemStack, server: MinecraftServer, report: SurveyReport) {
        writePages(stack, Vocabulary.of(server), report.sentence) { report.ageName }
        stack.set(AgeComponents.BOOK_SEED, report.ageSeed)
    }

    private fun writePages(stack: ItemStack, vocabulary: Vocabulary, pages: List<String>, title: () -> String) {
        val words = pages.mapNotNull { page -> vocabulary.word(page)?.id ?: vocabulary.grammarWord(page)?.id }
        stack.set(AgeComponents.BOOK_WORDS, words)
        if (words.isEmpty()) return
        stack.set(AgeComponents.BOOK_TITLE, title())
        // A sentence that dropped the `age` page is something no player could bind, so the book goes out
        // unread rather than carrying a reading of a sentence it does not spell.
        val read = Grammar.read(vocabulary, pages) ?: return
        stack.set(AgeComponents.BOOK_READING, Prose.of(read))
    }
}
