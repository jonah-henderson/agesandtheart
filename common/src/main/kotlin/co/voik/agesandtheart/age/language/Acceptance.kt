package co.voik.agesandtheart.age.language

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.MATERIAL_PARAMETERS
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Phenomenon
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.aspect.honoursDial
import co.voik.agesandtheart.age.aspect.ownParameters
import co.voik.agesandtheart.age.resolution.Effect
import co.voik.agesandtheart.age.resolution.Members
import co.voik.agesandtheart.age.resolution.ParameterKey
import co.voik.agesandtheart.age.resolution.Place
import net.minecraft.core.HolderLookup

/**
 * What a word takes from the pages waiting before it, and the [place] what it takes lands. Read off what the place
 * owns rather than written, so a member added to the game takes whatever its code honours.
 */
data class Acceptance(
    val place: Place,
    /** The parameters a meaning may set or bend here. */
    val parameters: Set<String>,
    /** Whether a choice or a bar on the members of the place's aspect lands here. */
    val takesChoices: Boolean,
    /**
     * Whether a lean on the members of the place's aspect lands here — on a word naming members, a lean among them:
     * `beautiful plants` leans toward the beautiful ones, where a choice would argue with the naming.
     */
    val takesLeans: Boolean,
    /** Whether a substance — a block for the place to be made of — lands here. */
    val takesSubstance: Boolean,
    /** Whether an abundance — `teeming` — lands here. */
    val takesAbundance: Boolean,
    /** Whether this mints a feature, so that a substance taken is what the feature is made of. */
    val mints: Boolean = false,
    /** A part of the world the page speaks for as well, taking what this one does not — the sea's for the waters. */
    val companion: Acceptance? = null,
) {
    fun takesParameter(name: String): Boolean = name in parameters

    /** Where [effect] lands from this page and as what, or null where neither this nor its [companion] takes it. */
    fun landing(effect: Effect): Pair<Place, Effect>? =
        takenHere(effect)?.let { place to it } ?: companion?.landing(effect)

    /** [effect] as it lands from this page, or null where nothing here takes it. */
    fun take(effect: Effect): Effect? = landing(effect)?.second

    private fun takenHere(effect: Effect): Effect? {
        fun onThisAspect(aspect: Aspect) = aspect == place.aspect
        return when (effect) {
            is Effect.Sets -> when {
                mints && effect.parameter.name in MATERIAL_PARAMETERS -> Effect.MadeOf(effect.value)
                takes(effect.parameter) -> effect
                else -> null
            }
            is Effect.Bends -> effect.takeIf { takes(it.parameter) }
            is Effect.Chooses -> effect.takeIf { takesChoices && onThisAspect(it.aspect) }
            is Effect.Admits -> effect.takeIf { takesChoices && onThisAspect(it.aspect) }
            is Effect.Excludes -> effect.takeIf { takesChoices && onThisAspect(it.aspect) }
            is Effect.Restricts -> effect.takeIf { takesChoices && onThisAspect(it.aspect) }
            is Effect.Leans -> effect.takeIf { takesLeans && onThisAspect(it.aspect) }
            is Effect.LeansEverywhere -> if (takesLeans) Effect.Leans(place.aspect, effect.weights) else null
            is Effect.Mints -> effect.takeIf { takesChoices && onThisAspect(Aspect.FEATURES) }
            is Effect.Abundance -> effect.takeIf { takesAbundance }
            is Effect.Template, is Effect.MadeOf -> null
        }
    }

    /** Whether a meaning setting [key] lands here: the parameter is taken, and any fence on it is this aspect. */
    private fun takes(key: ParameterKey): Boolean {
        val fenceFits = key.fencedTo == null || key.fencedTo == place.aspect
        return fenceFits && takesParameter(key.name)
    }

    companion object {
        /** What the page naming [aspect] takes: everything the aspect, and anything it may hold, understands. */
        fun ofAspect(aspect: Aspect): Acceptance {
            val holdsMembersToChoose = aspect.holds == Holds.CATALOGUE || aspect.holds == Holds.WEIGHTED_SET
            return Acceptance(
                place = Place(aspect),
                parameters = aspect.understood.map(Parameter::name).toSet(),
                takesChoices = holdsMembersToChoose,
                takesLeans = holdsMembersToChoose,
                takesSubstance = aspect.madeOfSomething,
                takesAbundance = aspect.count != null || aspect in COUNTED_WHOLE,
            )
        }

        /** The populations a quantifier counts whole — `teeming spawns`, `few structures` — every member at once. */
        private val COUNTED_WHOLE = setOf(Aspect.FEATURES, Aspect.STRUCTURES, Aspect.SPAWNS)

        /**
         * What a word naming [member] takes: the dials it honours of those its aspect holds member by member, and
         * a design's own parameters. A catalogue's member is the whole of its part of the world where it is seated,
         * so it takes the part's own parameters too. Without [registries] a registry entry is taken to honour every
         * dial.
         */
        fun ofMember(member: Taggable, registries: HolderLookup.Provider?): Acceptance {
            val aspect = member.aspect
            val seatsTheWholeAspect = aspect.holds == Holds.CATALOGUE
            fun honoured(parameter: Parameter) = parameter.ownedByEachMember && member.honoursDial(parameter, registries)
            val ofTheAspect = if (seatsTheWholeAspect) aspect.parameters else emptyList()
            val dials = (aspect.understood.filter(::honoured) + member.ownParameters + ofTheAspect).distinctBy(Parameter::name)
            return Acceptance(
                place = Place(aspect, Members.Named(setOf(member.key))),
                parameters = dials.map(Parameter::name).toSet(),
                takesChoices = false,
                takesLeans = aspect.holds == Holds.WEIGHTED_SET,
                takesSubstance = dials.any(Parameter::material) || (seatsTheWholeAspect && aspect.madeOfSomething),
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
