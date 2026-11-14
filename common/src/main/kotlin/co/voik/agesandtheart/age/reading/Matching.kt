package co.voik.agesandtheart.age.reading

import co.voik.agesandtheart.age.words.Acceptance
import co.voik.agesandtheart.age.words.Audience
import co.voik.agesandtheart.age.words.CoreConcepts
import co.voik.agesandtheart.age.words.EffectTemplate
import co.voik.agesandtheart.age.words.Offer
import co.voik.agesandtheart.age.words.Outcome
import co.voik.agesandtheart.age.words.Sense
import co.voik.agesandtheart.age.words.Value
import co.voik.agesandtheart.age.words.ValueFilter

/**
 * Whether a sense takes an offer, and how: the one copy of the rule, asked by the reader as it reads and by
 * [WordsInTheWorld] of every offer and every sense that could meet.
 */
class Matching(private val membership: Membership) {

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

    /** Whether [offer]'s value is one [acceptance] takes: of the sense's own part where it is a member, and through its filter. */
    fun valueFits(sense: Sense, acceptance: Acceptance, offer: Offer): Boolean {
        val text = (offer.value as? Value.Text)?.text
        if (isAMemberOfAnotherPart(sense, offer)) return false
        return when (val filter = acceptance.only) {
            ValueFilter.Anything -> true
            is ValueFilter.OneOf -> text != null && filter.keys.any { membership.isIn(text, it) }
            is ValueFilter.Clearing -> text != null && membership.clears(text, filter.bars)
        }
    }
}
