package co.voik.agesandtheart.preview.authoring.words

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.reading.Placement
import co.voik.agesandtheart.age.reading.Reader
import co.voik.agesandtheart.age.reading.Reading
import co.voik.agesandtheart.age.reading.StructureWord
import co.voik.agesandtheart.age.reading.Usage
import co.voik.agesandtheart.age.reading.Writing
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.Landing
import co.voik.agesandtheart.age.resolution.Resolver
import co.voik.agesandtheart.age.resolution.cameToNothingAmong
import co.voik.agesandtheart.age.words.Acceptance
import co.voik.agesandtheart.age.words.Offer
import co.voik.agesandtheart.age.words.Outcome
import co.voik.agesandtheart.age.words.Sense
import co.voik.agesandtheart.age.words.Value
import co.voik.agesandtheart.age.words.Word
import co.voik.agesandtheart.age.words.Words
import co.voik.agesandtheart.location
import co.voik.agesandtheart.preview.authoring.words.ui.AcceptanceAsking
import co.voik.agesandtheart.preview.authoring.words.ui.Sections
import java.io.File

/**
 * The acceptance tables, read for one word: every thing, part's page and Age that takes each of its offers, what it
 * makes there, and whether that holds in an Age written with it; and, where the word takes offers itself, the words
 * offering what each of its acceptances takes. An offer nothing takes and an acceptance nothing offers to are gaps.
 *
 * Whether a taking holds is asked of the reader and the resolver: the word, with that offer made certain, is laid before
 * one of the words taking it, and the book is resolved as laid and with the offer's effects taken out. [standIn] is read
 * in place of the saved word of its name, or as a word of its own.
 */
class Pairings(private val corpus: WordCorpus, private val standIn: Word? = null) {

    private val words: Words by lazy { corpus.with(listOfNotNull(standIn)) }

    private val usage: Usage by lazy { Usage(words, corpus.membership) }

    /** Whether what a taker makes of an offer holds once the Age is written. */
    enum class Hold(val said: String, val isHollow: Boolean) {
        HOLDS("holds", isHollow = false),

        /** Passed on to be made something of further along: folded, renamed, scaled, or dropped on purpose. */
        PASSED_ON("passed on", isHollow = false),

        /** A lean or a bar toward what no member of the part carries: nothing there for it to tip. */
        NOTHING_CARRIES_IT("nothing there carries it", isHollow = false),

        /** Laid right before the taker, the reader never placed it there. */
        NEVER_PLACED("never placed when laid before it", isHollow = false),

        /** Taken, but the acceptance makes no effect of this value. */
        MAKES_NOTHING("hollow: makes nothing of this value", isHollow = true),

        /** Placed in every book, and came to nothing in each: overruled, or honoured by nothing. */
        CAME_TO_NOTHING("hollow: came to nothing", isHollow = true),

        /** Held, and the part it landed on came out as it would have without it. */
        CHANGED_NOTHING("hollow: changed nothing", isHollow = true),
    }

    /** The words that take an offer alike — a part's page and its members, say — what they make of it, and whether it holds. */
    data class Takers(
        val who: String,
        val isTheAge: Boolean,
        val part: Aspect?,
        val words: List<Word>,
        val acceptance: Acceptance,
        val makes: String,
        val hold: Hold?,
    )

    /** One offer of a word's, and everything that takes it. */
    data class OfferTaken(val sense: Int, val offer: Offer, val takers: List<Takers>)

    /** One acceptance of a word's, and every other word offering something it takes. */
    data class AcceptanceOffered(val sense: Int, val acceptance: Acceptance, val offeredBy: List<Word>)

    data class Table(val word: Word, val offers: List<OfferTaken>, val accepts: List<AcceptanceOffered>) {
        val untaken: List<OfferTaken> get() = offers.filter { it.takers.isEmpty() }
        val unoffered: List<AcceptanceOffered> get() = accepts.filter { it.offeredBy.isEmpty() }
        val hollow: List<Pair<OfferTaken, Takers>>
            get() = offers.flatMap { offer -> offer.takers.filter { it.hold?.isHollow == true }.map { offer to it } }
    }

    /** [word]'s table, judging whether each taking holds where [judging] says to, and leaving the rest unjudged. */
    fun of(word: Word, judging: (Takers) -> Boolean = { true }): Table {
        val offers = word.senses.withIndex().flatMap { (at, sense) -> sense.offers.map { at to it } }
        val probes = offers.mapIndexed { index, (at, offer) -> probeOf(word.senses[at], offer, index) }
        val probed by lazy { Probed(corpus.with(listOfNotNull(standIn) + probes.map(Probe::word))) }
        val taken = offers.withIndex().toList().parallelStream().map { (index, sensedOffer) ->
            val (at, offer) = sensedOffer
            val groups = takersOf(word, offer).map { takers ->
                val shouldJudge = takers.hold == null && judging(takers)
                if (shouldJudge) takers.copy(hold = holdOf(probes[index], takers, probed)) else takers
            }
            OfferTaken(at, offer, groups)
        }.toList()
        return Table(word, taken, acceptancesOf(word))
    }

