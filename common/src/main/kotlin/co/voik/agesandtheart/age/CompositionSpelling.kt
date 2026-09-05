package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Share
import co.voik.agesandtheart.age.aspect.Terrain

/**
 * **How a composition is written down for a person** — `landmass=hills landmass.arrangement=grid sea=water`.
 *
 * A grammar of its own, and kept apart from [AgeComposition] because it is one: the composition is a record
 * with a codec, and this is a hand-written format read and written by two debug commands. Living in the tail
 * of the record is how [PARAMETER_MARK] came to be the same character as [LIST_MARK] for a while, which made
 * `[stone=copper,tuff]` — the documented spelling for mingled materials — unreadable, and nothing noticed.
 *
 * **Not the Art.** The pen proper must never fail loudly on a writer (design §2); this is a command, so a
 * typo here is a mistake and says so.
 */
object CompositionSpelling {

    /**
     * A composition and the world it was written over, which is the whole of what `/age compose` can say.
     *
     * The template is on the *recipe* rather than in the composition, so a spelling that only carried the
     * composition could not round-trip an infernal Age: `landmass=vanilla` says the rock is not ours, and
     * only the template says which vanilla it is.
     */
    data class Written(
        val composition: AgeComposition,
        val template: AgeTemplate = AgeTemplate.ORDINARY,
        /**
         * An index set by hand rather than earned by contradiction — `unstable=42`.
         *
         * **Here because it has to be set when the Age is written, not after.** `/age decay` rewrites a
         * recipe that is already open, and an Age's generator settles what it places when it opens — so
         * anything the consequence registers reach through *generation* is untestable from decay and
         * perfectly testable from here.
         *
         * Like the template, this belongs to the recipe rather than to the composition, and it is read
         * here because this is the only spelling a person types an Age in.
         */
        val instability: Instability = Instability.NONE,
    )

    /**
     * Everything [specification] says, or a failure naming what could not be read.
     *
     * Fails loudly on anything unrecognised, because this is a command and a typo here is a mistake.
     */
    fun read(specification: String): Result<Written> = runCatching {
        // A stand-in, so options may be read in any order relative to the presets they steer. Either
        // the sentence names a terrain over the top of it, or it is rejected below for naming none.
        var composition = AgeComposition(terrains = listOf(Terrain.SHAPES))
        var template = AgeTemplate.ORDINARY
        var instability = Instability.NONE
        var namedALandform = false

        for (token in specification.split(' ').filter(String::isNotBlank)) {
            val (key, value) = token.split('=', limit = 2).takeIf { it.size == 2 }
                ?: error("'$token' is not `key=value`")
            // The one token that is not an aspect's: which world the Age is written over, which belongs to
            // the recipe. Read here because this is the only spelling a person types a composition in.
            if (key == TEMPLATE) {
                template = AgeTemplate.named(value)
                    ?: error("No world called '$value'. Try: ${AgeTemplate.entries.joinToString(" ") { it.key }}")
                continue
            }
            // The other token that is not an aspect's, and the same argument: the recipe's, not the world's.
            if (key == UNSTABLE) {
                val index = value.toIntOrNull()?.takeIf { it >= 0 }
                    ?: error("'$value' is not an instability index. It is a whole number, nought or more.")
                instability = Instability.forced(index)
                continue
            }
            val aspect = Aspect.entries.firstOrNull { key.substringBefore('.') == it.page }
                ?: error(
                    "No aspect called '${key.substringBefore('.')}'. " +
                        "Slots: ${Aspect.entries.joinToString(" ") { it.page }}",
                )

            composition = if ('.' in key) {
                composition.withOptions(aspect, key.substringAfter('.'), outsideBrackets(value))
            } else {
                namedALandform = namedALandform || aspect == Aspect.TERRAIN
                // Commas are how a set-valued aspect is written: `landmass=hills,pillars`. An `@` after
                // a preset is how much ground it covers: `rock=caves,porous@0.25`. Brackets after
                // that steer that territory alone: `landmass=spires[stone=copper],hills`.
                val filling = outsideBrackets(value)
                val named = filling.map { it.substringBefore(STEER_OPEN) }
                composition
                    .withPresets(
                        aspect,
                        named.map { it.substringBefore(SHARE_MARK) },
                        named.map { preset ->
                            val share = preset.substringAfter(SHARE_MARK, missingDelimiterValue = "")
                            if (share.isEmpty()) Share.EVEN else readShare(share)
                        },
                    )
                    .steeredBy(aspect, filling)
            }
        }
        require(namedALandform) { "An Age needs a terrain. Try `${Aspect.TERRAIN.page}=${Terrain.HILLS.key}`" }
        // Vanilla's rock answers for the whole world or for none of it — the field tree and vanilla's
        // router are either/or — so it cannot stand as one territory among several. Said here rather
        // than left to the generator, which has no way to report it and used to throw instead.
        val ourOwnRockBeside = composition.terrains.filter { it != Terrain.VANILLA }
        val sharesTheWorld = Terrain.VANILLA in composition.terrains && ourOwnRockBeside.isNotEmpty()
        require(!sharesTheWorld) {
            "`${Aspect.TERRAIN.page}=${Terrain.VANILLA.key}` is the whole world's rock and cannot " +
                "divide it with ${ourOwnRockBeside.joinToString(" ") { it.key }}"
        }
        Written(composition, template, instability)
    }

