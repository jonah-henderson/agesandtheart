package co.voik.agesandtheart.preview.authoring.words

import co.voik.agesandtheart.age.words.Word

/** A word as it is being written: the word, and what only its file holds — the author's comment. Immutable, so undo drops an edit. */
data class Candidate(val word: Word, val comment: List<String> = emptyList()) {
    val name: String get() = word.name
}
