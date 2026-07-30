package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Biomes
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Share
import co.voik.agesandtheart.age.aspect.Climate
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.AspectPreset
import co.voik.agesandtheart.age.aspect.Carvers
import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.StringRepresentable

/**
 * An Age assembled from one preset per [Aspect] — the composed half of what an Age can be.
 *
 * Named fields rather than a `Map<Aspect, …>`, which the plan had sketched, because the map buys
 * extensibility we do not need yet and costs the two things that matter more: `composition.terrain`
 * reads as English where `composition[Aspect.TERRAIN]!!` does not, and a new aspect becomes a compile
 * error at every site that must think about it rather than a silently absent key. Adding a aspect is a
 * codec field with a default, which is cheap; the map can come back if the resolver turns out to want
 * generic iteration badly enough to pay for it.
 */
data class AgeComposition(
    /**
     * The shapes of the rock — **plural**, because terrain is a *positional* aspect and so holds a set
     * (§3.4). Two terrains divide the world between them, which is how a contradiction gets satisfied
     * by coexistence rather than by one term silently going missing.
     *
     * Never empty; [parse] and the codec both guarantee at least one.
     */
    val terrains: List<Terrain>,
    /** What fills the space the shapes leave — positional, so a sea may be two substances at once. */
    val seas: List<Sea> = listOf(Sea.NONE),
    /** What has been cut back out of the rock, and where water stands in it. Positional too. */
    val carvers: List<Carvers> = listOf(Carvers.SOLID),
    /**
     * Which biomes it grows — **singular**, because one climate table spans the world however many terrains
     * carve it up, and vanilla's table already holds many biomes natively (design §3.1).
     */
    val biomes: Biomes = Biomes.VANILLA,
    val sky: Sky = Sky.PLAIN,
    /**
     * What may be built here — singular, and **[Structures.NONE] by default**, which is what keeps
     * structures opt-in per Age (see [Structures], and the generator's `createState`).
     */
    val structures: Structures = Structures.NONE,
    /**
     * The coordinates its biomes are looked up at — **plural**, because two words bounding one axis to
     * stretches that do not overlap fracture the climate and take a region each (see [Climate]).
     *
     * Never empty; the codec and every writer guarantee at least [Climate.NATURAL], which narrows nothing.
     */
    val climates: List<Climate> = listOf(Climate.NATURAL),
    val options: AspectOptions = AspectOptions(),
    /**
     * How much ground each preset of a set-valued aspect covers, where they do not cover it equally.
     *
     * Kept beside the presets rather than inside them, like [options], because a share is not a property of
     * a preset — the same hills are dominant in one Age and scattered in another. Empty for a aspect means an
     * even division, which is what a hand-composed Age gets.
     */
    val shares: SlotShares = SlotShares(),
) {
    /**
     * The share each of [aspect]'s presets covers, one per preset — an even division where none was named.
     *
     * The shape generation wants: a share per member, never a shorter list, so a map can be built without
     * asking whether anyone said anything about it.
     */
    fun sharesOf(aspect: Aspect): List<Share> {
        val filling = presets.count { it.aspect == aspect }
        val named = shares.of(aspect)
        return List(filling) { member -> named.getOrElse(member) { Share.DOMINANT } }
    }

    /** Every preset this composition names, in aspect order — for listing, costing and diagnosis. */
    val presets: List<AspectPreset>
        get() = terrains + seas + carvers + listOf(biomes, sky, structures) + climates

    /** The one terrain, where there is only one — for the many places that still reasonably assume so. */
    val terrain: Terrain get() = terrains.first()

    /** Likewise the one sea and carving. */
    val sea: Sea get() = seas.first()
    val carver: Carvers get() = carvers.first()

    /**
     * Options no preset here understands, spelled `terrain.arrangment` — a typo, or a knob some later
     * version dropped. Kept in the recipe rather than discarded, and surfaced by `/age list` so that a
     * misspelling looks wrong instead of merely doing nothing. See [Options].
     */
    val unknownOptions: List<String>
        get() = presets.groupBy { it.aspect }.flatMap { (aspect, filling) ->
            filling.flatMapIndexed { member, preset ->
                options.of(aspect, member).unknownTo(preset).map { name -> "${aspect.key}.$name" }
            }
        }.distinct()

    /** How the [member]th preset of [aspect] is steered — what [AgeGeneration] hands each territory. */
    fun optionsFor(aspect: Aspect, member: Int): Options = options.of(aspect, member)

    /**
     * This composition with [aspect] filled by the preset called [key] instead.
     *
     * The `when` is exhaustive over [Aspect], which is the whole reason the fields are named rather than
     * a map: adding a aspect stops the build here, at the one place that must learn how to fill it.
     */
    fun withPreset(aspect: Aspect, key: String): AgeComposition = withPresets(aspect, listOf(key))

    /**
     * This composition with [aspect] filled by the presets named in [keys].
     *
     * A set-valued aspect takes them all; a singular one takes the last, rather than refusing. Refusing
     * would be the pen validating a sentence, which §2 forbids on the grounds that it makes precision
     * risk-free — and "sky=plain,storm" is a writer asking for something the world cannot be, which is
     * a job for the instability index rather than for an error message.
     */
    fun withPresets(aspect: Aspect, keys: List<String>, shares: List<Share> = emptyList()): AgeComposition {
        val filled = when (aspect) {
            Aspect.TERRAIN -> copy(terrains = keys.map { named<Terrain>(aspect, it) })
            Aspect.SEA -> copy(seas = keys.map { named<Sea>(aspect, it) })
            Aspect.CARVERS -> copy(carvers = keys.map { named<Carvers>(aspect, it) })
            Aspect.CLIMATE -> copy(climates = keys.map { named<Climate>(aspect, it) })
            else -> withSingle(aspect, keys.last())
        }
        return filled.copy(shares = filled.shares.with(aspect, shares))
    }

    private fun withSingle(aspect: Aspect, key: String): AgeComposition = when (aspect) {
        Aspect.TERRAIN -> copy(terrains = listOf(named<Terrain>(aspect, key)))
        Aspect.SEA -> copy(seas = listOf(named<Sea>(aspect, key)))
        Aspect.CARVERS -> copy(carvers = listOf(named<Carvers>(aspect, key)))
        Aspect.BIOMES -> copy(biomes = named<Biomes>(aspect, key))
        Aspect.SKY -> copy(sky = named<Sky>(aspect, key))
        Aspect.STRUCTURES -> copy(structures = named<Structures>(aspect, key))
        Aspect.CLIMATE -> copy(climates = listOf(named<Climate>(aspect, key)))
    }

    /**
     * This composition with one more option chosen for [aspect].
     *
     * Whether the preset in that aspect understands [parameter] is not asked here. An unrecognised name
     * is kept and reported rather than rejected — see [Options] for why a recipe must never quietly
     * lose part of itself.
     */
    fun withOption(aspect: Aspect, parameter: String, option: String): AgeComposition =
        withOptions(aspect, parameter, listOf(option))

    /**
     * The same, where a writer named several — which for a material means them **mingled** through one
     * another rather than given a region each (design §3.2).
     *
     * Applies to **every** territory of the aspect, which is what an unaimed word means: "the rock is
     * blackstone" said of a world with two terrains is said of both. Aiming one territory in particular is
     * [withOptionsFor], and needs the grammar to say which (design §4.3.1).
     */
    fun withOptions(aspect: Aspect, parameter: String, chosen: List<String>): AgeComposition {
        val everyMember = options.allOf(aspect).ifEmpty { listOf(Options.NONE) }
        return copy(options = options.with(aspect, everyMember.map { Options(it.chosen + (parameter to chosen)) }))
    }

    /**
     * This composition with one option chosen for **one territory only** — `spires{stone=copper}` beside
     * `hills{stone=andesite}`.
     *
     * The whole reason [AspectOptions] is per member. A [member] past the end of the aspect's filling is written
     * anyway rather than dropped, for the reason an unrecognised parameter name is kept: a recipe is the only
     * record of an Age and must never quietly lose part of itself.
     */
    fun withOptionsFor(aspect: Aspect, member: Int, parameter: String, chosen: List<String>): AgeComposition {
        val filling = presets.count { it.aspect == aspect }
        val perMember = options.expanded(aspect, maxOf(filling, member + 1))
        val steered = perMember.mapIndexed { index, existing ->
            if (index == member) Options(existing.chosen + (parameter to chosen)) else existing
        }
        return copy(options = options.with(aspect, steered))
    }

    /**
     * How a writer would have said it: `terrain=hills terrain.arrangement=grid sea=water …`.
     *
     * Deliberately the exact spelling [parse] reads, so that what `/age list` prints can be pasted
     * straight back into `/age compose` — and so the pair can be checked by round-trip rather than by
     * eye (`:common:recipecheck`). Every aspect is named even where it holds its default, since a recipe
     * is the only record of an Age and "what did this leave unsaid?" is the wrong question to have to
     * ask of one.
     */
    override fun toString(): String = presets.groupBy { it.aspect }.entries
        .sortedBy { (aspect, _) -> aspect.ordinal }
        .flatMap { (aspect, filling) ->
            // Territories that agree are spelled once for the whole aspect, the way they were before options
            // were per member; only a aspect whose territories differ pays for the braces.
            val aimed = options.allOf(aspect).size > 1
            val written = filling.mapIndexed { index, preset ->
                // A share is only spelled where it says something: an even division, and the largest share
                // of an uneven one, are both left unsaid so that the common case reads as it always did.
                val share = shares.of(aspect).getOrNull(index)
                val named = if (share == null || share == Share.DOMINANT) preset.key else "${preset.key}$SHARE_MARK${share.key}"
                if (aimed) named + steering(options.of(aspect, index)) else named
            }
            val slotWide = if (aimed) emptyList() else spelled(aspect, options.of(aspect))
            listOf("${aspect.key}=${written.joinToString(",")}") + slotWide
        }.joinToString(" ")

    /** `terrain.arrangement=grid` — one token per parameter, for a aspect whose territories agree. */
    private fun spelled(aspect: Aspect, chosen: Options): List<String> = chosen.chosen.entries.sortedBy { it.key }
        // Comma-joined like a set-valued aspect, and read back the same way — several values on one parameter
        // mingle (§3.2), where several presets on one aspect divide.
        .map { (parameter, options) -> "${aspect.key}.$parameter=${options.joinToString(",")}" }

    /** `{stone=copper;arrangement=grid}` — written against the preset it steers, and empty where it says nothing. */
    private fun steering(chosen: Options): String {
        if (chosen.chosen.isEmpty()) return ""
        val written = chosen.chosen.entries.sortedBy { it.key }
            .joinToString(PARAMETER_MARK.toString()) { (parameter, options) -> "$parameter=${options.joinToString(",")}" }
        return "$STEER_OPEN$written$STEER_CLOSE"
    }

    companion object {
        /**
         * A composition written out as `terrain=hills sea=water structures=vanilla` — the
         * debug spelling of a sentence, and the shape `/age compose` takes.
         *
         * Every aspect but the terrain has a default, so the shortest useful Age is one word. Anything
         * unrecognised fails loudly with the alternatives listed, because this is a *command* and a
         * typo here is a mistake rather than an utterance. Note that the pen proper must never behave
         * this way — refusing what a writer said is exactly what design §2 forbids, since validation
         * would make precision risk-free. This is a debugging convenience and nothing more.
         */
        fun parse(specification: String): Result<AgeComposition> = runCatching {
            // A stand-in terrain, so options may be read in any order relative to the presets they
            // steer. Either the sentence names one over the top of it, or it is rejected below for
            // having named none — so which one this is can never reach a world.
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
                        value.split(',').filter(String::isNotBlank),
                    )
                } else {
                    namedALandform = namedALandform || aspect == Aspect.TERRAIN
                    // Commas are how a set-valued aspect is written: `terrain=hills,pillars`. An `@` after
                    // a preset is how much ground it covers: `dressing=verdant,bare_rock@rare`. Braces after
                    // that steer that territory alone: `terrain=spires{stone=copper},hills`.
                    val filling = value.split(',').filter(String::isNotBlank)
                    val named = filling.map { it.substringBefore(STEER_OPEN) }
                    composition
                        .withPresets(
                            aspect,
                            named.map { it.substringBefore(SHARE_MARK) },
                            named.map { preset ->
                                val share = preset.substringAfter(SHARE_MARK, missingDelimiterValue = "")
                                if (share.isEmpty()) Share.DOMINANT else namedShare(share)
                            },
                        )
                        .steeredBy(aspect, filling)
                }
            }
            require(namedALandform) { "An Age needs a terrain. Try `terrain=${Terrain.HILLS.key}`" }
            composition
        }

        /**
         * The braced steering in `spires{stone=copper},hills{stone=andesite}`, applied to the territory each
         * was written against.
         *
         * Loud about a malformed brace for the same reason the rest of [parse] is loud: this is a command,
         * and `spires{stone` is a typo rather than an utterance.
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
                        value.split(',').filter(String::isNotBlank),
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
                enumCodec<Biomes>().optionalFieldOf("biomes", Biomes.VANILLA)
                    .forGetter(AgeComposition::biomes),
                enumCodec<Sky>().optionalFieldOf("sky", Sky.PLAIN).forGetter(AgeComposition::sky),
                enumCodec<Structures>().optionalFieldOf("structures", Structures.NONE)
                    .forGetter(AgeComposition::structures),
                setOrSingle(enumCodec<Climate>(), Climate.NATURAL)
                    .optionalFieldOf("climate", listOf(Climate.NATURAL))
                    .forGetter(AgeComposition::climates),
                AspectOptions.CODEC.optionalFieldOf("options", AspectOptions()).forGetter(AgeComposition::options),
                SlotShares.CODEC.optionalFieldOf("shares", SlotShares()).forGetter(AgeComposition::shares),
            ).apply(instance, ::AgeComposition)
        }
    }
}

/**
 * The options chosen for each aspect, **one set per territory** — kept apart per aspect so that two presets may
 * both offer a `depth` without one answering for the other, and apart per *member* so that two presets in
 * one aspect may be steered differently.
 *
 * **Per member, not per aspect, and that was a real flaw rather than a refinement.** A positional aspect holds
 * several presets, so "copper spires and andesite hills" names one parameter twice with two values — and a
 * single `Options` per aspect has nowhere to put the second. The two contended, one won, and the winner
 * painted *both* territories: a sentence that reads perfectly and quietly does something else.
 *
 * There is a note on record saying not to "fix" per-aspect options, on the grounds that it is only wrong if
 * two territories share a preset, which preset-disjointness forbids. That reasoning is sound and does not
 * reach this case: here the territories hold **different** presets, each wanting a different value of the
 * **same** parameter name, so disjointness is what makes it a problem rather than what prevents it.
 *
 * **A lone entry answers for every member**, which is what makes the common case cost nothing: a aspect whose
 * territories agree is stored, spelled and read exactly as it was when options were per aspect. [with]
 * collapses agreeing entries back to one, so "they all say copper" has a single spelling rather than one
 * per territory count.
 */
