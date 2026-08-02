package co.voik.agesandtheart.age.word.generation

import co.voik.agesandtheart.age.word.Vocabulary
import kotlin.random.Random

/**
 * What a generated Age is called: the syllables it was drawn as, those syllables read aloud, and the same
 * name in the script the Art is written in.
 */
data class AgeName(val syllables: List<String>, val read: String, val written: String) {
    companion object {
        /** The grammar a name is drawn from — `art/generation/name.json`. */
        const val GRAMMAR = "name"

        /**
         * A name at [seed], or null where the pack ships no name grammar.
         *
         * **Spelled syllable by syllable rather than whole.** A pack that authors its syllables' spellings
         * gets every name exactly; one that does not gets the transliteration rules' approximation of each
         * syllable, which is what a hand-picked inventory can be chosen to survive.
         */
        fun drawn(vocabulary: Vocabulary, seed: Long): AgeName? {
            val grammar = vocabulary.generation.grammar(GRAMMAR) ?: return null
            val syllables = grammar.expand(Random(seed))
            if (syllables.isEmpty()) return null
            return AgeName(
                syllables = syllables,
                read = syllables.joinToString("").replaceFirstChar(Char::uppercaseChar),
                written = syllables.joinToString("") { vocabulary.script.spell(it) },
            )
        }
    }
}
