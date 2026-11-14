package co.voik.agesandtheart.preview.authoring.words

import co.voik.agesandtheart.age.words.Sense
import co.voik.agesandtheart.age.words.Word
import net.minecraft.resources.Identifier

/** A word as it is being written: the word, and what only its file holds — the author's comment. Immutable, so undo drops an edit. */
data class Candidate(val word: Word, val comment: List<String> = emptyList()) {
    val name: String get() = word.name

    /** This candidate with [change] made to its word. */
    fun changingWord(change: (Word) -> Word): Candidate = copy(word = change(word))

    /** The same word under [name], in the same pack. [name] must be one a file may have ([WordFile.couldBeAName]). */
    fun renamed(name: String): Candidate {
        require(WordFile.couldBeAName(name)) { "'$name' cannot be a word's name" }
        return copy(word = word.copy(id = Identifier.fromNamespaceAndPath(word.id.namespace, name)))
    }

    companion object {
        /** A new word of one sense that says nothing yet. */
        fun blank(id: Identifier): Candidate = Candidate(Word(id, listOf(Sense())))
    }
}
