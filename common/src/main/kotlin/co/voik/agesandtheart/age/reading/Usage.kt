package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.words.Offer
import co.voik.agesandtheart.age.words.Sense
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.age.words.Words

/**
 * Which words take which, asked of whole words rather than of a row: the reader's own rule ([Matching]) put to every
 * offer a word makes and every thing that could meet it. What a word applies to is read off this — the word list's
 * headings, the parts a page reaches — where what a page does laid in a particular row is the reader's to say.
 */
class Usage(val words: Words, membership: Membership) {

    private val matching = Matching(membership)

    /** A sense that ends a phrase and takes something, with the word it belongs to. */
    data class Taker(val word: Word, val sense: Sense) {
        val part: Aspect? get() = sense.speaksFor
    }

    /** Every thing and part's page there is, each sense of it that takes anything. */
    val takers: List<Taker> by lazy {
        words.all.flatMap { word ->
            word.senses.filter { it.endsAPhrase && it.behaviour == null && it.acceptances.isNotEmpty() }.map { Taker(word, it) }
        }
    }

    /** Whether [sense] would take [offer] where the two met: the concept, an audience admitting it, and a value it takes. */
    fun takes(sense: Sense, offer: Offer): Boolean {
        val acceptance = matching.acceptanceFor(sense, offer) ?: return false
        return matching.valueFits(sense, acceptance, offer)
    }

    /** Whether some sense of [taker] would take something [word] offers. */
    fun takes(taker: Word, word: Word): Boolean = taker.senses.any { sense -> takesAnythingOf(sense, word) }

    /** Every other word's thing that would take something [word] offers. */
    fun takersOf(word: Word): List<Taker> = takers.filter { it.word.id != word.id && takesAnythingOf(it.sense, word) }

    /** The parts of the world [word] reaches through the things that take it: none for a word only `age` hands out. */
    fun partsReached(word: Word): Set<Aspect> = takersOf(word).mapNotNull(Taker::part).toSet()

    private fun takesAnythingOf(sense: Sense, word: Word): Boolean =
        word.senses.any { theirs -> theirs.offers.any { offer -> takes(sense, offer) } }
}
