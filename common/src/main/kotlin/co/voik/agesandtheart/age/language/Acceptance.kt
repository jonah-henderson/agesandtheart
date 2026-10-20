package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.aspect.honoursDial
import co.voik.agesandtheart.age.aspect.ownParameters
import co.voik.agesandtheart.age.resolution.Members
import co.voik.agesandtheart.age.resolution.Place
import net.minecraft.core.HolderLookup
import net.minecraft.resources.Identifier

/**
 * What a word takes from the pages waiting before it, and the [place] what it takes lands. Read off what the place
 * owns rather than written, so a member added to the game takes whatever its code honours.
 */
data class Acceptance(
    val place: Place,
    /** The parameters a meaning may set or bend here. */
    val parameters: Set<String>,
    /** Whether a choice, a bar or a lean on the members of the place's aspect lands here. */
    val takesChoices: Boolean,
    /** Whether a substance — a block for the place to be made of — lands here. */
    val takesSubstance: Boolean,
    /** Whether an abundance — `teeming` — lands here. */
    val takesAbundance: Boolean,
    /** Words taken beyond what is derived, written by hand: a word answering one exact word laid before it. */
    val alsoTakes: Set<Identifier> = emptySet(),
) {
    fun takesParameter(name: String): Boolean = name in parameters

    companion object {
        /** What the page naming [aspect] takes: everything the aspect, and anything it may hold, understands. */
        fun ofAspect(aspect: Aspect): Acceptance {
            val holdsMembersToChoose = aspect.holds == Holds.CATALOGUE || aspect.holds == Holds.WEIGHTED_SET
            return Acceptance(
                place = Place(aspect),
                parameters = aspect.understood.map(Parameter::name).toSet(),
                takesChoices = holdsMembersToChoose,
                takesSubstance = aspect.madeOfSomething,
                takesAbundance = aspect.count != null,
            )
        }

        /**
         * What a word naming [member] takes: the dials it honours of those its aspect holds member by member, and
         * a design's own parameters. Without [registries] a registry entry is taken to honour every dial.
         */
        fun ofMember(member: Taggable, registries: HolderLookup.Provider?): Acceptance {
            fun honoured(parameter: Parameter) = parameter.ownedByEachMember && member.honoursDial(parameter, registries)
            val dials = (member.aspect.understood.filter(::honoured) + member.ownParameters).distinctBy(Parameter::name)
            return Acceptance(
                place = Place(member.aspect, Members.Named(setOf(member.key))),
                parameters = dials.map(Parameter::name).toSet(),
                takesChoices = false,
                takesSubstance = dials.any(Parameter::material),
                takesAbundance = countsInSpace(member),
            )
        }

        /**
         * Whether there can be more or less of [member] across the world: every member of a weighted set but a
         * phenomenon that comes and goes in time, and nothing a catalogue seats.
         */
        private fun countsInSpace(member: Taggable): Boolean =
            if (member is Phenomenon) member.countsInSpace else member.aspect.holds == Holds.WEIGHTED_SET
    }
}
