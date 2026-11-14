package co.voik.agesandtheart.preview.authoring.words

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.reading.Fate
import co.voik.agesandtheart.age.reading.Reader
import co.voik.agesandtheart.age.reading.Reading
import co.voik.agesandtheart.age.reading.StructureWord
import co.voik.agesandtheart.age.reading.Usage
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.words.Audience
import co.voik.agesandtheart.age.words.CoreConcepts
import co.voik.agesandtheart.age.words.Offer
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.age.words.Words
import co.voik.agesandtheart.preview.authoring.words.ui.Sections

/**
 * What page could be laid next, asked of the reader itself: each of our pages read after the row and an `age` after it,
 * the pages that would take what is waiting first, and set aside those that would land nothing, take nothing and bind
 * nothing where they were laid. A describing word some thing takes is kept, since it waits for what follows, and what a
 * page reaches wherever it is laid is [Usage]'s.
 */
class Suggestions(private val corpus: WordCorpus) {

    private val words: Words get() = corpus.words

    private val usage: Usage by lazy { Usage(words, corpus.membership) }

    /** One page that could be laid, and enough about it to choose between it and two hundred others. */
    data class Suggestion(
        val page: String,
        val says: String,
        val authored: Boolean,
        /** Whether it takes the pages before it, which is what most changes the shape. */
        val firmness: String,
        /** The parts of the world a thing taking it puts it on, as their pages. */
        val targets: String,
        /** Whether this page offers a block — a substance, which waits for what is made of something. */
        val isMaterial: Boolean,
        /** What it offers for certain, and what it means itself. */
        val required: List<Claim>,
        /** What it offers by chance. */
        val requested: List<Claim>,
        /** Whether it would take what is waiting, closing the phrase being written. */
        val closes: Boolean,
    )

    /** One thing a page says. */
    data class Claim(val said: String) {
        val spelled: String get() = said
    }

    /** What could follow the row: what would do something, and what would parse and then not. */
    data class Suggested(val bearing: List<Suggestion>, val inert: List<Suggestion>) {
        val all: List<Suggestion> get() = bearing + inert
    }

    /** Every page that could follow [laid], those that would take what is waiting first and the inert set aside. */
    fun after(laid: List<String>): Suggested {
        val waiting = waitingAt(laid)
        val ordering = compareByDescending<Suggestion> { it.closes }.thenByDescending { it.authored }.thenBy { it.page }
        val read = sayable.associateWith { page -> readWith(laid, page) }
        fun bearsHere(page: String) = isGrammar(page) || waitsForATaker(page) || bears(read.getValue(page), laid.size)
        val (bearing, inert) = sayable.partition(::bearsHere)
        fun closes(page: String) = waiting.isNotEmpty() && closes(read.getValue(page), laid.size, waiting)
        return Suggested(
            bearing.map { suggestionOf(it, closes(it)) }.sortedWith(ordering),
            inert.map { suggestionOf(it, closes = false) }.sortedWith(ordering),
        )
    }

    /** The parts of the world the pages waiting at the end of [laid] could still be taken by, or null where none wait. */
    fun aimsStillOpen(laid: List<String>): Set<Aspect>? {
        if (laid.isEmpty() || isASentence(laid)) return null
        return closersAfter(laid).mapNotNull(Aspect::byPage).toSet().ifEmpty { null }
    }

    /** The parts' pages that would take a page waiting at the end of [laid]. */
    fun closersAfter(laid: List<String>): List<String> {
        val waiting = waitingAt(laid)
        if (waiting.isEmpty()) return emptyList()
        return Aspect.entries.mapNotNull { words.partPage(it)?.name }.filter { page -> closes(readWith(laid, page), laid.size, waiting) }
    }

    /** Whether the row as it stands reads as laid: every page a word, and none left with nowhere to land. */
    fun isASentence(laid: List<String>): Boolean {
        if (laid.isEmpty()) return false
        return read(laid).pages.none { it.fate == Fate.Unknown || it.fate == Fate.Dangles }
    }

    /** Whether [page] is one of the structure words, which bind the pages after it: read with nothing after, it binds nothing. */
    private fun isGrammar(page: String): Boolean = words.word(page)?.defaultSense?.behaviour != null

