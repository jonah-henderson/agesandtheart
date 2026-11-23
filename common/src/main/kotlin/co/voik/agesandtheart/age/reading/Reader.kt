package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.words.Acceptance
import co.voik.agesandtheart.age.words.Audience
import co.voik.agesandtheart.age.words.CoreConcepts
import co.voik.agesandtheart.age.words.EffectTemplate
import co.voik.agesandtheart.age.words.JoinedPair
import co.voik.agesandtheart.age.words.Offer
import co.voik.agesandtheart.age.words.Outcome
import co.voik.agesandtheart.age.words.Sense
import co.voik.agesandtheart.age.words.Value
import co.voik.agesandtheart.age.words.ValueType
import co.voik.agesandtheart.age.words.Words
import co.voik.agesandtheart.math.mix64
import kotlin.random.Random

/**
 * Reads a row of pages (`notes/the-word-rework.md`): offers travel right over the terms the structure words arranged,
 * a describing word takes what it accepts and carries the rest on, and a thing either passes everything on to the
 * word after it, where that word takes what it offers, or closes a phrase — turning what reached it into effects and
 * dropping the rest. `age` hands what reaches it to every part that takes it. A page that placed nothing dangles.
 * Pure: the same words, pages, answers and seed read the same way every time.
 */
object Reader {
    fun read(words: Words, pages: List<String>, membership: Membership, seed: Long): Reading {
        val arrangement = Arrangement.of(pages, words)
        return Pass(words, pages, arrangement, membership, seed).read()
    }
}

/**
 * An offer on its way, with whatever a describing word folded into it, and the pages of the describing words that
 * reshaped it on the way — `setting` in `west setting sun` — which are placed where it is.
 */
private data class Travelling(
    val page: Int,
    val offer: Offer,
    val carrying: List<Travelling> = emptyList(),
    val joined: Int? = null,
    val reshapedBy: List<Int> = emptyList(),
)

