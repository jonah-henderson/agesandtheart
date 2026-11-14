package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.words.Acceptance
import co.voik.agesandtheart.age.words.Audience
import co.voik.agesandtheart.age.words.Concept
import co.voik.agesandtheart.age.words.CoreConcepts
import co.voik.agesandtheart.age.words.EffectTemplate
import co.voik.agesandtheart.age.words.Offer
import co.voik.agesandtheart.age.words.Outcome
import co.voik.agesandtheart.age.words.Sense
import co.voik.agesandtheart.age.words.Value
import co.voik.agesandtheart.age.words.ValueFilter
import net.minecraft.resources.Identifier

/**
 * Whether a sense takes an offer, and how: the one copy of the rule, asked by the reader as it reads and by
 * [WordsInTheWorld] of every offer and every sense that could meet.
 */
class Matching(private val membership: Membership, private val concepts: Map<Identifier, Concept>) {

    /**
     * How [sense] accepts [offer], if it may: the concept, and an audience that admits it. A reading is taken by any
     * word of its part, as the effects it carries.
     */
    fun acceptanceFor(sense: Sense, offer: Offer, isTheAge: Boolean = false): Acceptance? {
        if (offer.isAReading) {
            val isOfItsPart = sense.speaksFor == (offer.audience as? Audience.Part)?.aspect
            return Acceptance(CoreConcepts.READING, outcome = Outcome.Becomes(offer.reading.map(EffectTemplate::Fixed))).takeIf { isOfItsPart }
        }
        val acceptance = sense.acceptanceOf(offer.concept) ?: return null
        val admitted = when (val audience = offer.audience) {
            Audience.Anyone -> true
            Audience.TheAge -> isTheAge
            is Audience.Part -> sense.speaksFor == audience.aspect
        }
        return acceptance.takeIf { admitted }
    }

    /** Whether [offer] is a member of some other part than [sense]'s: an oak is no landform, whatever a landmass takes. */
    fun isAMemberOfAnotherPart(sense: Sense, offer: Offer): Boolean {
        val text = (offer.value as? Value.Text)?.text ?: return false
        val part = sense.speaksFor ?: return false
        return offer.concept == CoreConcepts.MEMBER && !membership.isMemberOf(part, text)
    }

    /**
     * Whether [offer]'s value is one [acceptance] takes: of the sense's own part where it is a member, through its filter,
     * and one it makes something of — a meaning that would move nothing where it lands is not taken.
     */
    fun valueFits(sense: Sense, acceptance: Acceptance, offer: Offer): Boolean {
        val text = (offer.value as? Value.Text)?.text
        if (isAMemberOfAnotherPart(sense, offer)) return false
        val passesTheFilter = when (val filter = acceptance.only) {
            ValueFilter.Anything -> true
            is ValueFilter.OneOf -> text != null && filter.keys.any { membership.isIn(text, it) }
            is ValueFilter.Clearing -> text != null && membership.clears(text, filter.bars)
        }
        return passesTheFilter && takesItFromWhoOffersIt(sense, acceptance, offer) && makesSomethingOf(sense, acceptance, offer)
    }

    /** Whether [offer] comes from someone [acceptance] takes it from: a `#tag` only from an offer for its part, where it asks that. */
    private fun takesItFromWhoOffersIt(sense: Sense, acceptance: Acceptance, offer: Offer): Boolean {
        val isATag = (offer.value as? Value.Text)?.isTag == true
        val part = sense.speaksFor
        val offeredForItsPart = part != null && offer.audience == Audience.Part(part)
        return !(acceptance.tagsOnlyWhenOfferedForIt && isATag) || offeredForItsPart
    }

    /** Whether [acceptance] makes an effect of [offer]'s value where it becomes effects: wear's amount, to a part acting only on its porous way, it does not. */
    private fun makesSomethingOf(sense: Sense, acceptance: Acceptance, offer: Offer): Boolean {
        val templates = (acceptance.outcome as? Outcome.Becomes)?.effects ?: return true
        if (templates.isEmpty() || offer.isAReading) return true
        return effectsOf(sense, sense.speaksFor, acceptance, offer).isNotEmpty()
    }

    /**
     * What [acceptance] makes of [offer] at [part]: of its effects, those a member [sense] names does anything with —
     * `black sheep` dyes the wool and leaves the sheep's own colour be.
     */
    fun effectsOf(sense: Sense?, part: Aspect?, acceptance: Acceptance, offer: Offer): List<Effect> {
        val templates = (acceptance.outcome as? Outcome.Becomes)?.effects.orEmpty()
        val effects = templates.mapNotNull { it.applied(filled(offer), part) }
        return if (sense == null) effects else effects.filter { aMemberItNamesDoesAnythingWith(sense, it) }
    }

    /** Whether a member [sense] names does anything with [effect], or it names none. */
    private fun aMemberItNamesDoesAnythingWith(sense: Sense, effect: Effect): Boolean {
        val part = sense.speaksFor ?: return true
        val named = sense.means.flatMap { meaning ->
            when {
                meaning is Effect.Chooses && meaning.aspect == part -> listOf(meaning.member)
                meaning is Effect.Admits && meaning.aspect == part -> meaning.members
                else -> emptyList()
            }
        }
        return named.isEmpty() || named.any { membership.doesAnythingWith(part, it, effect) }
    }

    /** [offer]'s value with every part it does not give at its concept's default, where its concept is made of parts. */
    fun filled(offer: Offer): Value {
        val parts = offer.value as? Value.Parts ?: return offer.value
        return concepts[offer.concept]?.filled(parts) ?: parts
    }
}
