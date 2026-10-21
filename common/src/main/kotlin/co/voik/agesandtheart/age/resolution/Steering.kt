package co.voik.agesandtheart.age.resolution

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.Flaw
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Setting
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.aspect.ownParameters
import net.minecraft.resources.Identifier

/**
 * [composition] with every parameter what landed set or bent applied, after the presets are settled, since a
 * parameter steers whatever filled the aspect (§3.2). What was said of some members is written beside what was said
 * of all of them; a body is steered on its own; ranged axes settle together into spans; and a parameter several
 * landings set is divided where the aspect can be, or kept by the firmest where it cannot.
 */
internal fun Resolving.steer(composition: AgeComposition): AgeComposition {
    var steered = composition
    for (aspect in Aspect.entries) {
        val reaching = speakingTo(aspect).filter { it.settings.isNotEmpty() || it.bendings.isNotEmpty() }
        val ofSomeMembers = reaching.filter { it.members != null }
        val setting = reaching.map { it.withoutMemberValuesIn(aspect) }
            .filter { it.settings.isNotEmpty() || it.bendings.isNotEmpty() }
        if (setting.isEmpty()) {
            steered = steered.saidOfMembers(aspect, ofSomeMembers)
            continue
        }
        steered = spanned(steered, aspect, setting)
        val settled = rangedNames(steered, aspect)
        val parameters = setting.flatMap { it.settings.keys }.distinct()
            .filter { it != Parameter.CAST && it !in settled && holds(steered, aspect, it) }
        for (said in setting) {
            said.settings.keys.filter { it != Parameter.CAST && it !in settled && !holds(steered, aspect, it) }.forEach { lose(said, it) }
        }
        for (parameter in parameters) {
            val contenders = ranked(aspect, setting.filter { parameter in it.settings })
            val bodies = contenders.filter { it.body != null }
            steered = when {
                bodies.isNotEmpty() -> {
                    val ofAllOfThem = contenders.firstOrNull { it.body == null }
                    val many = maxOf(steered.membersIn(aspect), bodies.maxOf { (it.body ?: 0) + 1 })
                    val owners = (0..<many).map { member -> bodies.firstOrNull { it.body == member } ?: ofAllOfThem }
                    (contenders - owners.filterNotNull().toSet()).forEach { lose(it, parameter) }
                    owners.withIndex().fold(steered) { held, (member, own) ->
                        if (own == null) held
                        else held.withOptionsFor(aspect, member, parameter, listOf(own.settings.getValue(parameter)))
                    }
                }
                canFracture(steered, aspect, parameter, contenders) -> {
                    divide(groupsOf(parameter, contenders))
                    fractured(steered, aspect, parameter, contenders)
                }
                else -> contended(steered, aspect, parameter, contenders, asWritten = setting.sortedBy { it.page })
            }
        }
        flaws += wordsNothingHonours(steered, aspect, setting)
        steered = steered.saidOfMembers(aspect, ofSomeMembers)
    }
    return steered
}

/** Every member-level value [said] sets in [aspect], each written for the members it was said of. */
private fun AgeComposition.saidOfMembers(aspect: Aspect, said: List<Landing>): AgeComposition =
    said.fold(this) { held, landing ->
        landing.settings.filterKeys(aspect::ownsMemberParameterNamed).entries.fold(held) { writing, (parameter, value) ->
            val spelled = Claim(value, members = landing.members.orEmpty().toList()).spelled()
            val already = writing.optionsFor(aspect, 0).allSpelled(parameter)
            writing.withOptions(aspect, parameter, (already + spelled).distinct().toList())
        }
    }

/** This landing with what it said of members taken out — what is left for the aspect as a whole to steer. */
private fun Landing.withoutMemberValuesIn(aspect: Aspect): Landing {
    if (members == null) return this
    fun ofTheAspect(effect: Effect) = !(effect is Effect.Sets && aspect.ownsMemberParameterNamed(effect.parameter.name))
    return copy(effects = effects.filter(::ofTheAspect))
}

/**
 * A ranged parameter's claimants, gathered and applied: those that overlap narrow to what they share, `and` broadens
 * a run, and those that do not overlap take ground of their own where the aspect can divide, or the leading group
 * keeps it where it cannot. Each ground a landing is sited in is bounded on its own.
 */
private fun Resolving.spanned(composition: AgeComposition, aspect: Aspect, setting: List<Landing>): AgeComposition {
    val grounds = setting.map { it.sitedIn }.distinct()
    return grounds.fold(composition) { bounded, ground ->
        spannedIn(bounded, aspect, setting.filter { it.sitedIn == ground }, ground)
    }
}