private class Pass(
    private val words: Words,
    private val pages: List<String>,
    private val arrangement: Arrangement,
    private val membership: Membership,
    private val seed: Long,
) {
    private val matching = Matching(membership, words.concepts)
    private val fates = arrangement.fates.toMutableMap()
    private val senses = mutableMapOf<Int, Int>()
    private val phrases = mutableListOf<Phrase>()
    private val handedOut = mutableListOf<Placement>()
    private val placed = mutableSetOf<Int>()
    private var travelling = listOf<Travelling>()

    fun read(): Reading {
        val terms = arrangement.terms
        var passedOnFrom: Term? = null
        for ((index, term) in terms.withIndex()) {
            val reading = passedOnFrom?.let(term::saidAs) ?: term
            passedOnFrom = if (term.isAge) {
                handOut(term.pages.single())
                null
            } else {
                reading.takeIf { readTerm(reading, terms.getOrNull(index + 1)) }
            }
        }
        banTheAgeBefore(terms)
        val polarities = terms.flatMap { term -> term.pages.map { it to term.polarity } }.toMap()
        return Reading(
            pages = pages.indices.map { at ->
                PageRead(at, pages[at], senses[at], fateOf(at), polarities[at] ?: Polarity.ASSERTED)
            },
            phrases = phrases.toList(),
            handedOut = handedOut.toList(),
            arrival = arrangement.arrival,
        )
    }

    /**
     * A ban still travelling when the book ends is given to the last `age` before it: `age no dirt` is an Age with no
     * dirt, a ban being said of the whole Age wherever it stands. Nothing else after the last `age` is.
     */
    private fun banTheAgeBefore(terms: List<Term>) {
        val bans = travelling.filter { it.offer.concept == CoreConcepts.BAN }
        val lastAge = terms.lastOrNull(Term::isAge) ?: return
        if (bans.isEmpty()) return
        travelling = bans
        handOut(lastAge.pages.single())
    }

    private fun fateOf(at: Int): Fate = when (val fate = fates[at]) {
        null -> if (at in placed) Fate.Placed else Fate.Dangles
        is Fate.PassedOn -> if (at in placed) fate else Fate.Dangles
        else -> fate
    }

    /** Reads [term], and says whether its things passed their pages on to [next], which then closes the phrase. */
    private fun readTerm(term: Term, next: Term?): Boolean {
        val chosen = term.pages.map { at -> at to senseFor(at, next) }
        val (things, describing) = chosen.partition { (_, sense) -> sense.endsAPhrase }
        if (things.isEmpty()) {
            describe(term, describing)
            return false
        }
        val offered = things.flatMap { (at, sense) -> offersOf(at, sense, term) }
        val passesOn = next != null && !next.isAge && offered.any { takenByAnyOf(next, it.offer) }
        if (passesOn && next != null) {
            things.forEach { (at, _) -> fates[at] = Fate.PassedOn(next.pages.first()) }
            travelling = travelling + offered
            return true
        }
        val reaching = travelling
        val goingOn = mutableListOf<Travelling>()
        for ((at, sense) in things) goingOn += close(at, sense, term, reaching)
        travelling = goingOn.distinct()
        return false
    }

    /** Describing words, each taking a copy of what reached them; what none took travels on beside their offers. */
    private fun describe(term: Term, describing: List<Pair<Int, Sense>>) {
        var untaken = travelling
        val onward = mutableListOf<Travelling>()
        for ((at, sense) in describing) {
            val folded = mutableListOf<Travelling>()
            for (reaching in travelling) {
                val acceptance = matching.acceptanceFor(sense, reaching.offer) ?: continue
                if (!matching.valueFits(sense, acceptance, reaching.offer)) continue
                untaken = untaken - reaching
                // What is folded or offered on is placed only where what carries it lands.
                if (acceptance.outcome is Outcome.Becomes || acceptance.outcome == Outcome.Drops) placed += reaching.page
                when (val outcome = acceptance.outcome) {
                    Outcome.Folds -> folded += reaching
                    is Outcome.Renames -> onward += reaching.copy(offer = reaching.offer.copy(concept = outcome.concept), reshapedBy = reaching.reshapedBy + at)
                    is Outcome.Scales -> onward += reaching.copy(
                        offer = reaching.offer.copy(value = scaled(reaching.offer.value, outcome.factor)),
                        reshapedBy = reaching.reshapedBy + at,
                    )
                    is Outcome.Becomes, Outcome.Drops -> Unit
                }
                if (sense.consumesWholePage) untaken = untaken.filter { it.page != reaching.page }
            }
            onward += offersOf(at, sense, term).map { it.copy(carrying = folded) }
        }
        travelling = untaken + onward
    }

    /**
     * A thing closing a phrase: what reaches it it takes where it accepts it, a member it takes naming the phrase in place
     * of its own; what it does not take is dropped, but for an offer meant for someone else from a page it took nothing
     * of, which travels on. Returns what travels on.
     */
    private fun close(at: Int, sense: Sense, term: Term, reaching: List<Travelling>): List<Travelling> {
        fates[at] = Fate.Closed
        placed += at
        val taken = gathered(sense, reaching).flatMap { take(at, sense, it) }
        // A negated or `only` thing is struck or kept as described, and only a description of member kinds can say which.
        val isNarrowed = term.polarity != Polarity.ASSERTED
        // `only teeming villages` keeps its villages teeming: an amount stacks on what `only` keeps, never on what `no` strikes.
        fun isAnAmountKept(placement: Placement) =
            term.polarity == Polarity.ONLY && placement.effects.isNotEmpty() && placement.effects.all { it is Effect.Abundance }
        fun narrows(placement: Placement) = placement.offer.concept in CoreConcepts.NARROWING || isAnAmountKept(placement)
        val placements = if (isNarrowed) taken.filter(::narrows) else taken
        placed += placements.flatMap { listOf(it.from) + it.carrying }
        val tookFrom = placements.flatMap { listOf(it.from) + it.carrying }.toSet()
        val tookAMember = placements.any { it.offer.concept == CoreConcepts.MEMBER }
        // What is said of a thing replaces what it says of itself: members as members, and a setting as the same setting.
        val named = if (tookAMember) sense.means.filterNot { namesMembersOf(it, sense.speaksFor) } else sense.means
        val saidOfIt = placements.flatMap(Placement::effects).mapNotNull { (it as? Effect.Sets)?.parameter }.toSet()
        val own = named.map { EffectTemplate.fencedTo(it, sense.speaksFor) }.filterNot { (it as? Effect.Sets)?.parameter in saidOfIt }
        phrases += Phrase(at, sense.speaksFor, own, placements, term.polarity, term.sitedIn, term.everywhere, term.joined)
        fun meantForSomeoneElse(it: Travelling) = it.offer.audience != Audience.Anyone && it.page !in tookFrom
        return reaching.filter { travelling -> placements.none { it.from == travelling.page && it.offer == travelling.offer } }
            .filter(::meantForSomeoneElse)
    }

    /** What [sense] at [at] makes of [reaching] and what it carries: none where it does not take it. */
    private fun take(at: Int, sense: Sense, reaching: Travelling): List<Placement> {
        val acceptance = matching.acceptanceFor(sense, reaching.offer) ?: return emptyList()
        if (!matching.valueFits(sense, acceptance, reaching.offer)) return emptyList()
        val carried = reaching.carrying.flatMap { take(at, sense, it) }
        return listOf(placementOf(at, sense, sense.speaksFor, acceptance, reaching)) + carried
    }

    /** The Age: what it accepts itself — a template, a ban — and the rest to every part that takes it. */
    private fun handOut(at: Int) {
        val age = words.word(pages[at])?.defaultSense
        val toParts = mutableListOf<Placement>()
        for (reaching in travelling.flatMap { listOf(it) + it.carrying }) {
            val ownAcceptance = age?.let { matching.acceptanceFor(it, reaching.offer, isTheAge = true) }
            if (ownAcceptance != null) {
                handedOut += placementOf(at, null, null, ownAcceptance, reaching)
                placed += reaching.page
                continue
            }
            for (part in Aspect.entries) {
                val page = words.partPage(part)?.defaultSense ?: continue
                val acceptance = matching.acceptanceFor(page, reaching.offer, isTheAge = true) ?: continue
                val chance = reaching.offer.chanceFromWholeAge * willingness(acceptance, reaching.offer, part)
                if (chance <= 0.0 || !matching.valueFits(page, acceptance, reaching.offer)) continue
                toParts += placementOf(at, page, part, acceptance, reaching).copy(chance = chance)
            }
        }
        val kept = withoutWhatAnAimedOfferReplaces(toParts)
        handedOut += kept
        placed += kept.map(Placement::from)
        travelling = emptyList()
    }

    /**
     * Where the whole Age hands a part a page's offer aimed at it by name, the page's offer of that concept to anyone does
     * not reach that part too: `looming age` presses the clouds down rather than raising them with the land. A concept a
     * part holds several values of is exempt, since those add up rather than replace one another.
     */
    private fun withoutWhatAnAimedOfferReplaces(placements: List<Placement>): List<Placement> {
        fun conceptAt(placement: Placement) = Triple(placement.from, placement.part, placement.offer.concept)
        fun holdsOneValue(placement: Placement): Boolean {
            val concept = words.concepts[placement.offer.concept]
            return concept?.joined != JoinedPair.DIVIDE && concept?.values != ValueType.WEIGHTS
        }
        val aimedAt = placements.filter { it.part != null && it.offer.audience == Audience.Part(it.part) }.map(::conceptAt).toSet()
        fun isReplaced(placement: Placement) =
            placement.offer.audience == Audience.Anyone && holdsOneValue(placement) && conceptAt(placement) in aimedAt
        return placements.filterNot(::isReplaced)
    }

    /**
     * How readily a part takes what the whole Age hands it: its own reluctance for what reaches everything, and none for
     * a meaning the word made for it by name — `frozen`'s pale halo comes on frozen's chance, not the rainbow's.
     */
    private fun willingness(acceptance: Acceptance, offer: Offer, part: Aspect): Double =
        if (offer.audience == Audience.Part(part)) Offer.CERTAIN else acceptance.chanceFromWholeAge

    private fun placementOf(at: Int, sense: Sense?, part: Aspect?, acceptance: Acceptance, reaching: Travelling): Placement {
        val offer = reaching.offer.copy(value = matching.filled(reaching.offer))
        val effects = matching.effectsOf(sense, part, acceptance, reaching.offer)
        val alongWithIt = reaching.carrying.map(Travelling::page) + reaching.reshapedBy
        return Placement(reaching.page, offer, at, part, effects, alongWithIt, reaching.joined)
    }

    /**
     * What reaches a thing, with the parts several words give one concept made of parts gathered into one offer where
     * none gives a part twice — `ancient porous landmass` is porous at a great amount — the later pages placed with it.
     */
    private fun gathered(sense: Sense, reaching: List<Travelling>): List<Travelling> {
        val gathering = mutableListOf<Travelling>()
        for (travelling in reaching) {
            val parts = travelling.offer.value as? Value.Parts
            val joinsAt = if (parts == null || matching.acceptanceFor(sense, travelling.offer) == null) -1 else gathering.indexOfFirst { earlier ->
                val earlierParts = earlier.offer.value as? Value.Parts
                val isOfOneConcept = earlier.offer.concept == travelling.offer.concept && matching.acceptanceFor(sense, earlier.offer) != null
                isOfOneConcept && earlierParts?.with(parts) != null
            }
            if (joinsAt < 0) {
                gathering += travelling
                continue
            }
            val earlier = gathering[joinsAt]
            val together = (earlier.offer.value as? Value.Parts)?.with(parts ?: continue) ?: continue
            gathering[joinsAt] = earlier.copy(offer = earlier.offer.copy(value = together), reshapedBy = earlier.reshapedBy + travelling.page + travelling.reshapedBy)
        }
        return gathering
    }

    /**
     * Whether any word of [term] has a sense that takes [offer] — the lookahead. It reads the concept and the part a member
     * belongs to, never a group's filter: `sheep fliers` passes the sheep on, to be refused there.
     */
    private fun takenByAnyOf(term: Term, offer: Offer): Boolean = term.pages.any { at ->
        words.word(pages[at])?.senses.orEmpty().any { matching.acceptanceFor(it, offer) != null && !matching.isAMemberOfAnotherPart(it, offer) }
    }

    /**
     * Whether any word of [term] has a sense that would take [offer] and its value: what choosing a sense asks of the term
     * after, where the lookahead that passes an offer on does not look at the value.
     */
    private fun fitsAnyOf(term: Term, offer: Offer): Boolean = term.pages.any { at ->
        words.word(pages[at])?.senses.orEmpty().any { sense ->
            val acceptance = matching.acceptanceFor(sense, offer)
            acceptance != null && matching.valueFits(sense, acceptance, offer)
        }
    }

    /** What the page at [at] offers in [sense]: a block made a ban for the Age where `no` stood before it. */
    private fun offersOf(at: Int, sense: Sense, term: Term): List<Travelling> = sense.offers.map { offer ->
        val banned = term.polarity == Polarity.EXCEPT && !sense.endsAPhrase && offer.concept == CoreConcepts.BLOCK
        val offered = if (banned) offer.copy(concept = CoreConcepts.BAN, audience = Audience.TheAge) else offer
        Travelling(at, offered, joined = term.joined)
    }

    /**
     * The sense [at] is read in: the one that places most of the pages reaching it, by taking or letting through to a
     * term after that would take it; then the one marked default; then whether the term after takes what it offers; then
     * the seed's. A describing sense nothing after it could take is never chosen: one at the end of the book, or before
     * an `age` that may not be given what it offers. So `teeming large beds piles` is the furniture, the default, the
     * piles taking what it lets through; `clay beds` the feature, which alone takes a block; and `beds age` the feature,
     * since the furniture is never said of the whole Age.
     */
    private fun senseFor(at: Int, next: Term?): Sense {
        val all = words.word(pages[at])?.senses.orEmpty()
        val chosen = if (all.size == 1) 0 else {
            val nextReads = next != null && !next.isAge
            fun passes(sense: Sense) = nextReads && sense.offers.any { fitsAnyOf(requireNotNull(next), it) }
            fun hasAReader(sense: Sense): Boolean {
                val ageMayBeGivenIt = next?.isAge == true && sense.offers.any { it.chanceFromWholeAge > Offer.NEVER }
                return sense.endsAPhrase || nextReads || ageMayBeGivenIt
            }
            fun placed(sense: Sense): Int {
                val letsThrough = nextReads && (!sense.endsAPhrase || passes(sense))
                fun placesIt(offer: Offer): Boolean {
                    val takesIt = matching.acceptanceFor(sense, offer) != null
                    val nextTakesIt = letsThrough && fitsAnyOf(requireNotNull(next), offer)
                    return takesIt || nextTakesIt
                }
                return travelling.filter { placesIt(it.offer) }.map { it.page }.distinct().size
            }
            val readable = all.indices.filter { hasAReader(all[it]) }.ifEmpty { all.indices.toList() }
            val mostPlaced = readable.maxOf { placed(all[it]) }
            val placingMost = readable.filter { placed(all[it]) == mostPlaced }
            val passing = placingMost.filter { passes(all[it]) }.ifEmpty { placingMost }
            placingMost.firstOrNull { all[it].isDefault }
                ?: passing[Random(mix64(seed xor at.toLong())).nextInt(passing.size)]
        }
        if (all.size > 1) senses[at] = chosen
        return all[chosen]
    }

    private fun scaled(value: Value, factor: Double): Value = if (value is Value.Amount) Value.Amount(value.multiple * factor) else value

    private fun namesMembersOf(effect: Effect, part: Aspect?): Boolean = when (effect) {
        is Effect.Chooses -> effect.aspect == part
        is Effect.Admits -> effect.aspect == part
        is Effect.Restricts -> effect.aspect == part
        else -> false
    }
}