data class AspectOptions(private val bySlot: Map<Aspect, List<Options>> = emptyMap()) {
    /** What was chosen for the [member]th preset of [aspect] — a lone entry answering for all of them. */
    fun of(aspect: Aspect, member: Int = 0): Options {
        val perMember = bySlot[aspect].orEmpty()
        return perMember.singleOrNull() ?: perMember.getOrElse(member) { Options.NONE }
    }

    /** Every member's options as stored — one entry where they agree, [members] of them where they differ. */
    fun allOf(aspect: Aspect): List<Options> = bySlot[aspect].orEmpty()

    /** The same, padded out to one entry per member, so a caller may steer any of them. */
    fun expanded(aspect: Aspect, members: Int): List<Options> = List(members) { member -> of(aspect, member) }

    fun with(aspect: Aspect, perMember: List<Options>): AspectOptions {
        // Collapsed before it is stored rather than when it is read, so a recipe never records the same
        // answer several times and the round trip has one spelling to reproduce.
        val collapsed = if (perMember.distinct().size == 1) perMember.take(1) else perMember
        val saysNothing = collapsed.all { it.chosen.isEmpty() }
        return AspectOptions(if (saysNothing) bySlot - aspect else bySlot + (aspect to collapsed))
    }

    companion object {
        /**
         * A lone entry is written **bare**, the way it was before options were per member — so a aspect whose
         * territories agree serialises identically to how it always did. The same either-or trick, for the
         * same reason, as [setOrSingle] and [Options.CODEC].
         */
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
 * How much of the world each preset of a aspect covers, kept per aspect beside [AspectOptions] and for the same
 * reasons.
 *
 * An absent aspect is an even division, and so is one whose shares are all [Share.DOMINANT] — normalised away
 * on the way in, so that "equal" has exactly one spelling in a recipe rather than five.
 */
data class SlotShares(private val bySlot: Map<Aspect, List<Share>> = emptyMap()) {
    fun of(aspect: Aspect): List<Share> = bySlot[aspect] ?: emptyList()

    fun with(aspect: Aspect, shares: List<Share>): SlotShares =
        SlotShares(if (shares.all { it == Share.DOMINANT }) bySlot - aspect else bySlot + (aspect to shares))

    companion object {
        val CODEC: Codec<SlotShares> =
            Codec.unboundedMap(StringRepresentable.fromEnum(Aspect::values), Share.CODEC.listOf())
                .xmap(::SlotShares, SlotShares::bySlot)
    }
}

/**
 * How much ground a preset covers, written after it: `dressing=verdant,bare_rock@rare`.
 *
 * It was a colon until the sea aspect opened, and a colon is now the thing that tells a registry id from
 * an authored key (`namesReferent`) — so `sea=minecraft:air` read as the preset `minecraft` covering an
 * `air` share of the world. Caught by `:common:recipecheck`'s round trip, which is exactly the collision
 * that check exists to find. Nothing persisted moves: shares travel as their own codec field, and this
 * spelling is only ever what `/age list` prints and `/age compose` reads.
 */
private const val SHARE_MARK = '@'

/**
 * How one territory's own steering is written: `terrain=spires{stone=copper},hills{stone=andesite}`.
 *
 * Against the preset rather than as a `terrain.0.stone=copper` token, because the index form puts the thing
 * being steered and the steering in different places and makes a reader count commas to pair them up. Braces
 * keep them together, which matters most in exactly the case that needs them — a aspect holding several
 * territories that differ.
 *
 * Only ever what `/age list` prints and `/age compose` reads. Nothing persisted uses it: options travel as
 * their own codec field.
 */
private const val STEER_OPEN = '{'
private const val STEER_CLOSE = '}'

/** Parameters within one territory's braces, since a space would end the token and a comma joins values. */
private const val PARAMETER_MARK = ';'

/** The share called [key], loud about a name nobody knows for the same reason [named] is. */
private fun namedShare(key: String): Share = Share.entries.firstOrNull { it.key == key }
    ?: error("No share called '$key'. Try: ${Share.entries.joinToString(" ") { it.key }}")

/**
 * The preset [aspect] calls [key], or a failure saying what it could have been.
 *
 * Loud rather than lenient, for the same reason [AgeComposition.Companion.parse] is: this only ever
 * runs behind a command, where a name nobody recognises is a typo rather than an utterance. An **open**
 * aspect has no list to offer instead, so it says what shape it wanted — every id is a legitimate answer
 * there, including one naming content this pack does not have (design §3.1).
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
    // Cannot happen unless a aspect's `presetFor` and this call site disagree about the aspect's own type,
    // which the exhaustive `when` in `withSingle` is there to prevent.
    return preset as? T ?: error("The ${aspect.key} aspect answered '$key' with a ${preset::class.simpleName}")
}

/**
 * A set-valued aspect's codec: reads a list, and also a bare single value for recipes written before that
 * aspect held sets. Writes a bare value back when there is only one, so a one-preset Age is spelled the way
 * it always was and stays readable by anything that only ever understood the old shape.
 *
 * Never empty — an empty aspect is a world missing a part, so [fallback] stands in rather than letting the
 * recipe describe nothing.
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
 * What an *open* aspect needs that [enumCodec] cannot give it: the set of legal values is not knowable in
 * advance, so the key has to be handed to the aspect to interpret rather than matched against a list. It is
 * written for both kinds anyway, since going through [Aspect.presetFor] is what stops a recipe, a
 * `preset_tags` file and a command from ever disagreeing about how a preset is spelled.
 *
 * A key an open aspect cannot read is a *malformed* recipe rather than missing content — an id naming a block
 * this pack does not have parses perfectly well here and is complained about where it is used.
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
