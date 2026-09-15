package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.word.grammar.Constraint
import co.voik.agesandtheart.age.word.grammar.Phrase
import co.voik.agesandtheart.age.word.grammar.Sentence

/** A book already broken into constraints, with no clause structure to render — one phrase per constraint. */
fun sentenceOf(constraints: List<Constraint>, unreadable: List<String> = emptyList()): Sentence =
    Sentence(constraints.map { Phrase(modifiers = listOf(it)) }, unreadable)

/** A book with no structure — every word standing alone, aimed at its own aspects where it narrows. */
fun flatSentence(words: List<Word>): Sentence = sentenceOf(
    words.map { word ->
        Constraint(word, if (word.tier.narrows) word.aspects else emptySet())
    },
)
