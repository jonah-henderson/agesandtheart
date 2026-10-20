package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.PlacedFeature
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.resolution.Members
import co.voik.agesandtheart.age.resolution.Place
import co.voik.agesandtheart.age.aspect.Bars
import net.minecraft.core.HolderLookup

/** The tag layer as the language reads it: what a part of the world holds for a broad word, and what each carries. */
interface MemberTags {
    fun availableToBroadWordsIn(aspect: Aspect): List<Taggable>

    fun tagsOf(member: Taggable): Map<String, Double>
}

/**
 * What a word takes from the pages waiting before it, or null where it takes nothing — derived from what the word
 * is, never written (`the-world-model.md` §3). A part of the world's page takes what the part understands; a word
 * naming members takes what they honour; a word minting where it is aimed takes a substance and its pattern's dials,
 * where a block raised as a formation only laid bare stays a substance; anything else takes nothing. A meaning
 * offered to a word reaches it past all of this ([Meaning.offeredTo]).
 */
object WordAcceptance {

    fun of(word: Word, tags: MemberTags, registries: HolderLookup.Provider?): Acceptance? {
        return pageOf(word) ?: minting(word, registries) ?: membersNamedBy(word, tags, registries)
    }

    private fun pageOf(word: Word): Acceptance? {
        val isOurs = word.id.namespace == Constants.MOD_ID
        val aspect = Aspect.byPage(word.name)?.takeIf { isOurs } ?: return null
        return Acceptance.ofAspect(aspect)
    }

    private fun minting(word: Word, registries: HolderLookup.Provider?): Acceptance? {
        val appliesWhereAimed = word.meanings.filter { it.mustAlwaysApply || it.chanceToApply > Meaning.NEVER }
        val pattern = appliesWhereAimed.flatMap { it.effects }.filterIsInstance<Effect.Mints>().firstOrNull() ?: return null
        val feature = PlacedFeature.named(pattern.pattern) ?: return null
        return Acceptance.ofMember(feature, registries).copy(takesSubstance = true, mints = true)
    }

    /**
     * A word whose every meaning chooses or bars members of one weighted set, or chooses a design of ours, names
     * them — `villages` the one, `trees` everything wooded, `spires` a landform — and takes what any of them
     * honours. A block chooses a sea, and is a substance rather than a member.
     */
    private fun membersNamedBy(word: Word, tags: MemberTags, registries: HolderLookup.Provider?): Acceptance? {
        val effects = word.meanings.flatMap { it.effects }
        val aspects = effects.map(::aspectNamed).distinct()
        val aspect = aspects.singleOrNull() ?: return null
        val holdsMembers = aspect.holds == Holds.WEIGHTED_SET
        val holdsOurDesigns = aspect.holds == Holds.CATALOGUE && !aspect.open
        if (!holdsMembers && !holdsOurDesigns) return null
        val chosen = effects.filterIsInstance<Effect.Chooses>().mapNotNull { aspect.presetFor(it.member) }
        val bars = effects.filterIsInstance<Effect.Restricts>().map { it.bars }
        fun clearsEveryBar(member: Taggable) = bars.all { Bars.cleared(tags.tagsOf(member), it) }
        val carrying = if (bars.isEmpty()) emptyList() else tags.availableToBroadWordsIn(aspect).filter(::clearsEveryBar)
        val members = (chosen + carrying).distinctBy(Taggable::key)
        if (members.isEmpty()) return null
        return ofMembers(aspect, members, registries)
    }

    /** The aspect [effect] chooses or bars members of, or null where it does anything else. */
    private fun aspectNamed(effect: Effect): Aspect? = when (effect) {
        is Effect.Chooses -> effect.aspect
        is Effect.Restricts -> effect.aspect
        else -> null
    }

    private fun ofMembers(aspect: Aspect, members: List<Taggable>, registries: HolderLookup.Provider?): Acceptance {
        val each = members.map { Acceptance.ofMember(it, registries) }
        return Acceptance(
            place = Place(aspect, Members.Named(members.map(Taggable::key).toSet())),
            parameters = each.flatMap { it.parameters }.toSet(),
            takesChoices = false,
            takesLeans = true,
            takesSubstance = each.any { it.takesSubstance },
            takesAbundance = each.any { it.takesAbundance },
        )
    }
}
