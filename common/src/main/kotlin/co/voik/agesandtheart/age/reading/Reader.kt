package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.words.Acceptance
import co.voik.agesandtheart.age.words.Audience
import co.voik.agesandtheart.age.words.CoreConcepts
import co.voik.agesandtheart.age.words.EffectTemplate
import co.voik.agesandtheart.age.words.Offer
import co.voik.agesandtheart.age.words.Outcome
import co.voik.agesandtheart.age.words.Sense
import co.voik.agesandtheart.age.words.Value
import co.voik.agesandtheart.age.words.ValueFilter
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

/** An offer on its way, with whatever a describing word folded into it. */
private data class Travelling(val page: Int, val offer: Offer, val carrying: List<Travelling> = emptyList(), val joined: Int? = null)

private class Pass(
    private val words: Words,
    private val pages: List<String>,
    private val arrangement: Arrangement,
    private val membership: Membership,
    private val seed: Long,
) {
    private val fates = arrangement.fates.toMutableMap()
    private val senses = mutableMapOf<Int, Int>()
    private val phrases = mutableListOf<Phrase>()
    private val handedOut = mutableListOf<Placement>()
    private val placed = mutableSetOf<Int>()
    private var travelling = listOf<Travelling>()

    fun read(): Reading {
        val terms = arrangement.terms
        for ((index, term) in terms.withIndex()) {
            if (term.isAge) handOut(term.pages.single()) else readTerm(term, terms.getOrNull(index + 1))
        }
        return Reading(
            pages = pages.indices.map { at -> PageRead(at, pages[at], senses[at], fateOf(at)) },
            phrases = phrases.toList(),
            handedOut = handedOut.toList(),
            arrival = arrangement.arrival,
        )
    }

    private fun fateOf(at: Int): Fate = when (val fate = fates[at]) {
        null -> if (at in placed) Fate.Placed else Fate.Dangles
        is Fate.PassedOn -> if (at in placed) fate else Fate.Dangles
        else -> fate
    }

    private fun readTerm(term: Term, next: Term?) {
        val chosen = term.pages.map { at -> at to senseFor(at, next) }
        val (things, describing) = chosen.partition { (_, sense) -> sense.endsAPhrase }
        if (things.isEmpty()) return describe(term, describing)
        val offered = things.flatMap { (at, sense) -> offersOf(at, sense, term) }
        val passesOn = next != null && !next.isAge && offered.any { takenByAnyOf(next, it.offer) }
        if (passesOn && next != null) {
            things.forEach { (at, _) -> fates[at] = Fate.PassedOn(next.pages.first()) }
            travelling = travelling + offered
            return
        }
        val reaching = travelling
        val goingOn = mutableListOf<Travelling>()
        for ((at, sense) in things) goingOn += close(at, sense, term, reaching)
        travelling = goingOn.distinct()
    }

    /** Describing words, each taking a copy of what reached them; what none took travels on beside their offers. */
    private fun describe(term: Term, describing: List<Pair<Int, Sense>>) {
        var untaken = travelling
        val onward = mutableListOf<Travelling>()
        for ((at, sense) in describing) {
            val folded = mutableListOf<Travelling>()
            for (reaching in travelling) {
                val acceptance = acceptanceFor(sense, reaching.offer) ?: continue
                if (!valueFits(sense, acceptance, reaching.offer)) continue
                untaken = untaken - reaching
                placed += reaching.page
                when (val outcome = acceptance.outcome) {
                    Outcome.Folds -> folded += reaching
                    is Outcome.Renames -> onward += reaching.copy(offer = reaching.offer.copy(concept = outcome.concept))
                    is Outcome.Scales -> onward += reaching.copy(offer = reaching.offer.copy(value = scaled(reaching.offer.value, outcome.factor)))
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
        val taken = reaching.flatMap { take(at, sense, it) }
        // A negated or `only` thing is struck or kept as described, and only a description of member kinds can say which.
        val isNarrowed = term.polarity != Polarity.ASSERTED
        val placements = if (isNarrowed) taken.filter { it.offer.concept in CoreConcepts.NARROWING } else taken
        placed += placements.flatMap { listOf(it.from) + it.carrying }
        val tookFrom = placements.flatMap { listOf(it.from) + it.carrying }.toSet()
        val tookAMember = placements.any { it.offer.concept == CoreConcepts.MEMBER }
        val named = if (tookAMember) sense.means.filterNot { namesMembersOf(it, sense.speaksFor) } else sense.means
        val own = named.map { EffectTemplate.fencedTo(it, sense.speaksFor) }
        phrases += Phrase(at, sense.speaksFor, own, placements, term.polarity, term.sitedIn, term.everywhere, term.joined)
        fun meantForSomeoneElse(it: Travelling) = it.offer.audience != Audience.Anyone && it.page !in tookFrom
        return reaching.filter { travelling -> placements.none { it.from == travelling.page && it.offer == travelling.offer } }
            .filter(::meantForSomeoneElse)
    }

    /** What [sense] at [at] makes of [reaching] and what it carries: none where it does not take it. */
    private fun take(at: Int, sense: Sense, reaching: Travelling): List<Placement> {
        val acceptance = acceptanceFor(sense, reaching.offer) ?: return emptyList()
        if (!valueFits(sense, acceptance, reaching.offer)) return emptyList()
        val carried = reaching.carrying.flatMap { take(at, sense, it) }
        return listOf(placementOf(at, sense.speaksFor, acceptance, reaching)) + carried
    }

    /** The Age: what it accepts itself — a template, a ban — and the rest to every part that takes it. */
    private fun handOut(at: Int) {
        val age = words.word(pages[at])?.defaultSense
        for (reaching in travelling.flatMap { listOf(it) + it.carrying }) {
            val ownAcceptance = age?.let { acceptanceFor(it, reaching.offer, isTheAge = true) }
            if (ownAcceptance != null) {
                handedOut += placementOf(at, null, ownAcceptance, reaching)
                placed += reaching.page
                continue
            }
            for (part in Aspect.entries) {
                val page = words.partPage(part)?.defaultSense ?: continue
                val acceptance = acceptanceFor(page, reaching.offer, isTheAge = true) ?: continue
                if (!valueFits(page, acceptance, reaching.offer)) continue
                handedOut += placementOf(at, part, acceptance, reaching)
                placed += reaching.page
            }
        }
        travelling = emptyList()
    }

    private fun placementOf(at: Int, part: Aspect?, acceptance: Acceptance, reaching: Travelling): Placement {
        val effects = (acceptance.outcome as? Outcome.Becomes)?.effects.orEmpty().mapNotNull { it.applied(reaching.offer.value, part) }
        return Placement(reaching.page, reaching.offer, at, part, effects, reaching.carrying.map(Travelling::page), reaching.joined)
    }

    /** How [sense] accepts [offer], if it may: the concept, and an audience that admits it. */
    private fun acceptanceFor(sense: Sense, offer: Offer, isTheAge: Boolean = false): Acceptance? {
        val acceptance = sense.acceptanceOf(offer.concept) ?: return null
        val admitted = when (val audience = offer.audience) {
            Audience.Anyone -> true
            Audience.TheAge -> isTheAge
            is Audience.Part -> sense.speaksFor == audience.aspect
        }
        return acceptance.takeIf { admitted }
    }

    /** Whether [offer] is a member of some other part than [sense]'s: an oak is no landform, whatever a landmass takes. */
    private fun isAMemberOfAnotherPart(sense: Sense, offer: Offer): Boolean {
        val text = (offer.value as? Value.Text)?.text ?: return false
        val part = sense.speaksFor ?: return false
        return offer.concept == CoreConcepts.MEMBER && !membership.isMemberOf(part, text)
    }

    /** Whether [offer]'s value is one [acceptance] takes: of the sense's own part where it is a member, and through its filter. */
    private fun valueFits(sense: Sense, acceptance: Acceptance, offer: Offer): Boolean {
        val text = (offer.value as? Value.Text)?.text
        if (isAMemberOfAnotherPart(sense, offer)) return false
        return when (val filter = acceptance.only) {
            ValueFilter.Anything -> true
            is ValueFilter.OneOf -> text != null && filter.keys.any { membership.isIn(text, it) }
            is ValueFilter.Clearing -> text != null && membership.clears(text, filter.bars)
        }
    }

    /**
     * Whether any word of [term] has a sense that takes [offer] — the lookahead. It reads the concept and the part a member
     * belongs to, never a group's filter: `sheep fliers` passes the sheep on, to be refused there.
     */
    private fun takenByAnyOf(term: Term, offer: Offer): Boolean = term.pages.any { at ->
        words.word(pages[at])?.senses.orEmpty().any { acceptanceFor(it, offer) != null && !isAMemberOfAnotherPart(it, offer) }
    }

    /** What the page at [at] offers in [sense]: a block made a ban for the Age where `no` stood before it. */
    private fun offersOf(at: Int, sense: Sense, term: Term): List<Travelling> = sense.offers.map { offer ->
        val banned = term.polarity == Polarity.EXCEPT && !sense.endsAPhrase && offer.concept == CoreConcepts.BLOCK
        val offered = if (banned) offer.copy(concept = CoreConcepts.BAN, audience = Audience.TheAge) else offer
        Travelling(at, offered, joined = term.joined)
    }

    /**
     * The sense [at] is read in: the one its neighbours fit best — what reaches it that it takes, and whether the term after
     * takes what it offers — then the one marked default, then the seed's.
     */
    private fun senseFor(at: Int, next: Term?): Sense {
        val all = words.word(pages[at])?.senses.orEmpty()
        val chosen = if (all.size == 1) 0 else {
            fun fit(sense: Sense): Int {
                val takes = travelling.count { acceptanceFor(sense, it.offer) != null }
                val passes = next != null && !next.isAge && sense.offers.any { takenByAnyOf(next, it) }
                return takes + if (passes) 1 else 0
            }
            val best = all.maxOf(::fit)
            val fitting = all.indices.filter { fit(all[it]) == best }
            fitting.firstOrNull { all[it].isDefault } ?: fitting[Random(mix64(seed xor at.toLong())).nextInt(fitting.size)]
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