    /** What every word of ours that ends a phrase makes hollow on the part of the world it speaks for — always a fault. */
    fun hollowOnTheParts(): List<String> = corpus.written.filter { it.defaultSense?.behaviour == null }.flatMap { word ->
        of(word, judging = ::includesThePartsPage).hollow.map { (offer, takers) ->
            "${word.name} → ${takers.who}: ${Sections.offerSpelled(offer.offer)} — ${takers.hold?.said}"
        }
    }

    private fun includesThePartsPage(takers: Takers): Boolean {
        val page = takers.part?.let(words::partPage) ?: return false
        return takers.words.any { it.id == page.id }
    }

    // -- who takes an offer -----------------------------------------------------------------------------

    private data class Taking(val word: Word, val sense: Sense, val acceptance: Acceptance, val made: List<Effect>) {
        val isTheAge: Boolean get() = sense.behaviour == StructureWord.Age.id
    }

    private val theAge: List<Pair<Word, Sense>> by lazy {
        words.all.flatMap { word -> word.senses.filter { it.behaviour == StructureWord.Age.id && it.acceptances.isNotEmpty() }.map { word to it } }
    }

    /** Everything taking [offer], folded where the takers are of one part and make the same of it. */
    private fun takersOf(word: Word, offer: Offer): List<Takers> {
        val value = usage.filled(offer)
        fun made(sense: Sense, acceptance: Acceptance) =
            (acceptance.outcome as? Outcome.Becomes)?.effects.orEmpty().mapNotNull { it.applied(value, sense.speaksFor) }
        val byThings = usage.takers.filter { it.word.id != word.id }.mapNotNull { taker ->
            usage.acceptanceTaking(taker.sense, offer)?.let { Taking(taker.word, taker.sense, it, made(taker.sense, it)) }
        }
        fun theAgeWouldTake(acceptance: Acceptance) = offer.chanceFromWholeAge * acceptance.chanceFromWholeAge > Offer.NEVER
        val byTheAge = theAge.mapNotNull { (age, sense) ->
            usage.acceptanceTaking(sense, offer, isTheAge = true)?.takeIf(::theAgeWouldTake)?.let { Taking(age, sense, it, made(sense, it)) }
        }
        val alike = (byThings + byTheAge).groupBy { listOf(it.sense.speaksFor, it.isTheAge, it.acceptance, it.made) }.values
        return alike.map(::takersOf).sortedWith(compareBy({ it.part?.ordinal ?: Int.MAX_VALUE }, { it.who }))
    }

    private fun takersOf(alike: List<Taking>): Takers {
        val first = alike.first()
        val outcome = first.acceptance.outcome
        val hold = when {
            outcome !is Outcome.Becomes -> Hold.PASSED_ON
            first.made.isEmpty() -> Hold.MAKES_NOTHING
            first.made.all(::tipsNothing) -> Hold.NOTHING_CARRIES_IT
            else -> null
        }
        val makes = when (outcome) {
            is Outcome.Becomes -> first.made.joinToString("; ", transform = Sections::effectSpelled).ifEmpty { "nothing" }
            else -> AcceptanceAsking.outcomeSaid(outcome)
        }
        val takingWords = alike.map(Taking::word).distinctBy(Word::id).sortedWith(compareBy({ it.id in words.derived }, { it.name }))
        return Takers(whoOf(first, takingWords), first.isTheAge, first.sense.speaksFor, takingWords, first.acceptance, makes, hold)
    }

    /** Whether [effect] leans or bars toward members its part has none of. */
    private fun tipsNothing(effect: Effect): Boolean = when (effect) {
        is Effect.Leans -> nothingCarries(effect.aspect, effect.weights.keys)
        is Effect.Restricts -> nothingCarries(effect.aspect, effect.bars.keys.map { "$TAG_MARK$it" })
        else -> false
    }

    private fun nothingCarries(aspect: Aspect, named: Collection<String>): Boolean {
        val members = corpus.vocabulary.candidatesFor(aspect)
        fun isCarried(key: String): Boolean {
            val tag = key.removePrefix(TAG_MARK).takeIf { key.startsWith(TAG_MARK) } ?: return members.any { it.key == key }
            return members.any { tag in corpus.vocabulary.tagsOf(it) }
        }
        return named.none(::isCarried)
    }

