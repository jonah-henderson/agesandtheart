package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Biomes
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Share
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.AspectPreset
import co.voik.agesandtheart.age.aspect.Carvers
import co.voik.agesandtheart.worldgen.biome.ClimateBias
import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.StringRepresentable

/**
 * An Age assembled from one preset per [Aspect] — the composed half of what an Age can be.
 *
 * Named fields rather than a `Map<Aspect, …>`, so that a new aspect is a compile error at every site
 * that must think about it rather than a silently absent key.
 */
data class AgeComposition(
    /** The shapes of the rock. Plural — terrain is spatial (world model §2). Never empty. */
    val terrains: List<Terrain>,
    /** What fills the space the shapes leave. Spatial, so a sea may be two substances at once. */
    val seas: List<Sea> = listOf(Sea.NONE),
    /** What has been cut back out of the rock, and where water stands in it. Spatial too. */
    val carvers: List<Carvers> = listOf(Carvers.SOLID),
    /** Which biomes it grows. Singular — one climate table spans the world however many terrains carve it. */
    val sky: Sky = Sky.PLAIN,
    val options: AspectOptions = AspectOptions(),
    /**
     * How each spatial population is laid across the map — the ground its members cover and the boundary
     * between them (`the-world-model.md` §2).
     *
     * Beside the members rather than inside them, because the same hills are dominant in one Age and
     * scattered in another, and because a seam belongs to the boundary rather than to either side of it.
     */
    val spreads: Spreads = Spreads(),
) {
    /**
     * The coordinates its biomes are looked up at, one per territory. Never empty.
     *
     * **Read off the options rather than stored beside them**, because a climate territory *is* its
     * options: a span per axis, which is what an option already is. Storing them apart made climate the
     * one aspect the composition had to ask about by name, in five places — see
     * [Aspect.membersAreDescribed].
     */
    val climates: List<ClimateBias>
        get() = List(membersIn(Aspect.CLIMATE)) { ClimateBias.of(options.of(Aspect.CLIMATE, it)) }

    /** How [aspect]'s members are laid out, with one share each — never a shorter list. */
    fun spreadOf(aspect: Aspect): Spread = spreads.of(aspect).over(membersIn(aspect))

    /**
     * How many territories [aspect] divides into. Presets answer for themselves; an aspect whose answer is
     * a set of dials counts its own values, there being no preset to count.
     */
    fun membersIn(aspect: Aspect): Int {
        // A described member has no preset to count: its entries *are* the roll — see
        // [Aspect.membersAreDescribed], which covers a cast of suns and a divided climate alike.
        if (!aspect.membersAreDescribed) return presets.count { it.aspect == aspect }
        // **A spatial one always has ground to be somewhere.** An Age nobody said anything about the
        // climate of has one climate; an Age nobody described a sun for has none. Which is the whole of
        // the difference between the two kinds of described member, and it is what tells a climate that
        // could still fracture from one that already has.
        return if (aspect.spatial) maxOf(described(aspect), AT_LEAST_ONE) else described(aspect)
    }

    /**
     * How many members of [aspect] the book described outright, **before any spatial floor** — which is a
     * different question from [membersIn] for exactly the aspects that have one, and the question a
     * template merge has to ask: a climate nobody wrote still has ground, but it has none of its own.
     */
    private fun described(aspect: Aspect): Int = options.allOf(aspect).size

    /** Every preset this composition names, in aspect order — for listing, costing and diagnosis. */
    val presets: List<AspectPreset>
        get() = terrains + seas + carvers + listOf(sky)

    /** The one terrain, where there is only one — for the many places that still reasonably assume so. */
    val terrain: Terrain get() = terrains.first()

    /** Likewise the one sea and carving. */
    val sea: Sea get() = seas.first()
    val carver: Carvers get() = carvers.first()

    /**
     * Options no preset here understands, spelled `terrain.arrangment` — a typo, or a knob a later version
     * dropped. Kept rather than discarded, and surfaced by `/age list`, so a misspelling looks wrong
     * instead of merely doing nothing.
     */
    val unknownOptions: List<String>
        get() = presets.groupBy { it.aspect }.flatMap { (aspect, filling) ->
            filling.flatMapIndexed { member, preset ->
                options.of(aspect, member).unknownTo(preset).map { name -> "${aspect.key}.$name" }
            }
        }.distinct()

    /** How the [member]th preset of [aspect] is steered — what [AgeGeneration] hands each territory. */
    fun optionsFor(aspect: Aspect, member: Int): Options = options.of(aspect, member)

    /** This composition with [aspect] filled by the preset called [key] instead. */
    fun withPreset(aspect: Aspect, key: String): AgeComposition = withPresets(aspect, listOf(key))

    /**
     * This composition with [aspect] filled by the presets named in [keys].
     *
     * A set-valued aspect takes them all; a singular one takes the last rather than refusing, because a
     * pen that validates a sentence makes precision risk-free (design §2). Over-naming is the instability
     * index's business, not an error message's.
     */
    fun withPresets(aspect: Aspect, keys: List<String>, shares: List<Double> = emptyList()): AgeComposition {
        val filled = when (aspect) {
            Aspect.TERRAIN -> copy(terrains = keys.map { named<Terrain>(aspect, it) })
            Aspect.SEA -> copy(seas = keys.map { named<Sea>(aspect, it) })
            Aspect.CARVERS -> copy(carvers = keys.map { named<Carvers>(aspect, it) })
            // A described member names no preset, so a key list can only mean "give it this many" — one
            // `body` per sun, one territory per climate.
            else -> if (aspect.membersAreDescribed) withCastOf(aspect, keys.size) else withSingle(aspect, keys.last())
        }
        return filled.copy(spreads = filled.spreads.withShares(aspect, shares))
    }

    /**
     * This composition with a form drawn for every boundary that has one and was not asked for a form
     * outright — the last thing decided about an Age before its recipe is written down.
     *
     * A population of one has no boundary, so nothing is drawn for it: `landmass.seam=rift` on an
     * undivided Age is kept and shows nothing, which is what a rift with nothing to cut between is.
     *
     * A pure function of the seed, so the same recipe rewritten at the same seed draws the same geology —
     * which is what lets `/age list`'s spelling be pasted back into `/age compose`.
     */
    fun seamed(seed: Long): AgeComposition =
        Aspect.entries.filter { it.spatial }.fold(this) { held, aspect ->
            val spread = held.spreads.of(aspect)
            val nothingToDrawItBetween = held.membersIn(aspect) <= 1
            if (spread.drawn != null || nothingToDrawItBetween) held
            else held.copy(
                spreads = held.spreads.withSeam(aspect, Seam.drawnFor(aspect, Seam.sourceFor(aspect, seed))),
            )
        }

    private fun withSingle(aspect: Aspect, key: String): AgeComposition = when (aspect) {
        Aspect.TERRAIN -> copy(terrains = listOf(named<Terrain>(aspect, key)))
        Aspect.SEA -> copy(seas = listOf(named<Sea>(aspect, key)))
        Aspect.CARVERS -> copy(carvers = listOf(named<Carvers>(aspect, key)))
        Aspect.SKY -> copy(sky = named<Sky>(aspect, key))
        // None of these seats anything: a biome and a structure set are weighed, and a climate and a
        // surface are where their dials were left.
        Aspect.BIOMES, Aspect.STRUCTURES, Aspect.SURFACE, Aspect.FEATURES, Aspect.SPAWNS,
        Aspect.PHENOMENA, Aspect.AIR, Aspect.WATERS, Aspect.WEATHER, Aspect.CLIMATE,
        Aspect.SUN, Aspect.MOON, Aspect.STARS,
        -> this
    }

    /** [aspect] given [count] territories, however that aspect says how many it has. */
    fun withMembers(aspect: Aspect, count: Int): AgeComposition {
        if (aspect.membersAreDescribed) return withCastOf(aspect, count)
        val seated = presets.first { it.aspect == aspect }
        return withPresets(aspect, List(count) { seated.key })
    }

    /**
     * This composition with one more option chosen for [aspect]. Whether the preset understands
     * [parameter] is not asked here — an unrecognised name is kept and reported, never rejected.
     */
    fun withOption(aspect: Aspect, parameter: String, option: String): AgeComposition =
        withOptions(aspect, parameter, listOf(option))

    /**
     * The same, where a writer named several — which for a material means them mingled rather than given
     * a region each (design §3.2). Applies to every territory of the aspect; [withOptionsFor] aims one.
     */
    fun withOptions(aspect: Aspect, parameter: String, chosen: List<String>): AgeComposition {
        asksForASeam(aspect, parameter, chosen)?.let { return copy(spreads = spreads.withSeam(aspect, it)) }
        val everyMember = options.allOf(aspect).ifEmpty { listOf(Options.NONE) }
        return copy(options = options.with(aspect, everyMember.map { Options(it.chosen + (parameter to chosen)) }))
    }

    /**
     * This composition with one option chosen for one territory only — `spires[stone=copper]` beside
     * `hills[stone=andesite]`. A [member] past the end of the filling is written anyway, not dropped.
     */
    fun withOptionsFor(aspect: Aspect, member: Int, parameter: String, chosen: List<String>): AgeComposition {
        // Whichever territory it was written against: a seam belongs to the boundary rather than to either
        // side, so "riven here and whole there" is not something it could mean.
        asksForASeam(aspect, parameter, chosen)?.let { return copy(spreads = spreads.withSeam(aspect, it)) }
        val filling = membersIn(aspect)
        val perMember = options.expanded(aspect, maxOf(filling, member + 1))
        val steered = perMember.mapIndexed { index, existing ->
            if (index == member) Options(existing.chosen + (parameter to chosen)) else existing
        }
        return copy(options = options.with(aspect, steered))
    }

    /**
     * The form `landmass.seam=rift` asks this boundary to take, or null where the token is about something
     * else — how a seam reaches the spread rather than the options, there being nowhere in a preset for a
     * boundary *between* two of them to live.
     *
     * A form this version does not know falls through to the options, where an unrecognised name is kept
     * and reported ([unknownOptions]). Silently taking it would flatten an Age's geology.
     */
    private fun asksForASeam(aspect: Aspect, parameter: String, chosen: List<String>): Seam? {
        if (!aspect.spatial || parameter != Spread.SEAM) return null
        return chosen.firstOrNull()?.let(Seam::named)
    }

    /**
     * This composition laid over [template] — **the template underneath, what the sentence said on top**
     * (`the-world-model.md` §4).
     *
     * Per aspect for what is *seated*, because a preset is one answer and half of one means nothing: an
     * aspect the sentence never mentioned keeps the template's, and one it spoke to is the sentence's
     * outright. Per **parameter** for the steering, because those genuinely compose — a writer who picks
     * the nether and then names a landform keeps its heat and its seal, and only says again what they
     * meant to change.
     *
     * A **cast** comes from underneath only where the book minted nothing: §4's rule is that describing any
     * member clears the template's, so a book that wrote a sun of its own keeps exactly the bodies it
     * minted, and one that wrote none is lit — or left dark — by the world it began from.
     */
    fun laidOver(template: AgeComposition, spokenTo: Set<Aspect>): AgeComposition {
        fun seated(aspect: Aspect, mine: List<AspectPreset>, theirs: List<AspectPreset>) =
            if (aspect in spokenTo) mine else theirs
        val merged = copy(
            terrains = seated(Aspect.TERRAIN, terrains, template.terrains).filterIsInstance<Terrain>(),
            seas = seated(Aspect.SEA, seas, template.seas).filterIsInstance<Sea>(),
            carvers = seated(Aspect.CARVERS, carvers, template.carvers).filterIsInstance<Carvers>(),
            sky = if (Aspect.SKY in spokenTo) sky else template.sky,
        )
        return Aspect.entries.fold(merged) { held, aspect ->
            val boughtItsOwnMembers = aspect.holds == Holds.POPULATION && described(aspect) > 0
            if (boughtItsOwnMembers) held else held.underlaidWith(aspect, template.options.of(aspect))
        }
    }

    /** [aspect]'s options with [beneath]'s filled in wherever this composition said nothing. */
    private fun underlaidWith(aspect: Aspect, beneath: Options): AgeComposition {
        if (beneath.chosen.isEmpty()) return this
        val members = maxOf(membersIn(aspect), 1)
        val laid = options.expanded(aspect, members).map { mine -> Options(beneath.chosen + mine.chosen) }
        return copy(options = options.with(aspect, laid))
    }

    /**
     * This composition with [aspect]'s cast grown to [members] — what a clause that minted a body and said
     * nothing else about it writes.
     *
     * A body with no properties still has to be *there*, and a population's stored entries are its roll
     * ([membersIn]), so an empty entry is how the roll records one. Never shrinks: a sentence adds bodies
     * and nothing takes them away.
     */
    fun withCastOf(aspect: Aspect, members: Int): AgeComposition {
        if (members <= membersIn(aspect)) return this
        return copy(options = options.with(aspect, options.expanded(aspect, members)))
    }

    /**
     * How a writer would have said it: `terrain=hills terrain.arrangement=grid sea=water …`.
     *
     * Exactly the spelling [parse] reads, so `/age list` output pastes back into `/age compose` and the
     * pair can be checked by round trip (`RecipeCheck`). Every aspect is named even at its default.
     */
    override fun toString(): String = presets.groupBy { it.aspect }.entries
        .sortedBy { (aspect, _) -> aspect.ordinal }
        .flatMap { (aspect, filling) ->
            // Territories that agree are spelled once for the whole aspect; only differing ones pay for braces.
            val aimed = options.allOf(aspect).size > 1
            val written = filling.mapIndexed { index, preset ->
                // A share is only spelled where it says something: an even division, and the largest share
                // of an uneven one, are both left unsaid.
                val share = spreads.of(aspect).shares.getOrNull(index)
                val named =
                    if (share == null || Share.isEven(share)) preset.key else "${preset.key}$SHARE_MARK$share"
                if (aimed) named + steering(options.of(aspect, index)) else named
            }
            val slotWide = if (aimed) emptyList() else spelled(aspect, options.of(aspect))
            listOf("${aspect.key}=${written.joinToString(",")}") + slotWide
        }.plus(castSpelling()).plus(seatlessSpelling()).plus(seamSpelling())
        .joinToString(" ")

    /**
     * `landmass.seam=rift` — the form drawn for each boundary the Age has one for.
     *
     * Spelled even where it was drawn rather than asked for, because a recipe records what an Age *is*: the
     * draw is reproducible from the seed, but a spelling that left it out would read as "nothing was
     * decided here" and could not tell a requested shear from an unremarked one.
     */
    private fun seamSpelling(): List<String> = Aspect.entries
        .filter { it.spatial }
        .mapNotNull { aspect ->
            spreads.of(aspect).drawn?.let { "${aspect.key}.${Spread.SEAM}=${it.key}" }
        }

    /**
     * The options of an aspect that seats no preset, which the loop above cannot reach because it walks
     * presets. A population is exactly that — an Age holds vanilla's whole table and the sentence adjusts
     * it — and so is an aspect that is nothing but its dials.
     *
     * The one that divides is spelled apart, in [climateSpelling]: a fractured climate needs a form that
     * says which territory each stretch belongs to, where an Age-wide answer needs no such thing.
     */
    private fun seatlessSpelling(): List<String> = Aspect.entries
        .filter { it.seatsNothing && !spellsEveryMember(it) }
        .flatMap { aspect -> spelled(aspect, options.of(aspect)) }

    /**
     * Whether this aspect's spelling names each member in turn rather than saying one thing for all of
     * them.
     *
     * **A population always does**, even at one: its entries *are* its roll, so a one-sun sky that spelled
     * itself as a dial would come back with no sun at all. **A climate only does once divided**, since it
     * always has exactly one territory until something fractures it, and `climate.temperature=…` reads
     * better than a member with a bracket round it.
     */
    private fun spellsEveryMember(aspect: Aspect): Boolean =
        aspect.holds == Holds.POPULATION || (aspect.membersAreDescribed && membersIn(aspect) > 1)

    /**
     * `sun=body,body[suncolour=red]` — a **cast**, one word per member.
     *
     * A body has no name of its own, having been described into being rather than chosen, so [BODY] stands
     * for one and the number of them is the roll. Spelled out rather than counted because the per-member
     * steering has to hang on something, and this is the bracket idiom every territory already uses — which
     * means [parse] reads it back with no new machinery.
     *
     * **Without this a cast did not survive the round trip at all**: nothing walks a population's members,
     * so a three-sun Age wrote no `sun=` and rebuilt with the template's one.
     */
    private fun castSpelling(): List<String> = Aspect.entries
        .filter { spellsEveryMember(it) && membersIn(it) > 0 }
        .map { aspect ->
            val bodies = (0..<membersIn(aspect)).joinToString(",") { member ->
                BODY + steering(options.of(aspect, member))
            }
            "${aspect.key}=$bodies"
        }

    /** `terrain.arrangement=grid` — one token per parameter, for an aspect whose territories agree. */
    private fun spelled(aspect: Aspect, chosen: Options): List<String> = chosen.chosen.entries.sortedBy { it.key }
        // Comma-joined: several values on one parameter mingle (§3.2), where several presets divide.
        .map { (parameter, options) -> "${aspect.key}.$parameter=${options.joinToString(",")}" }

    /** `[stone=copper,arrangement=grid]` — written against the preset it steers, and empty where it says nothing. */
    private fun steering(chosen: Options): String {
        if (chosen.chosen.isEmpty()) return ""
        val written = chosen.chosen.entries.sortedBy { it.key }
            .joinToString(PARAMETER_MARK.toString()) { (parameter, options) ->
                "$parameter=${options.joinToString(LIST_MARK.toString())}"
            }
        return "$STEER_OPEN$written$STEER_CLOSE"
    }

    companion object {
        /**
         * A composition written out as `terrain=hills sea=water structures=vanilla` — the debug spelling
         * of a sentence, and the shape `/age compose` takes.
         *
         * Fails loudly on anything unrecognised, because this is a command and a typo here is a mistake.
         * The pen proper must never behave this way (design §2): validation would make precision risk-free.
         */
        fun parse(specification: String): Result<AgeComposition> = runCatching {
            // A stand-in, so options may be read in any order relative to the presets they steer. Either
            // the sentence names a terrain over the top of it, or it is rejected below for naming none.
            var composition = AgeComposition(terrains = listOf(Terrain.SHAPES))
            var namedALandform = false

            for (token in specification.split(' ').filter(String::isNotBlank)) {
                val (key, value) = token.split('=', limit = 2).takeIf { it.size == 2 }
                    ?: error("'$token' is not `key=value`")
                val aspect = Aspect.entries.firstOrNull { key.substringBefore('.') == it.key }
                    ?: error("No aspect called '${key.substringBefore('.')}'. Slots: ${Aspect.entries.joinToString(" ") { it.key }}")

                composition = if ('.' in key) {
                    composition.withOptions(
                        aspect,
                        key.substringAfter('.'),
                        outsideBrackets(value),
                    )
                } else {
                    namedALandform = namedALandform || aspect == Aspect.TERRAIN
                    // Commas are how a set-valued aspect is written: `terrain=hills,pillars`. An `@` after
                    // a preset is how much ground it covers: `carvers=caves,porous@0.25`. Brackets after
                    // that steer that territory alone: `terrain=spires[stone=copper],hills`.
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
            require(namedALandform) { "An Age needs a terrain. Try `terrain=${Terrain.HILLS.key}`" }
            // Vanilla's rock answers for the whole world or for none of it — the field tree and vanilla's
            // router are either/or — so it cannot stand as one territory among several. Said here rather
            // than left to the generator, which has no way to report it and used to throw instead.
            val ourOwnRockBeside = composition.terrains.filter { it != Terrain.VANILLA }
            val sharesTheWorld = Terrain.VANILLA in composition.terrains && ourOwnRockBeside.isNotEmpty()
            require(!sharesTheWorld) {
                "`${Aspect.TERRAIN.key}=${Terrain.VANILLA.key}` is the whole world's rock and cannot " +
                    "divide it with ${ourOwnRockBeside.joinToString(" ") { it.key }}"
            }
            composition
        }

        /**
         * The braced steering in `spires[stone=copper],hills[stone=andesite]`, applied to the territory
         * each was written against. Loud about a malformed brace, like the rest of [parse].
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
                    steered = steered.withOptionsFor(
                        aspect,
                        member,
                        parameter,
                        outsideBrackets(value),
                    )
                }
            }
            return steered
        }

        val MAP_CODEC: MapCodec<AgeComposition> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                setOrSingle(enumCodec<Terrain>(), Terrain.SHAPES)
                    .fieldOf("terrain").forGetter(AgeComposition::terrains),
                setOrSingle(presetCodec<Sea>(Aspect.SEA), Sea.NONE)
                    .optionalFieldOf("sea", listOf(Sea.NONE)).forGetter(AgeComposition::seas),
                setOrSingle(enumCodec<Carvers>(), Carvers.SOLID)
                    .optionalFieldOf("carvers", listOf(Carvers.SOLID))
                    .forGetter(AgeComposition::carvers),
                enumCodec<Sky>().optionalFieldOf("sky", Sky.PLAIN).forGetter(AgeComposition::sky),
                AspectOptions.CODEC.optionalFieldOf("options", AspectOptions()).forGetter(AgeComposition::options),
                Spreads.CODEC.optionalFieldOf("spread", Spreads()).forGetter(AgeComposition::spreads),
            ).apply(instance, ::AgeComposition)
        }
    }
}

/**
 * The options chosen for each aspect, one set per territory — apart per aspect so two presets may both
 * offer a `depth`, and apart per member so two presets in one aspect may be steered differently.
 *
 * A lone entry answers for every member, which is what makes the agreeing case cost nothing; [with]
 * collapses agreeing entries back to one so there is a single spelling for it.
 */
data class AspectOptions(private val bySlot: Map<Aspect, List<Options>> = emptyMap()) {
    /** What was chosen for the [member]th preset of [aspect] — a lone entry answering for all of them. */
    fun of(aspect: Aspect, member: Int = 0): Options {
        val perMember = bySlot[aspect].orEmpty()
        return perMember.singleOrNull() ?: perMember.getOrElse(member) { Options.NONE }
    }

    /** Every member's options as stored — one entry where they agree, one per member where they differ. */
    fun allOf(aspect: Aspect): List<Options> = bySlot[aspect].orEmpty()

    /** The same, padded out to one entry per member, so a caller may steer any of them. */
    fun expanded(aspect: Aspect, members: Int): List<Options> = List(members) { member -> of(aspect, member) }

    fun with(aspect: Aspect, perMember: List<Options>): AspectOptions {
        // **A described member keeps its entry, however alike they are, and even when it says nothing.**
        // The entries *are* the roll: two identical red suns collapsed to one would be one sun, and two
        // halves of a fractured climate that happen to agree would be one climate — which is how a
        // fracture came back undivided the moment climate started storing its members here.
        if (aspect.membersAreDescribed) {
            return AspectOptions(if (perMember.isEmpty()) bySlot - aspect else bySlot + (aspect to perMember))
        }
        // Collapsed on the way in, so the round trip has one spelling to reproduce.
        val collapsed = if (perMember.distinct().size == 1) perMember.take(1) else perMember
        val saysNothing = collapsed.all { it.chosen.isEmpty() }
        return AspectOptions(if (saysNothing) bySlot - aspect else bySlot + (aspect to collapsed))
    }

    companion object {
        /** A lone entry is written bare — the same either-or trick as [setOrSingle] and [Options.CODEC]. */
        val CODEC: Codec<AspectOptions> = Codec.unboundedMap(
            StringRepresentable.fromEnum(Aspect::values),
            Codec.either(Options.CODEC.listOf(), Options.CODEC).xmap(
                { either -> either.map({ many -> many }, ::listOf) },
                { many -> if (many.size == 1) Either.right(many.first()) else Either.left(many) },
            ),
        ).xmap(::AspectOptions, AspectOptions::bySlot)
    }
}

/**
 * How much ground a preset covers, written after it: `carvers=caves,porous@0.25`.
 *
 * Not a colon: a colon tells a registry id from an authored key (`namesReferent`), so `sea=minecraft:air`
 * read as the preset `minecraft` covering an `air` share. Only ever a command spelling — shares persist
 * as their own codec field.
 */
/**
 * What one described member is called in a recipe, having no name of its own — a sun, or one territory of
 * a divided climate. See `castSpelling`; `parse` reads the count and never the token, so a recipe written
 * with any other word still loads.
 */
private const val BODY = "member"

/** What a spatial aspect always has, however little the sentence said — see [AgeComposition.membersIn]. */
private const val AT_LEAST_ONE = 1

private const val SHARE_MARK = '@'

/**
 * How one territory's own steering is written: `terrain=spires[stone=copper],hills[stone=andesite]`.
 *
 * **Square brackets and commas, which is Minecraft's own idiom** for data hung on a named thing —
 * `oak_stairs[facing=north,half=top]` reads exactly this way — so a pack author brings the punctuation
 * with them. It costs a comma that has to know its depth, which [outsideBrackets] answers.
 *
 * Only ever a command spelling; options persist as their own codec field.
 */
private const val STEER_OPEN = '['
private const val STEER_CLOSE = ']'

/** What separates one territory from the next, and one value of a parameter from the next. */
private const val LIST_MARK = ','

/**
 * Parameters within one territory's brackets: `[stone=copper,tuff;mingling=0.9]`.
 *
 * **Not [LIST_MARK], which is what separates a parameter's own values.** `[stone=copper,tuff]` is one
 * parameter holding two stones and was read as two parameters, the second of which is not `name=value` —
 * so the documented spelling for mingled materials could not be read back. The bug predates the climate
 * moving in here and was reachable the moment anything spelled two values inside a bracket.
 */
private const val PARAMETER_MARK = ';'

/**
 * [written] split on the commas that are **not inside brackets** — the one thing sharing a separator costs.
 *
 * `terrain=spires[stone=copper,tuff],hills` is two territories rather than three: the comma between the
 * stones belongs to the steering it sits inside.
 */
private fun outsideBrackets(written: String): List<String> {
    val parts = mutableListOf<String>()
    val part = StringBuilder()
    var depth = 0
    for (character in written) {
        when {
            character == STEER_OPEN -> depth++
            character == STEER_CLOSE -> depth--
            character == LIST_MARK && depth == 0 -> {
                parts += part.toString()
                part.clear()
                continue
            }
        }
        part.append(character)
    }
    parts += part.toString()
    return parts.filter(String::isNotBlank)
}

/** The share written as [spelled], loud about a thing that is not one for the same reason [named] is. */
private fun readShare(spelled: String): Double = Share.read(spelled)
    ?: error("'$spelled' is not a share. A share is how much ground a preset covers, ${Share.EVEN} being all of it")

/**
 * The preset [aspect] calls [key], or a failure saying what it could have been. Loud rather than lenient,
 * like [AgeComposition.Companion.parse]. An open aspect has no list to offer, so it says what shape it
 * wanted instead (design §3.1).
 */
private inline fun <reified T : AspectPreset> named(aspect: Aspect, key: String): T {
    val preset = aspect.presetFor(key)
        ?: error(
            if (aspect.open) {
                "'$key' is no ${aspect.key}. An open aspect takes a `namespace:path` id, like `minecraft:water`"
            } else {
                "No ${aspect.key} called '$key'. Try: ${aspect.authored.joinToString(" ") { it.key }}"
            },
        )
    // Cannot happen unless `presetFor` and this call site disagree about the aspect's own type, which the
    // exhaustive `when` in `withSingle` prevents.
    return preset as? T ?: error("The ${aspect.key} aspect answered '$key' with a ${preset::class.simpleName}")
}

/**
 * A set-valued aspect's codec: reads a list, and also a bare single value. Writes a bare value back when
 * there is only one. Never empty — [fallback] stands in rather than letting the recipe describe nothing.
 */
private fun <T> setOrSingle(single: Codec<T>, fallback: T): Codec<List<T>> =
    Codec.either(single.listOf(), single).xmap(
        { either -> either.map({ many -> many.ifEmpty { listOf(fallback) } }, ::listOf) },
        { many -> if (many.size == 1) Either.right(many.first()) else Either.left(many) },
    )

/** A codec over any of our aspect-preset enums, which all serialise by their own [AspectPreset.key]. */
private inline fun <reified E> enumCodec(): Codec<E> where E : Enum<E>, E : StringRepresentable =
    StringRepresentable.fromEnum { enumValues<E>() }

/**
 * A codec over one aspect's presets, reading and writing the same key `/age compose` spells.
 *
 * What an open aspect needs that [enumCodec] cannot give it: its legal values are not knowable in advance,
 * so the key is handed to the aspect to interpret. Going through [Aspect.presetFor] is what stops a recipe,
 * a `preset_tags` file and a command from disagreeing about how a preset is spelled.
 */
private inline fun <reified T : AspectPreset> presetCodec(aspect: Aspect): Codec<T> = Codec.STRING.comapFlatMap(
    { key ->
        when (val preset = aspect.presetFor(key)) {
            is T -> DataResult.success(preset)
            else -> DataResult.error { "'$key' is no ${aspect.key}" }
        }
    },
    AspectPreset::key,
)
