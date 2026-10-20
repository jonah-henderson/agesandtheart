package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.language.Meaning
import co.voik.agesandtheart.age.language.Speech
import co.voik.agesandtheart.age.language.Word
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.location
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier

/**
 * A word as it is being written: the game's own meanings, and what only its file holds — the author's comment.
 *
 * Immutable, so an edit is a new candidate and undo drops the last one.
 */
data class Candidate(
    val name: String,
    val comment: List<String> = emptyList(),
    val speech: Speech? = null,
    val takes: Set<Identifier> = emptySet(),
    val meanings: List<Meaning> = emptyList(),
    /** The registry entry a derived word is, where the game wrote it from one. */
    val derivedFrom: Identifier? = null,
    /** Whether this is an aspect's own page, which the game writes too. */
    val isAnAspectPage: Boolean = false,
    /** The tag directory of the registry [derivedFrom] is in, where its ink and loot are tags on the entry. */
    val tagDirectory: String? = null,
) {
    val id: Identifier get() = derivedFrom ?: name.location()

    /** Whether the game writes this word rather than a file — then only its rarity and ink can be set. */
    val isDerived: Boolean get() = derivedFrom != null || isAnAspectPage

    /** What the rarity list and the display names call this word. */
    val listingKey: String get() = derivedFrom?.toString() ?: name

    fun asWord(): Word = Word(id, meanings, speech, takes)

    companion object {
        /** A word the corpus holds, opened as the corpus has it. */
        fun of(word: Word, vocabulary: Vocabulary, comment: List<String> = emptyList()): Candidate {
            val isDerived = vocabulary.isDerived(word)
            return Candidate(
                name = word.name,
                comment = comment,
                speech = word.speech,
                takes = word.alsoTakes,
                meanings = word.meanings,
                derivedFrom = word.id.takeIf { isDerived },
                isAnAspectPage = Aspect.byPage(word.name) != null,
                tagDirectory = word.referentRegistries.firstOrNull()?.takeIf { isDerived }?.let(Registries::tagsDirPath),
            )
        }

        /** A word read from its file. */
        fun authored(word: Word, comment: List<String>) =
            Candidate(word.name, comment, word.speech, word.alsoTakes, word.meanings)
    }
}