    /** The takers named: the Age, a part's page and how many of its members beside it, or the first few words. */
    private fun whoOf(first: Taking, takingWords: List<Word>): String {
        if (first.isTheAge) return THE_AGE
        val page = first.sense.speaksFor?.let(words::partPage)
        val others = takingWords.filterNot { it.id == page?.id }
        val pageTakesIt = page != null && takingWords.any { it.id == page.id }
        return when {
            pageTakesIt && others.isEmpty() -> "${page.name}"
            pageTakesIt -> "${page.name} and ${others.size} of its members"
            else -> named(takingWords)
        }
    }

    private fun named(takingWords: List<Word>): String {
        val shown = takingWords.take(NAMES_SHOWN).joinToString(" ") { it.name }
        val more = takingWords.size - NAMES_SHOWN
        return if (more > 0) "$shown and $more more" else shown
    }

    // -- what a word takes ------------------------------------------------------------------------------

    private fun acceptancesOf(word: Word): List<AcceptanceOffered> = word.senses.withIndex().flatMap { (at, sense) ->
        val takes = sense.behaviour == null && sense.endsAPhrase
        if (!takes) return@flatMap emptyList()
        sense.acceptances.map { acceptance ->
            fun offersIt(other: Word) = other.senses.any { theirs -> theirs.offers.any { usage.acceptanceTaking(sense, it) == acceptance } }
            val offering = words.all.filter { it.id != word.id && offersIt(it) }
            AcceptanceOffered(at, acceptance, offering.sortedWith(compareBy({ it.id in words.derived }, { it.name })))
        }
    }

    // -- whether it holds -------------------------------------------------------------------------------

    /**
     * The word, as [sense] alone with [offer] made certain, and the offer as it is laid. An offer that never applies said
     * of a thing stays so, made certain from the whole Age, where it does apply.
     */
    private data class Probe(val word: Word, val offer: Offer) {
        val landsOnlyFromTheAge: Boolean get() = offer.chance == Offer.NEVER
    }

    private fun probeOf(sense: Sense, offer: Offer, index: Int): Probe {
        val fromTheWholeAge = offer.ownChanceFromWholeAge?.let { if (it > Offer.NEVER) Offer.CERTAIN else it }
        val chance = if (offer.chance > Offer.NEVER) Offer.CERTAIN else Offer.NEVER
        val certain = offer.copy(chance = chance, ownChanceFromWholeAge = fromTheWholeAge)
        val laid = sense.copy(offers = sense.offers.map { if (it == offer) certain else it }, isDefault = true)
        return Probe(Word("$PROBE$index".location(), listOf(laid)), certain)
    }

    /** The words with the probes among them, and the books written in them. */
    private inner class Probed(val words: Words) {
        val writing: Writing = corpus.writing(words)

        fun read(laid: List<String>, seed: Long): Reading = Reader.read(words, laid, corpus.membership, seed)
    }

    /** What one book did with the offer: never placed it, lost it, kept it to no end, or changed the Age. */
    private enum class InOneBook { NOT_PLACED, CAME_TO_NOTHING, CHANGED_NOTHING, CHANGED }

    /** Whether the offer holds where [takers] take it: the probe laid before one of them, in several books. */
    private fun holdOf(probe: Probe, takers: Takers, probed: Probed): Hold {
        val viaTheAge = takers.isTheAge || probe.landsOnlyFromTheAge
        val laid = if (viaTheAge) listOf(probe.word.name, AGE) else listOf(probe.word.name, takers.words.first().name, AGE)
        val books = SEEDS.map { seed -> inOneBook(probe.offer, probed.read(laid, seed), viaTheAge, probed.writing, seed) }
        return when {
            books.all { it == InOneBook.NOT_PLACED } -> Hold.NEVER_PLACED
            InOneBook.CHANGED in books -> Hold.HOLDS
            InOneBook.CHANGED_NOTHING in books -> Hold.CHANGED_NOTHING
            else -> Hold.CAME_TO_NOTHING
        }
    }

