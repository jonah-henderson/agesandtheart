package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.age.word.learnedWords
import net.minecraft.locale.Language
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer

/**
 * Reading a writer's template on the server, which is the only side that has the whole vocabulary.
 *
 * The names are built once per vocabulary and kept until a reload replaces it — `Vocabulary.of` already
 * caches on the resource manager, so a new vocabulary instance is exactly the signal to rebuild.
 */
object DeskTemplates {

    private var namesBuiltFor: Vocabulary? = null
    private var names: TemplateNames = TemplateNames(emptyMap())

    fun namesFor(vocabulary: Vocabulary): TemplateNames {
        if (namesBuiltFor !== vocabulary) {
            val pageWords = vocabulary.words.map { it.id } + vocabulary.grammarWords.map { it.id }
            names = TemplateNames.of(pageWords, ::translatedName)
            namesBuiltFor = vocabulary
        }
        return names
    }

    /** [text] as [writer] would have it read: which runs are words, and which of those they know. */
    fun read(writer: ServerPlayer, text: String): List<ReadWord> {
        val vocabulary = Vocabulary.of(writer.level().server)
        return TemplateReading.read(text, namesFor(vocabulary), writer.learnedWords::knows)
    }

    /** The words [writer] could bind from [text] — what an instrument in the room reads. */
    fun wordsOf(writer: ServerPlayer, text: String): List<Identifier> =
        TemplateReading.learnedWords(read(writer, text))

    /** [words] as a writer would type them into a template — the name the word list shows, where it has one. */
    fun typed(words: List<Identifier>): String =
        words.joinToString(" ") { translatedName(it) ?: it.path.replace('_', ' ') }

    /**
     * The server's own translation, where there is one — both loaders load a mod's `en_us` on a dedicated
     * server. Null where the key is missing, which is every derived word: those are named by their path.
     */
    private fun translatedName(word: Identifier): String? {
        val key = WordNames.key(word)
        val language = Language.getInstance()
        return if (language.has(key)) language.getOrDefault(key) else null
    }
}