private fun Resolving.spannedIn(
    composition: AgeComposition,
    aspect: Aspect,
    setting: List<Landing>,
    ground: Identifier?,
): AgeComposition {
    val axes = rangedNames(composition, aspect)
    fun asksOfAnAxis(said: Landing) = axes.any { it in said.settings || it in said.bendings }
    val speaking = ranked(aspect, setting.filter(::asksOfAnAxis))
    if (speaking.isEmpty()) return composition

    fun spansIn(parameters: Map<String, String>): Map<String, Span> = axes.mapNotNull { axis ->
        (parameters[axis]?.let(Setting::read) as? Setting.Fixed)?.let { axis to it.span }
    }.toMap()

    fun boundsIn(said: Landing): Map<String, Span> = spansIn(said.settings)
    fun bendsIn(said: Landing): Map<String, Span> = spansIn(said.bendings)

    /** Everything a landing asks of an axis that is not a demand — its limits, nudges and spreads. */
    fun askingIn(said: Landing): List<Pair<String, Setting>> = axes.flatMap { axis ->
        listOfNotNull(said.settings[axis], said.bendings[axis])
            .mapNotNull(Setting::read)
            .filterNot { it is Setting.Fixed }
            .map { axis to it }
    }

    fun agree(one: Landing, other: Landing): Boolean {
        val aboutDifferentBodies = one.body != null && other.body != null && one.body != other.body
        if (aboutDifferentBodies) return false
        if (one.isJoinedTo(other)) return true
        val mine = boundsIn(one)
        val theirs = boundsIn(other)
        return (mine.keys intersect theirs.keys).all { axis -> mine.getValue(axis).overlaps(theirs.getValue(axis)) }
    }

    val bounding = speaking.filter { axes.any { axis -> axis in it.settings } }

    fun preferred(axis: String): Double? {
        val wants = speaking.mapNotNull { bendsIn(it)[axis] }
        if (wants.isEmpty()) return null
        return wants.map { (it.least + it.most) / 2.0 }.average()
    }

    fun bentTo(bounds: Map<String, Span>): Map<String, Span> {
        val touched = bounds.keys + speaking.flatMap { bendsIn(it).keys }
        return touched.associateWith { axis ->
            val stretch = bounds[axis] ?: Span.NATURAL
            val want = preferred(axis) ?: return@associateWith stretch
            stretch.bentToward(stretch.fractionOf(want))
        }
    }

    val gathered = gathered(bounding, ::agree)
    fun bodyOf(group: List<Landing>): Int? = group.firstNotNullOfOrNull { it.body }
    val described = gathered.map(::bodyOf)
    val eachIsItsOwn = gathered.isNotEmpty() && described.none { it == null } && described.distinct().size == gathered.size
    val groups = if (eachIsItsOwn) gathered.sortedBy(::bodyOf) else gathered

    // Each group settles to the span its landings jointly describe: narrowing, unless `and` broadens a run.
    val climates = groups.map { group ->
        val runs = group.withIndex().groupBy { (at, said) -> said.joined ?: -1 - at }.values
        axes.mapNotNull { axis ->
            runs.mapNotNull { run ->
                run.mapNotNull { (_, said) -> boundsIn(said)[axis] }.reduceOrNull { held, next -> held.broadenedTo(next) }
            }
                .reduceOrNull { held, next -> held.narrowedTo(next) }
                ?.let { axis to it }
        }.toMap()
    }.ifEmpty { listOf(emptyMap()) }

    fun settledWith(bounds: Map<String, Span>): Map<String, Span> {
        val asking = speaking.flatMap(::askingIn)
        if (asking.isEmpty()) return bounds
        return (bounds.keys + asking.map { it.first }).associateWith { axis ->
            val demanded = bounds[axis]
            val onThisAxis = asking.filter { it.first == axis }.map { it.second }
            val asked = listOfNotNull(demanded?.let(Setting::Fixed)) + onThisAxis
            Setting.settle(asked) ?: demanded ?: Span.NATURAL
        }
    }

    fun written(held: AgeComposition, member: Int, bounds: Map<String, Span>): AgeComposition {
        var steered = held
        for ((axis, span) in bounds) {
            val already = steered.optionsFor(aspect, member).allSpelled(axis)
            val bounded = Claim(span.spelled(), confinedTo = ground).spelled()
            steered = steered.withOptionsFor(aspect, member, axis, (already - bounded + bounded).toList())
        }
        return steered
    }

    if (eachIsItsOwn) {
        val bodies = (described.filterNotNull().maxOrNull() ?: 0) + 1
        return climates.withIndex().fold(composition.withMembers(aspect, bodies)) { held, (at, bounds) ->
            written(held, bodyOf(groups[at]) ?: 0, settledWith(bentTo(bounds)))
        }
    }

    val couldFracture = aspect.spatial && composition.membersIn(aspect) == 1
    if (climates.size == 1 || !couldFracture || climates.size > MOST_TERRITORIES) {
        for (overruled in groups.drop(1).flatten()) boundsIn(overruled).keys.forEach { lose(overruled, it) }
        val agreed = settledWith(bentTo(climates.first()))
        if (!aspect.membersAreDescribed) return written(composition, member = 0, bounds = agreed)
        return (0..<maxOf(composition.membersIn(aspect), 1)).fold(composition) { held, member -> written(held, member, agreed) }
    }
    divide(groups)
    var fractured = composition.withMembers(aspect, groups.size)
    for ((member, bounds) in climates.withIndex()) fractured = written(fractured, member, settledWith(bentTo(bounds)))
    return fractured
}