    /**
     * The book [reading] makes resolved twice at [seed], as laid and with what [offer]'s placement landed taken out of
     * its landings, held to one draw: whatever differs is the offer's doing.
     */
    private fun inOneBook(offer: Offer, reading: Reading, viaTheAge: Boolean, writing: Writing, seed: Long): InOneBook {
        fun isFromTheProbe(placement: Placement) = placement.from == PROBE_AT && (viaTheAge || placement.at == TAKER_AT)
        // The reader may place an offer filled out, a concept of parts with its defaults given, so the certain one of
        // its concept and audience stands for it where none is the same offer exactly.
        fun isLikeTheOffer(placement: Placement) = placement.offer.concept == offer.concept && placement.offer.audience == offer.audience &&
            placement.offer.reading == offer.reading && placement.offer.chance == offer.chance
        val fromTheProbe = reading.placements.filter(::isFromTheProbe)
        val placement = fromTheProbe.firstOrNull { it.offer == offer } ?: fromTheProbe.firstOrNull(::isLikeTheOffer) ?: return InOneBook.NOT_PLACED
        val book = writing.bookOf(reading, seed)
        val landed = book.landings.filter { it.page == PROBE_AT && it.saidOf == placement.at }
        val itsEffects = placement.effects.flatMap(::asLanded).toSet()
        // A landing left saying nothing would still speak to its part, so it goes; the draw is held to the book as laid.
        fun withoutIt(landing: Landing): Landing? {
            if (landing !in landed) return landing
            val rest = landing.effects.filterNot(itsEffects::contains)
            return if (rest.isEmpty()) null else landing.copy(effects = rest)
        }
        val without = book.copy(landings = book.landings.mapNotNull(::withoutIt))
        val asLaid = Resolver.resolve(corpus.vocabulary, book, seed)
        val withoutIt = Resolver.resolve(corpus.vocabulary, without, seed, draw = Resolver.drawOf(book, seed))
        val changed = asLaid.composition != withoutIt.composition || asLaid.template != withoutIt.template
        fun held(landing: Landing): Boolean {
            val lost = asLaid.lostDecisions[landing.at].orEmpty()
            val itsOwn = landing.effects.filter(itsEffects::contains)
            return landing.at !in asLaid.cameToNothing && itsOwn.any { !it.cameToNothingAmong(lost) }
        }
        return when {
            changed -> InOneBook.CHANGED
            landed.none(::held) -> InOneBook.CAME_TO_NOTHING
            else -> InOneBook.CHANGED_NOTHING
        }
    }

    /** [effect] as a landing carries it: a lean on every part is a lean on each. */
    private fun asLanded(effect: Effect): List<Effect> =
        if (effect is Effect.LeansEverywhere) Aspect.entries.map { Effect.Leans(it, effect.weights) } else listOf(effect)

    companion object {
        /** Where a word's report is written. */
        fun reportFor(word: String): File = File(".authoring/pairings/$word.md")

        /** [table] as a report: each offer and what takes it, then what the word takes and who offers it. */
        fun report(table: Table): String = buildString {
            val name = table.word.name
            appendLine("# What `$name` offers, and what takes it\n")
            appendLine("Each offer, and every thing, part's page and Age that takes it, with what it makes there and whether")
            appendLine("that holds in an Age written with it. Hollow is always a fault; an offer nothing takes is a gap.\n")
            for ((at, offers) in table.offers.groupBy { it.sense }) {
                appendLine("## Sense ${at + 1}\n")
                for (offer in offers) {
                    val chance = if (offer.offer.chance == Offer.CERTAIN) "" else " (${Sections.percent(offer.offer.chance)})"
                    appendLine("### ${Sections.offerSpelled(offer.offer)}$chance\n")
                    if (offer.takers.isEmpty()) appendLine("- **nothing takes it** — it will always dangle")
                    for (takers in offer.takers) {
                        val hold = takers.hold?.let { if (it.isHollow) " — **${it.said}**" else " — ${it.said}" }.orEmpty()
                        appendLine("- `${takers.who}`: ${takers.makes}$hold")
                    }
                    appendLine()
                }
            }
            if (table.accepts.isNotEmpty()) {
                appendLine("## What `$name` takes\n")
                for (accepted in table.accepts) {
                    val acceptance = accepted.acceptance
                    val only = AcceptanceAsking.filterSaid(acceptance.only).takeIf { it != ANY }?.let { " only $it" }.orEmpty()
                    val offeredBy = if (accepted.offeredBy.isEmpty()) "**offered by nothing**" else
                        "offered by ${accepted.offeredBy.size}: ${accepted.offeredBy.take(WORDS_LISTED).joinToString(" ") { it.name }}" +
                            if (accepted.offeredBy.size > WORDS_LISTED) " …" else ""
                    appendLine("- ${acceptance.concept.path}$only — $offeredBy")
                }
            }
        }

        private const val THE_AGE = "the Age"
        private const val AGE = "age"

        private const val PROBE = "zzz_pairing_probe_"
        private const val PROBE_AT = 0
        private const val TAKER_AT = 1

        /** Enough books that an offer only shifting what is drawn shows in one of them. */
        private val SEEDS = 1L..8L

        private const val NAMES_SHOWN = 4
        private const val WORDS_LISTED = 12
        private const val ANY = "any"
        private const val TAG_MARK = "#"
    }
}
