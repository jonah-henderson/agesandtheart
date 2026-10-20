package co.voik.agesandtheart.preview.authoring

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.language.Meaning
import co.voik.agesandtheart.age.language.Structure
import co.voik.agesandtheart.age.language.Word
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.word.Vocabulary

/**
 * What page could be laid next, read off the reading (`notes/meanings-plan.md` §2).
 *
 * **The bare minimum the meanings rework left it**, until Scrivener is reworked for meanings (phase 6). On meanings
 * almost nothing is inert while a book is being written — a page nothing takes is laid on the Age — so every page is
 * offered, the closers are the words that would take what is waiting, and an offer's claims are its meanings.
 */
class Suggestions(private val vocabulary: Vocabulary) {

    /** One page that could be laid, and enough about it to choose between it and a thousand others. */
    data class Offer(
        val page: String,
        val says: String,
        val authored: Boolean,
        /** Whether it takes the pages before it, which is what most changes the shape. */
        val firmness: String,
        /** The parts of an Age this page can be laid on, as their pages. */
        val targets: String,
        /** Whether this page is a block — a substance, which takes nothing and waits for what does. */
        val isMaterial: Boolean,
        /** The meanings it never lands without. */
        val required: List<Claim>,
        /** The meanings it lands by chance. */
        val requested: List<Claim>,
        /** Whether it would take what is waiting, closing the clause being written. */
        val closes: Boolean,
    )

    /** One thing a meaning says. */
    data class Claim(val said: String, val drawnFrom: List<String> = emptyList()) {
        val spelled: String get() = if (drawnFrom.isEmpty()) said else "$said ${drawnFrom.joinToString(" ")}"
    }

    /** What could follow the row: what would do something, and what would parse and then not. */
    data class Offers(val bearing: List<Offer>, val inert: List<Offer>) {
        val all: List<Offer> get() = bearing + inert
    }

    /** Every page that could follow [laid], those that would take what is waiting first. */
    fun after(laid: List<String>): Offers {
        val closing = closersAfter(laid).toSet()
        val offers = sayable.map { offerOf(it, closes = it in closing) }
        return Offers(offers.sortedWith(compareByDescending<Offer> { it.closes }.thenByDescending { it.authored }.thenBy { it.page }), emptyList())
    }

    /** The parts of the world the pages waiting at the end of [laid] could still be taken by, or null where none wait. */
    fun aimsStillOpen(laid: List<String>): Set<Aspect>? {
        if (laid.isEmpty() || isASentence(laid)) return null
        return closersAfter(laid).mapNotNull(Aspect::byPage).toSet().ifEmpty { null }
    }

    /** The pages of the parts of the world that would take any meaning of a page waiting at the end of [laid]. */
    fun closersAfter(laid: List<String>): List<String> {
        val waiting = laid.takeLastWhile { page ->
            val word = vocabulary.word(page)
            vocabulary.structurePage(page) == null && word != null && vocabulary.lexicon.acceptance(word) == null
        }.mapNotNull(vocabulary::word)
        if (waiting.isEmpty()) return emptyList()
        val effects = waiting.first().effects
        return Aspect.entries.map { it.page }.filter { page ->
            val taker = vocabulary.word(page)?.let(vocabulary.lexicon::acceptance) ?: return@filter false
            effects.any { taker.take(it) != null }
        }
    }

    /** Whether the row as it stands reads as laid, every page finding where it was written. */
    fun isASentence(laid: List<String>): Boolean = laid.isNotEmpty() && vocabulary.read(laid, SEED)?.readsAsLaid == true

    /** Every page a writer could lay, once each: the words written by hand, the parts of the world, the structure. */
    private val sayable: List<String> by lazy {
        (vocabulary.authoredWords.map { it.name } + Aspect.entries.map { it.page } + vocabulary.structurePages.map { it.name })
            .distinct()
    }

    private fun offerOf(page: String, closes: Boolean): Offer {
        val word = vocabulary.word(page)
        val structure = vocabulary.structurePage(page)?.structure
        val (always, byChance) = word?.meanings.orEmpty().partition(Meaning::mustAlwaysApply)
        val takes = word?.let(vocabulary.lexicon::acceptance) != null || structure == Structure.AGE
        return Offer(
            page = page,
            says = when {
                structure != null -> "structure — ${structure.production}"
                word == null -> ""
                else -> word.meanings.joinToString(", ") { meaning -> meaning.effects.joinToString(" ", transform = ::said) }
            },
            authored = word != null && !vocabulary.isDerived(word),
            firmness = if (takes) "takes" else "",
            targets = word?.let(vocabulary.lexicon::placesFor).orEmpty().sortedBy { it.ordinal }.joinToString(" ") { it.page },
            isMaterial = word?.block != null,
            required = always.map(::claimOf),
            requested = byChance.map(::claimOf),
            closes = closes || structure == Structure.AGE,
        )
    }

    private fun claimOf(meaning: Meaning): Claim = Claim(meaning.effects.joinToString(" ", transform = ::said))

    private fun said(effect: Effect): String = when (effect) {
        is Effect.Sets -> "${effect.parameter.spelled}=${effect.value}"
        is Effect.Bends -> "bends ${effect.parameter.spelled} toward ${effect.value}"
        is Effect.Chooses -> "chooses ${effect.member} in ${effect.aspect.page}"
        is Effect.Admits -> "adds ${effect.members.sorted().joinToString(" ")} to ${effect.aspect.page}"
        is Effect.Excludes -> "removes ${effect.members.sorted().joinToString(" ")} from ${effect.aspect.page}"
        is Effect.Restricts -> "keeps ${effect.bars.keys.joinToString(" ") { "#$it" }} in ${effect.aspect.page}"
        is Effect.Leans -> "leans the ${effect.aspect.page}"
        is Effect.LeansEverywhere -> "leans everything"
        is Effect.Mints -> "mints ${effect.pattern}"
        is Effect.Template -> "begins the Age from ${effect.name}"
        is Effect.Abundance -> "${effect.rung} times as many"
        is Effect.MadeOf -> "made of ${effect.substance}"
    }

    private companion object {
        /** Whether a row reads as laid does not depend on the draw, so any seed asks it. */
        const val SEED = 0L
    }
}
