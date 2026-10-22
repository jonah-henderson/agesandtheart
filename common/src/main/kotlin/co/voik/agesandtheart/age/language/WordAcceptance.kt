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
        val acceptance = pageOf(word) ?: minting(word, registries) ?: membersNamedBy(word, tags, registries) ?: return null
        return acceptance.withLeanable(tags)
    }

    /**
     * [this] knowing what a lean here could move: the tags its members carry, at a weight that moves the draw, and
     * their keys — every broad member of the aspect for its page, the members named for a word naming them.
     */
    private fun Acceptance.withLeanable(tags: MemberTags): Acceptance {
        if (!takesLeans) return copy(companion = companion?.withLeanable(tags))
        val named = (place.members as? Members.Named)?.keys
        val reach = named?.mapNotNull(place.aspect::presetFor) ?: tags.availableToBroadWordsIn(place.aspect)
        val carried = reach.flatMap { member -> tags.tagsOf(member).filterValues { it > CARRIED }.keys + member.key }
        return copy(leanable = carried.toSet(), companion = companion?.withLeanable(tags))
    }

    /** A tag weight under this moves a lean's draw too little to be what the lean is about. */
    private const val CARRIED = 0.3

    private fun pageOf(word: Word): Acceptance? {
        val isOurs = word.id.namespace == Constants.MOD_ID
        val aspect = Aspect.byPage(word.name)?.takeIf { isOurs } ?: return null
        val synonym = SYNONYMS[aspect]?.let(Acceptance::ofAspect)
        return Acceptance.ofAspect(aspect).copy(companion = synonym)
    }

    /** Pages that mean one thing to a writer though the world keeps them apart: the sea and its waters. */
    private val SYNONYMS = mapOf(Aspect.SEA to Aspect.WATERS, Aspect.WATERS to Aspect.SEA)

    private fun minting(word: Word, registries: HolderLookup.Provider?): Acceptance? {
        val appliesWhereAimed = word.meanings.filter { it.mustAlwaysApply || it.chanceToApply > Meaning.NEVER }
        val pattern = appliesWhereAimed.flatMap { it.effects }.filterIsInstance<Effect.Mints>().firstOrNull() ?: return null
        val feature = PlacedFeature.named(pattern.pattern) ?: return null
        return Acceptance.ofMember(feature, registries).copy(takesLeans = false, takesSubstance = true, mints = true)
    }

    /**
     * A word whose every meaning chooses or bars members of one weighted set, or chooses a design of ours, names
     * them — `villages` the one, `trees` everything wooded, `spires` a landform — and takes what any of them
     * honours. A block chooses a sea, and is a substance rather than a member.
     */
    private fun membersNamedBy(word: Word, tags: MemberTags, registries: HolderLookup.Provider?): Acceptance? {
        // A meaning offered elsewhere is not the word's own naming: `herds` offers a herd's size to `of`.
        val effects = word.meanings.filter { !it.isOffered }.flatMap { it.effects }
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

    /**
     * What a word naming [members] takes: a dial or material only where most of them honour it — `trees` grow of a
     * block though bamboo has no leaves, where `plants` do not because a few have them, and `red beasts` is no
     * word because only the sheep among them have wool — and a lean only among more than one.
     */
    private fun ofMembers(aspect: Aspect, members: List<Taggable>, registries: HolderLookup.Provider?): Acceptance {
        val each = members.map { Acceptance.ofMember(it, registries) }
        fun mostHonour(parameter: String) = each.count { it.takesParameter(parameter) } * 2 > each.size
        val parameters = each.flatMap { it.parameters }.toSet().filter(::mostHonour)
        return Acceptance(
            place = Place(aspect, Members.Named(members.map(Taggable::key).toSet())),
            parameters = parameters.toSet(),
            takesChoices = false,
            takesLeans = members.size > 1,
            takesSubstance = each.count { it.takesSubstance } * 2 > each.size,
            takesAbundance = each.any { it.takesAbundance },
        )
    }
}