    /**
     * Whether [page] is a describing word some thing would take: laid last it waits for what follows, and only a
     * reading of what follows could say whether it lands — `rising` before a sun.
     */
    private fun waitsForATaker(page: String): Boolean = page in describingWordsSomethingTakes

    private val describingWordsSomethingTakes: Set<String> by lazy {
        corpus.written.filter { word -> word.defaultSense?.endsAPhrase == false && usage.takersOf(word).isNotEmpty() }.map(Word::name).toSet()
    }

    /** Every page a writer could lay, once each: the words of ours, the parts' pages and the structure words among them. */
    private val sayable: List<String> by lazy { corpus.written.map(Word::name).distinct() }

    private fun read(pages: List<String>): Reading = Reader.read(words, pages, corpus.membership, SEED)

    /**
     * [laid] with [page] after it, closed by an `age` standing for whatever comes after: a describing word laid last is
     * waiting for what follows it, not inert, and the Age takes what nothing else does.
     */
    private fun readWith(laid: List<String>, page: String): Reading {
        val isAnAge = words.word(page)?.defaultSense?.behaviour == StructureWord.Age.id
        return read(if (isAnAge) laid + page else laid + page + AGE)
    }

    /** The pages at the end of [laid] that nothing has taken yet: what the next page might take. */
    private fun waitingAt(laid: List<String>): Set<Int> =
        read(laid).pages.takeLastWhile { it.fate == Fate.Dangles }.map { it.index }.toSet()

    /** Whether the page at [at] does anything in [reading]: lands something, takes something, or binds as structure. */
    private fun bears(reading: Reading, at: Int): Boolean {
        val page = reading.pages.getOrNull(at) ?: return false
        val bindsAsStructure = page.fate == Fate.Structure
        val placesOrTakes = reading.placements.any { it.from == at || it.at == at }
        val meansSomethingItself = reading.phrases.any { it.head == at && it.own.isNotEmpty() }
        return bindsAsStructure || placesOrTakes || meansSomethingItself
    }

    /** Whether the page at [at] takes one of the [waiting] pages before it. */
    private fun closes(reading: Reading, at: Int, waiting: Set<Int>): Boolean =
        reading.placements.any { it.at == at && it.from in waiting }

    private fun suggestionOf(page: String, closes: Boolean): Suggestion {
        val word = words.word(page)
        val sense = word?.defaultSense
        val behaviour = sense?.behaviour
        val offers = word?.senses.orEmpty().flatMap { it.offers }
        val (certain, byChance) = offers.partition { it.chance == Offer.CERTAIN }
        val means = word?.senses.orEmpty().flatMap { it.means }
        val takes = word?.senses.orEmpty().any { it.endsAPhrase && it.acceptances.isNotEmpty() } || behaviour == StructureWord.Age.id
        return Suggestion(
            page = page,
            says = when {
                behaviour != null -> "structure — runs as ${behaviour.path}"
                word == null -> ""
                else -> (offers.map(::offerSaid) + means.map(::said)).joinToString(", ")
            },
            authored = word != null && word.id !in words.derived,
            firmness = if (takes) "takes" else "",
            targets = word?.let(usage::partsReached).orEmpty().sortedBy { it.ordinal }.joinToString(" ") { it.page },
            isMaterial = offers.any { it.concept == CoreConcepts.BLOCK },
            required = certain.map { Claim(offerSaid(it)) } + means.map { Claim(said(it)) },
            requested = byChance.map { Claim("${offerSaid(it)} ${Sections.percent(it.chance)}") },
            closes = closes || behaviour == StructureWord.Age.id,
        )
    }

    private fun offerSaid(offer: Offer): String {
        val audience = when (val said = offer.audience) {
            Audience.Anyone -> ""
            Audience.TheAge -> " for the Age"
            is Audience.Part -> " for the ${said.aspect.page}"
        }
        val what = if (offer.isAReading) "a reading of ${offer.reading.joinToString(" ", transform = ::said)}" else
            "${offer.concept.path} ${Sections.valueSaid(offer.value)}".trim()
        return what + audience
    }

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
        is Effect.Bans -> "never ${effect.block}"
        is Effect.LinksIn -> "a link arrives in ${effect.anchor.aspect.page}"
    }

    private companion object {
        /** Whether a row reads as laid does not depend on the draw, so any seed asks it. */
        const val SEED = 0L
        const val AGE = "age"
    }
}