    /**
     * How a writer would have said it — exactly the spelling [read] reads back, so `/age list` output pastes
     * into `/age compose` and the pair can be checked by round trip (`SpellingCheck`).
     *
     * Every aspect is named even at its default. The template is spelled only where it is not the ordinary
     * one, since silence already means the overworld (`the-world-model.md` §4).
     */
    fun spell(written: Written): String {
        val world = if (written.template == AgeTemplate.ORDINARY) emptyList()
        else listOf("$TEMPLATE=${written.template.key}")
        // Spelled only where there is one, so a coherent Age reads exactly as it always did. What comes
        // back is an index and not the flaws that earned it — this is a spelling of an Age, not of an
        // argument the writer had with themselves.
        val wrong = if (written.instability.isCoherent) emptyList()
        else listOf("$UNSTABLE=${written.instability.index}")
        return (world + wrong + written.composition.tokens()).joinToString(" ")
    }

    /** Every token a composition alone says — what [AgeComposition.toString] is. */
    internal fun AgeComposition.tokens(): List<String> =
        presets.groupBy { it.aspect }.entries
            .sortedBy { (aspect, _) -> aspect.ordinal }
            .flatMap { (aspect, filling) ->
                // Territories that agree are spelled once for the whole aspect; only differing ones pay
                // for braces.
                val aimed = options.allOf(aspect).size > 1
                val spelledOut = filling.mapIndexed { index, preset ->
                    // A share is only spelled where it says something: an even division, and the largest
                    // share of an uneven one, are both left unsaid.
                    val share = spreadOf(aspect).shares.getOrNull(index)
                    val named =
                        if (share == null || Share.isEven(share)) preset.key else "${preset.key}$SHARE_MARK$share"
                    if (aimed) named + steering(options.of(aspect, index)) else named
                }
                val slotWide = if (aimed) emptyList() else spelled(aspect, options.of(aspect))
                listOf("${aspect.page}=${spelledOut.joinToString(LIST_MARK.toString())}") + slotWide
            }
            .plus(castSpelling())
            .plus(seatlessSpelling())
            .plus(seamSpelling())

    /**
     * `landmass.seam=rift` — the form drawn for each boundary the Age has one for.
     *
     * Spelled even where it was drawn rather than asked for, because a recipe records what an Age *is*: the
     * draw is reproducible from the seed, but a spelling that left it out would read as "nothing was decided
     * here" and could not tell a requested shear from an unremarked one.
     */
    private fun AgeComposition.seamSpelling(): List<String> = Aspect.entries
        .filter { it.spatial }
        .mapNotNull { aspect -> spreads.of(aspect).drawn?.let { "${aspect.page}.${Spread.SEAM}=${it.key}" } }

    /**
     * The options of an aspect that seats no preset, which the loop above cannot reach because it walks
     * presets. A weighted set is exactly that — an Age holds vanilla's whole table and the sentence adjusts
     * it — and so is an aspect that is nothing but its parameters.
     *
     * The one that divides is spelled apart, in [castSpelling]: a divided climate needs a form that says
     * which territory each stretch belongs to, where an Age-wide answer needs no such thing.
     */
    private fun AgeComposition.seatlessSpelling(): List<String> = Aspect.entries
        .filter { it.seatsNothing && !spellsEveryMember(it) }
        .flatMap { aspect -> spelled(aspect, options.of(aspect)) }

    /**
     * Whether this aspect's spelling names each member in turn rather than saying one thing for all of them.
     *
     * **A cast always does**, even at one: its entries *are* its roll, so a one-sun sky that spelled itself
     * as a dial would come back with no sun at all. **A spatial population only does once divided**, since
     * it always has ground for one whatever the book said, and `climate.temperature=…` reads better than a
     * member with a bracket round it.
     */
    private fun AgeComposition.spellsEveryMember(aspect: Aspect): Boolean =
        aspect.membersAreDescribed && (!aspect.spatial || membersIn(aspect) > 1)

    /**
     * `sun=member,member[colour=red]` — a **cast**, one word per member.
     *
     * A body has no name of its own, having been described into being rather than chosen, so [BODY] stands
     * for one and the number of them is the roll. Spelled out rather than counted because the per-member
     * steering has to hang on something, and this is the bracket idiom every territory already uses — which
     * means [read] takes it back with no new machinery.
     *
     * **Without this a cast did not survive the round trip at all**: nothing walks a population's members,
     * so a three-sun Age wrote no `sun=` and rebuilt with the template's one.
     */
    private fun AgeComposition.castSpelling(): List<String> = Aspect.entries
        .filter { spellsEveryMember(it) && membersIn(it) > 0 }
        .map { aspect ->
            val bodies = (0..<membersIn(aspect)).joinToString(LIST_MARK.toString()) { member ->
                BODY + steering(options.of(aspect, member))
            }
            "${aspect.page}=$bodies"
        }