/** Which of [aspect]'s parameters bound a continuous axis — asked of the seated presets, as they own them. */
private fun rangedNames(composition: AgeComposition, aspect: Aspect): List<String> =
    parametersOf(composition, aspect).filter { it.holds == Holds.RANGE }.map { it.name }.distinct()

/**
 * Whether the groups can each be given ground: the aspect can divide, holds one preset, and has room for them.
 */
private fun canFracture(composition: AgeComposition, aspect: Aspect, parameter: String, contenders: List<Landing>): Boolean {
    if (!aspect.spatial) return false
    if (composition.membersIn(aspect) != 1) return false
    return groupsOf(parameter, contenders).size in 2..MOST_TERRITORIES
}

/** The claimants gathered into the fewest sets satisfiable at once: the same value, or joined by `and`. */
private fun groupsOf(parameter: String, contenders: List<Landing>): List<List<Landing>> {
    fun agree(one: Landing, other: Landing) = one.settings[parameter] == other.settings[parameter] || one.isJoinedTo(other)
    return gathered(contenders, ::agree)
}

/**
 * [contenders] gathered into the fewest sets whose members all [agree] with one another — all, not one, because
 * agreement between spans is not transitive.
 */
private fun gathered(contenders: List<Landing>, agree: (Landing, Landing) -> Boolean): List<List<Landing>> {
    val groups = mutableListOf<MutableList<Landing>>()
    for (said in contenders) {
        val home = groups.firstOrNull { group -> group.all { seated -> agree(seated, said) } }
        if (home == null) groups += mutableListOf(said) else home += said
    }
    return groups
}

/** The aspect seated once per group, each territory steered by its own group: copper spires and andesite hills. */
private fun fractured(composition: AgeComposition, aspect: Aspect, parameter: String, contenders: List<Landing>): AgeComposition {
    val groups = groupsOf(parameter, contenders)
    val seated = composition.presets.first { it.aspect == aspect }
    var fractured = composition.withPresets(aspect, List(groups.size) { seated.key })
    for ((member, group) in groups.withIndex()) {
        val asked = group.map { it.settings.getValue(parameter) }.distinct()
        fractured = fractured.withOptionsFor(aspect, member, parameter, asked)
    }
    return fractured
}

/**
 * A parameter several landings set, where it holds one answer: the firmest keeps it, with whatever is joined to it
 * or asks the same, and two sited in different biomes are not rivals at all.
 */
private fun Resolving.contended(
    composition: AgeComposition,
    aspect: Aspect,
    parameter: String,
    contenders: List<Landing>,
    asWritten: List<Landing>,
): AgeComposition {
    fun keptAmong(rivals: List<Landing>): List<Landing> {
        val winner = rivals.first()
        val mingled = rivals.filter { it == winner || it.isJoinedTo(winner) || it.settings[parameter] == winner.settings[parameter] }
        val keepsOrder = parametersOf(composition, aspect).firstOrNull { it.name == parameter }?.keepsWrittenOrder == true
        return if (keepsOrder) mingled.sortedBy(asWritten::indexOf) else mingled
    }
    val kept = contenders.groupBy { it.sitedIn }.values.flatMap(::keptAmong)
    (contenders - kept.toSet()).forEach { lose(it, parameter) }
    return composition.withOptions(
        aspect,
        parameter,
        kept.map { Claim(it.settings.getValue(parameter), confinedTo = it.sitedIn).spelled() }.distinct(),
    )
}

/** Whether [aspect] holds a parameter by this name at all — its seated presets' own, or its own. */
private fun holds(composition: AgeComposition, aspect: Aspect, parameter: String): Boolean =
    parametersOf(composition, aspect).any { it.name == parameter }

private fun parametersOf(composition: AgeComposition, aspect: Aspect): List<Parameter> =
    composition.presets.filter { it.aspect == aspect }.flatMap { it.ownParameters } + aspect.parameters

/** A parameter set at something nothing seated can honour — charged as unbacked rather than ignored (§3.3). */
private fun Resolving.wordsNothingHonours(
    composition: AgeComposition,
    aspect: Aspect,
    setting: List<Landing>,
): List<Flaw> {
    val seated = composition.presets.filter { it.aspect == aspect }
    fun anythingSeatedHonours(parameter: String) =
        seated.any { it.honoursParameterNamed(parameter) } || aspect.parameters.any { it.name == parameter }
    fun canSet(said: Landing) = said.settings.keys + said.bendings.keys
    val addressing = setting.filter { said -> canSet(said).any { holds(composition, aspect, it) } }
    val wentUnheeded = addressing.filter { said -> canSet(said).none(::anythingSeatedHonours) }
    for (said in wentUnheeded) said.settings.keys.forEach { lose(said, it) }
    return wentUnheeded.map { said -> flaw(Register.UNBACKED, listOf(said), aspect) }
}
