package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.ownParameters
import co.voik.agesandtheart.age.aspect.Biomes
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Underground
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Share
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.age.aspect.AgeParts
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Holds
import co.voik.agesandtheart.age.aspect.Taggable
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
    /**
     * What is built into the rock beneath the surface. Singular — an underground claims a *band* of the
     * world, and an Age has one set of heights however many territories divide its rock.
     */
    val underground: Underground = Underground.NOISE_CAVES,
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
) : AgeParts {
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
     * Solid to the ceiling, and so shut overhead whether or not a book said the word.
     *
     * **Every territory, not any of them** — being roofed is one fact for the whole Age, since a dimension
     * type is one file and a lid is one field. `solid and hills landmass` divides the world between rock
     * to the ceiling and open hills, and reading `any` there put a bedrock roof and no skylight over the
     * hills as well. Where only part of the world closes itself, the Age has a sky, and a writer who wants
     * it shut says so — which then lays `CeilingField` over the half that needs one.
     */
    override val roofedByItsRock: Boolean get() =
        terrains.isNotEmpty() && terrains.all { it.roofsTheWorld }

    /** Whether any territory's land hangs over the void — see [Terrain.hasGroundBeneath]. */
    val hasNothingBeneathIt: Boolean get() = terrains.any { !it.hasGroundBeneath }

    /** Every territory's, for the same reason as [roofedByItsRock]: an Age has one cloud deck. */
    override val cloudsAtY: Int? get() =
        terrains.map { it.cloudsAtY }.distinct().singleOrNull()

    /**
     * How many territories [aspect] divides into. Presets answer for themselves; an aspect whose answer is
     * a set of parameters counts its own values, there being no preset to count.
     */
    override fun membersIn(aspect: Aspect): Int {
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
    val presets: List<Taggable>
        get() = terrains + seas + carvers + listOf(underground, sky)

    /**
     * Options no preset here understands, spelled `terrain.arrangment` — a typo, or a parameter a later version
     * dropped. Kept rather than discarded, and surfaced by `/age list`, so a misspelling looks wrong
     * instead of merely doing nothing.
     */
    val unknownOptions: List<String>
        get() = Aspect.entries.flatMap { aspect ->
            val seated = presets.filter { it.aspect == aspect }
            options.allOf(aspect).indices.flatMap { member ->
                // A seatless aspect understands its parameters and nothing else — and was never asked at all,
                // so `sun.size` could be misspelled *and* misvalued in silence.
                val understood = seated.getOrNull(member)?.ownParameters.orEmpty() + aspect.parameters
                // **And its pool, which is not one of its parameters.** `Aspect.pool` was split out of
                // `parameters` and this was not told, so every aspect holding a population reported its own
                // population as unrecognised — `spawns.lives=nothing` read as a typo while it was working.
                val pooled = setOfNotNull(aspect.pool?.name)
                val here = options.of(aspect, member)
                (here.unknownAmong(understood) - pooled).map { name -> "${aspect.page}.$name" } +
                    here.unreadableAmong(understood).map { (name, value) -> "${aspect.page}.$name=$value" }
            }
        }.distinct()

    /**
     * How the [member]th preset of [aspect] is steered — what
     * [co.voik.agesandtheart.generation.AgeGeneration] hands each territory.
     */
    override fun optionsFor(aspect: Aspect, member: Int): Options = options.of(aspect, member)

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
            Aspect.UNDERGROUND -> copy(underground = named<Underground>(aspect, keys.last()))
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
        Aspect.UNDERGROUND -> copy(underground = named<Underground>(aspect, key))
        Aspect.SKY -> copy(sky = named<Sky>(aspect, key))
        // None of these seats anything: a biome and a structure set are weighed, and a climate and a
        // surface are where their parameters were left.
        Aspect.BIOMES, Aspect.STRUCTURES, Aspect.SURFACE, Aspect.FEATURES, Aspect.SPAWNS,
        Aspect.PHENOMENA, Aspect.AIR, Aspect.WATERS, Aspect.WEATHER, Aspect.CLIMATE,
        Aspect.SUN, Aspect.MOON, Aspect.STARS, Aspect.GRASS, Aspect.LEAVES, Aspect.CLOUD,
        Aspect.AURORA, Aspect.RAINBOW,
        -> this
    }

    /** [aspect] given [count] territories, however that aspect says how many it has. */
    fun withMembers(aspect: Aspect, count: Int): AgeComposition {
        if (aspect.membersAreDescribed) return withCastOf(aspect, count)
        val seated = presets.first { it.aspect == aspect }
        return withPresets(aspect, List(count) { seated.key })
    }

    /**
     * This composition with [chosen] as [aspect]'s options for [parameter] — several of them, for a material,
     * meaning them mingled rather than given a region each (design §3.2). Whether the preset understands
     * [parameter] is not asked here: an unrecognised name is kept and reported, never rejected. Applies to
     * every territory of the aspect; [withOptionsFor] aims one.
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
        fun seated(aspect: Aspect, mine: List<Taggable>, theirs: List<Taggable>) =
            if (aspect in spokenTo) mine else theirs
        val merged = copy(
            terrains = seated(Aspect.TERRAIN, terrains, template.terrains).filterIsInstance<Terrain>(),
            seas = seated(Aspect.SEA, seas, template.seas).filterIsInstance<Sea>(),
            carvers = seated(Aspect.CARVERS, carvers, template.carvers).filterIsInstance<Carvers>(),
            underground = if (Aspect.UNDERGROUND in spokenTo) underground else template.underground,
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
     * How a writer would have said it — `landmass=hills landmass.arrangement=grid sea=water …`.
     *
     * The format itself is [CompositionSpelling], which owns both halves of it. This is here because a
     * composition prints in a report and in `/age list`, and it cannot say which world it was written over:
     * that is the recipe's, so only [CompositionSpelling.spell] writes the whole of what `/age compose`
     * would take back.
     */
    override fun toString(): String = with(CompositionSpelling) { tokens().joinToString(" ") }

    companion object {
        /**
         * The composition [specification] describes, dropping whatever else it says.
         *
         * Kept because twenty-odd callers want only this. `/age compose` reads
         * [CompositionSpelling.read] instead, which also answers which world the Age was written over.
         */
        fun parse(specification: String): Result<AgeComposition> =
            CompositionSpelling.read(specification).map { it.composition }

        val MAP_CODEC: MapCodec<AgeComposition> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                setOrSingle(enumCodec<Terrain>(), Terrain.OVERWORLD)
                    .fieldOf("terrain").forGetter(AgeComposition::terrains),
                setOrSingle(presetCodec<Sea>(Aspect.SEA), Sea.NONE)
                    .optionalFieldOf("sea", listOf(Sea.NONE)).forGetter(AgeComposition::seas),
                setOrSingle(enumCodec<Carvers>(), Carvers.SOLID)
                    .optionalFieldOf("carvers", listOf(Carvers.SOLID))
                    .forGetter(AgeComposition::carvers),
                enumCodec<Underground>().optionalFieldOf("underground", Underground.NOISE_CAVES)
                    .forGetter(AgeComposition::underground),
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
    /**
     * What was chosen for the [member]th preset of [aspect] — a lone entry answering for all of them.
     *
     * **A described member never borrows another's.** Its entries *are* the roll ([with] keeps them apart
     * for that reason), so a lone entry is the first body's own and says nothing about the second: a sky
     * with one sun sized and one not is not a sky with two sized suns. Broadcasting is what an aspect whose
     * territories *agree* wants, and only that.
     */
    fun of(aspect: Aspect, member: Int = 0): Options {
        val perMember = bySlot[aspect].orEmpty()
        if (aspect.membersAreDescribed) return perMember.getOrElse(member) { Options.NONE }
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

/** A spatial population always has ground for one member, whatever the book said — see [AgeComposition.membersIn]. */
private const val AT_LEAST_ONE = 1

/**
 * A set-valued aspect's codec: reads a list, and also a bare single value. Writes a bare value back when
 * there is only one. Never empty — [fallback] stands in rather than letting the recipe describe nothing.
 */
private fun <T> setOrSingle(single: Codec<T>, fallback: T): Codec<List<T>> =
    Codec.either(single.listOf(), single).xmap(
        { either -> either.map({ many -> many.ifEmpty { listOf(fallback) } }, ::listOf) },
        { many -> if (many.size == 1) Either.right(many.first()) else Either.left(many) },
    )

/** A codec over any of our aspect-preset enums, which all serialise by their own [Taggable.key]. */
private inline fun <reified E> enumCodec(): Codec<E> where E : Enum<E>, E : StringRepresentable =
    StringRepresentable.fromEnum { enumValues<E>() }

/**
 * A codec over one aspect's presets, reading and writing the same key `/age compose` spells.
 *
 * What an open aspect needs that [enumCodec] cannot give it: its legal values are not knowable in advance,
 * so the key is handed to the aspect to interpret. Going through [Aspect.presetFor] is what stops a recipe,
 * a `preset_tags` file and a command from disagreeing about how a preset is spelled.
 */
private inline fun <reified T : Taggable> presetCodec(aspect: Aspect): Codec<T> = Codec.STRING.comapFlatMap(
    { key ->
        when (val preset = aspect.presetFor(key)) {
            is T -> DataResult.success(preset)
            else -> DataResult.error { "'$key' is no ${aspect.key}" }
        }
    },
    Taggable::key,
)