    /** `landmass.arrangement=grid` — one token per parameter, for an aspect whose territories agree. */
    private fun spelled(aspect: Aspect, chosen: Options): List<String> = chosen.chosen.entries.sortedBy { it.key }
        // Comma-joined: several values on one parameter mingle (§3.2), where several presets divide.
        .map { (parameter, options) -> "${aspect.page}.$parameter=${options.joinToString(LIST_MARK.toString())}" }

    /** `[stone=copper;arrangement=grid]` — written against the preset it steers, empty where it says nothing. */
    private fun steering(chosen: Options): String {
        if (chosen.chosen.isEmpty()) return ""
        val written = chosen.chosen.entries.sortedBy { it.key }
            .joinToString(PARAMETER_MARK.toString()) { (parameter, options) ->
                "$parameter=${options.joinToString(LIST_MARK.toString())}"
            }
        return "$STEER_OPEN$written$STEER_CLOSE"
    }

    /**
     * The braced steering in `spires[stone=copper],hills[stone=andesite]`, applied to the territory each was
     * written against. Loud about a malformed brace, like the rest of [read].
     */
    private fun AgeComposition.steeredBy(aspect: Aspect, filling: List<String>): AgeComposition {
        var steered = this
        for ((member, written) in filling.withIndex()) {
            if (STEER_OPEN !in written) continue
            require(written.endsWith(STEER_CLOSE)) { "'$written' opens a $STEER_OPEN and never closes it" }
            val inside = written.substringAfter(STEER_OPEN).dropLast(1)
            for (setting in inside.split(PARAMETER_MARK).filter(String::isNotBlank)) {
                val (parameter, value) = setting.split('=', limit = 2).takeIf { it.size == 2 }
                    ?: error("'$setting' is not `parameter=value`")
                steered = steered.withOptionsFor(aspect, member, parameter, outsideBrackets(value))
            }
        }
        return steered
    }
}

/** Which world the Age is written over, the one token in the spelling that is not an aspect's. */
private const val TEMPLATE = "template"

/** `unstable=42` — an index set by hand, which no contradiction had to earn. */
private const val UNSTABLE = "unstable"

/** What stands for one member of a cast, having no name of its own — see `CompositionSpelling.castSpelling`. */
private const val BODY = "member"

/** How much ground a preset covers, after its name: `rock=caves,porous@0.25`. */
private const val SHARE_MARK = '@'

/** What opens and closes the steering written against one territory: `hills[stone=copper]`. */
private const val STEER_OPEN = '['
private const val STEER_CLOSE = ']'

/** What separates two values of one parameter, which mingle, and two presets, which divide. */
private const val LIST_MARK = ','

/**
 * What separates two parameters inside one territory's brackets.
 *
 * **Deliberately not [LIST_MARK].** It was, and `[stone=copper,tuff]` — the documented spelling for two
 * materials mingled — could not be read back at all, because the split could not tell the second material
 * from a second parameter.
 */
private const val PARAMETER_MARK = ';'

/**
 * [written] split on [LIST_MARK], **except inside brackets** — so `spires[stone=copper,tuff],hills` is two
 * territories and not three.
 */
private fun outsideBrackets(written: String): List<String> {
    val parts = mutableListOf<String>()
    val part = StringBuilder()
    var depth = 0
    for (character in written) {
        when {
            character == STEER_OPEN -> depth++.also { part.append(character) }
            character == STEER_CLOSE -> (--depth).also { part.append(character) }
            character == LIST_MARK && depth == 0 -> {
                parts += part.toString()
                part.clear()
            }

            else -> part.append(character)
        }
    }
    parts += part.toString()
    return parts.filter(String::isNotBlank)
}

/** The share written as [spelled], loud about a thing that is not one for the same reason [named] is. */
private fun readShare(spelled: String): Double = Share.read(spelled)
    ?: error("'$spelled' is not a share. A share is how much ground a preset covers, ${Share.EVEN} being all of it")

/**
 * The preset [aspect] calls [key], or a failure saying what it could have been. Loud rather than lenient,
 * like the rest of the spelling. An open aspect has no list to offer, so it says what shape it wanted
 * instead (design §3.1).
 */
internal inline fun <reified T : Taggable> named(aspect: Aspect, key: String): T {
    val preset = aspect.presetFor(key)
        ?: error(
            if (aspect.open) {
                "'$key' is no ${aspect.page}. An open aspect takes a `namespace:path` id, like `minecraft:water`"
            } else {
                "No ${aspect.page} called '$key'. Try: ${aspect.authored.joinToString(" ") { it.key }}"
            },
        )
    // Cannot happen unless `presetFor` and this call site disagree about the aspect's own type, which the
    // exhaustive `when` in `withSingle` prevents.
    return preset as? T ?: error("The ${aspect.key} aspect answered '$key' with a ${preset::class.simpleName}")
}
