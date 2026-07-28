package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.slot.Dressing
import co.voik.agesandtheart.age.slot.Landform
import co.voik.agesandtheart.age.slot.Medium
import co.voik.agesandtheart.age.slot.Options
import co.voik.agesandtheart.age.slot.Share
import co.voik.agesandtheart.age.slot.Sky
import co.voik.agesandtheart.age.slot.Slot
import co.voik.agesandtheart.age.slot.SlotPreset
import co.voik.agesandtheart.age.slot.Subsurface
import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.util.StringRepresentable

/**
 * An Age assembled from one preset per [Slot] — the composed half of what an Age can be.
 *
 * Named fields rather than a `Map<Slot, …>`, which the plan had sketched, because the map buys
 * extensibility we do not need yet and costs the two things that matter more: `composition.landform`
 * reads as English where `composition[Slot.LANDFORM]!!` does not, and a new slot becomes a compile
 * error at every site that must think about it rather than a silently absent key. Adding a slot is a
 * codec field with a default, which is cheap; the map can come back if the resolver turns out to want
 * generic iteration badly enough to pay for it.
 */
data class AgeComposition(
    /**
     * The shapes of the rock — **plural**, because landform is a *positional* slot and so holds a set
     * (§3.4). Two landforms divide the world between them, which is how a contradiction gets satisfied
     * by coexistence rather than by one term silently going missing.
     *
     * Never empty; [parse] and the codec both guarantee at least one.
     */
    val landforms: List<Landform>,
    /** What fills the space the shapes leave — positional, so a sea may be two substances at once. */
    val mediums: List<Medium> = listOf(Medium.VOID),
    /** What has been cut back out of the rock, and where water stands in it. Positional too. */
    val subsurfaces: List<Subsurface> = listOf(Subsurface.SOLID),
    /**
     * What it all looks and grows like — **plural**, like [landforms], because dressing is positional
     * too. Two dressings divide the world between them, painting and populating their own territories.
     */
    val dressings: List<Dressing> = listOf(Dressing.BARE_ROCK),
    val sky: Sky = Sky.PLAIN,
    val options: SlotOptions = SlotOptions(),
    /**
     * How much ground each preset of a set-valued slot covers, where they do not cover it equally.
     *
     * Kept beside the presets rather than inside them, like [options], because a share is not a property of
     * a preset — the same hills are dominant in one Age and scattered in another. Empty for a slot means an
     * even division, which is what a hand-composed Age gets.
     */
    val shares: SlotShares = SlotShares(),
) {
    /**
     * The share each of [slot]'s presets covers, one per preset — an even division where none was named.
     *
     * The shape generation wants: a share per member, never a shorter list, so a map can be built without
     * asking whether anyone said anything about it.
     */
    fun sharesOf(slot: Slot): List<Share> {
        val filling = presets.count { it.slot == slot }
        val named = shares.of(slot)
        return List(filling) { member -> named.getOrElse(member) { Share.DOMINANT } }
    }

    /** Every preset this composition names, in slot order — for listing, costing and diagnosis. */
    val presets: List<SlotPreset> get() = landforms + mediums + subsurfaces + dressings + listOf(sky)

    /** The one landform, where there is only one — for the many places that still reasonably assume so. */
    val landform: Landform get() = landforms.first()

    /** Likewise the one dressing, medium and subsurface. */
    val dressing: Dressing get() = dressings.first()
    val medium: Medium get() = mediums.first()
    val subsurface: Subsurface get() = subsurfaces.first()

    /**
     * Options no preset here understands, spelled `dressing.settlment` — a typo, or a knob some later
     * version dropped. Kept in the recipe rather than discarded, and surfaced by `/age list` so that a
     * misspelling looks wrong instead of merely doing nothing. See [Options].
     */
    val unknownOptions: List<String>
        get() = presets.groupBy { it.slot }.flatMap { (slot, filling) ->
            filling.flatMapIndexed { member, preset ->
                options.of(slot, member).unknownTo(preset).map { name -> "${slot.key}.$name" }
            }
        }.distinct()

    /** How the [member]th preset of [slot] is steered — what [AgeGeneration] hands each territory. */
    fun optionsFor(slot: Slot, member: Int): Options = options.of(slot, member)

    /**
     * This composition with [slot] filled by the preset called [key] instead.
     *
     * The `when` is exhaustive over [Slot], which is the whole reason the fields are named rather than
     * a map: adding a slot stops the build here, at the one place that must learn how to fill it.
     */
    fun withPreset(slot: Slot, key: String): AgeComposition = withPresets(slot, listOf(key))

    /**
     * This composition with [slot] filled by the presets named in [keys].
     *
     * A set-valued slot takes them all; a singular one takes the last, rather than refusing. Refusing
     * would be the pen validating a sentence, which §2 forbids on the grounds that it makes precision
     * risk-free — and "sky=plain,storm" is a writer asking for something the world cannot be, which is
     * a job for the instability index rather than for an error message.
     */
    fun withPresets(slot: Slot, keys: List<String>, shares: List<Share> = emptyList()): AgeComposition {
        val filled = when (slot) {
            Slot.LANDFORM -> copy(landforms = keys.map { named<Landform>(slot, it) })
            Slot.DRESSING -> copy(dressings = keys.map { named<Dressing>(slot, it) })
            Slot.MEDIUM -> copy(mediums = keys.map { named<Medium>(slot, it) })
            Slot.SUBSURFACE -> copy(subsurfaces = keys.map { named<Subsurface>(slot, it) })
            else -> withSingle(slot, keys.last())
        }
        return filled.copy(shares = filled.shares.with(slot, shares))
    }

    private fun withSingle(slot: Slot, key: String): AgeComposition = when (slot) {
        Slot.LANDFORM -> copy(landforms = listOf(named<Landform>(slot, key)))
        Slot.MEDIUM -> copy(mediums = listOf(named<Medium>(slot, key)))
        Slot.SUBSURFACE -> copy(subsurfaces = listOf(named<Subsurface>(slot, key)))
        Slot.DRESSING -> copy(dressings = listOf(named<Dressing>(slot, key)))
        Slot.SKY -> copy(sky = named<Sky>(slot, key))
    }

    /**
     * This composition with one more option chosen for [slot].
     *
     * Whether the preset in that slot understands [parameter] is not asked here. An unrecognised name
     * is kept and reported rather than rejected — see [Options] for why a recipe must never quietly
     * lose part of itself.
     */
    fun withOption(slot: Slot, parameter: String, option: String): AgeComposition =
        withOptions(slot, parameter, listOf(option))

    /**
     * The same, where a writer named several — which for a material means them **mingled** through one
     * another rather than given a region each (design §3.2).
     *
     * Applies to **every** territory of the slot, which is what an unaimed word means: "the rock is
     * blackstone" said of a world with two landforms is said of both. Aiming one territory in particular is
     * [withOptionsFor], and needs the grammar to say which (design §4.3.1).
     */
    fun withOptions(slot: Slot, parameter: String, chosen: List<String>): AgeComposition {
        val everyMember = options.allOf(slot).ifEmpty { listOf(Options.NONE) }
        return copy(options = options.with(slot, everyMember.map { Options(it.chosen + (parameter to chosen)) }))
    }

    /**
     * This composition with one option chosen for **one territory only** — `spires{stone=copper}` beside
     * `hills{stone=andesite}`.
     *
     * The whole reason [SlotOptions] is per member. A [member] past the end of the slot's filling is written
     * anyway rather than dropped, for the reason an unrecognised parameter name is kept: a recipe is the only
     * record of an Age and must never quietly lose part of itself.
     */
    fun withOptionsFor(slot: Slot, member: Int, parameter: String, chosen: List<String>): AgeComposition {
        val filling = presets.count { it.slot == slot }
        val perMember = options.expanded(slot, maxOf(filling, member + 1))
        val steered = perMember.mapIndexed { index, existing ->
            if (index == member) Options(existing.chosen + (parameter to chosen)) else existing
        }
        return copy(options = options.with(slot, steered))
    }

    /**
     * How a writer would have said it: `landform=hills landform.arrangement=grid medium=sea …`.
     *
     * Deliberately the exact spelling [parse] reads, so that what `/age list` prints can be pasted
     * straight back into `/age compose` — and so the pair can be checked by round-trip rather than by
     * eye (`:common:recipecheck`). Every slot is named even where it holds its default, since a recipe
     * is the only record of an Age and "what did this leave unsaid?" is the wrong question to have to
     * ask of one.
     */
    override fun toString(): String = presets.groupBy { it.slot }.entries
        .sortedBy { (slot, _) -> slot.ordinal }
        .flatMap { (slot, filling) ->
            // Territories that agree are spelled once for the whole slot, the way they were before options
            // were per member; only a slot whose territories differ pays for the braces.
            val aimed = options.allOf(slot).size > 1
            val written = filling.mapIndexed { index, preset ->
                // A share is only spelled where it says something: an even division, and the largest share
                // of an uneven one, are both left unsaid so that the common case reads as it always did.
                val share = shares.of(slot).getOrNull(index)
                val named = if (share == null || share == Share.DOMINANT) preset.key else "${preset.key}$SHARE_MARK${share.key}"
                if (aimed) named + steering(options.of(slot, index)) else named
            }
            val slotWide = if (aimed) emptyList() else spelled(slot, options.of(slot))
            listOf("${slot.key}=${written.joinToString(",")}") + slotWide
        }.joinToString(" ")

    /** `landform.arrangement=grid` — one token per parameter, for a slot whose territories agree. */
    private fun spelled(slot: Slot, chosen: Options): List<String> = chosen.chosen.entries.sortedBy { it.key }
        // Comma-joined like a set-valued slot, and read back the same way — several values on one parameter
        // mingle (§3.2), where several presets on one slot divide.
        .map { (parameter, options) -> "${slot.key}.$parameter=${options.joinToString(",")}" }

    /** `{stone=copper;arrangement=grid}` — written against the preset it steers, and empty where it says nothing. */
    private fun steering(chosen: Options): String {
        if (chosen.chosen.isEmpty()) return ""
        val written = chosen.chosen.entries.sortedBy { it.key }
            .joinToString(PARAMETER_MARK.toString()) { (parameter, options) -> "$parameter=${options.joinToString(",")}" }
        return "$STEER_OPEN$written$STEER_CLOSE"
    }

    companion object {
        /**
         * A composition written out as `landform=hills medium=sea dressing.settlement=vanilla` — the
         * debug spelling of a sentence, and the shape `/age compose` takes.
         *
         * Every slot but the landform has a default, so the shortest useful Age is one word. Anything
         * unrecognised fails loudly with the alternatives listed, because this is a *command* and a
         * typo here is a mistake rather than an utterance. Note that the pen proper must never behave
         * this way — refusing what a writer said is exactly what design §2 forbids, since validation
         * would make precision risk-free. This is a debugging convenience and nothing more.
         */
        fun parse(specification: String): Result<AgeComposition> = runCatching {
            // A stand-in landform, so options may be read in any order relative to the presets they
            // steer. Either the sentence names one over the top of it, or it is rejected below for
            // having named none — so which one this is can never reach a world.
            var composition = AgeComposition(landforms = listOf(Landform.SHAPES))
            var namedALandform = false

            for (token in specification.split(' ').filter(String::isNotBlank)) {
                val (key, value) = token.split('=', limit = 2).takeIf { it.size == 2 }
                    ?: error("'$token' is not `key=value`")
                val slot = Slot.entries.firstOrNull { key.substringBefore('.') == it.key }
                    ?: error("No slot called '${key.substringBefore('.')}'. Slots: ${Slot.entries.joinToString(" ") { it.key }}")

                composition = if ('.' in key) {
                    composition.withOptions(
                        slot,
                        key.substringAfter('.'),
                        value.split(',').filter(String::isNotBlank),
                    )
                } else {
                    namedALandform = namedALandform || slot == Slot.LANDFORM
                    // Commas are how a set-valued slot is written: `landform=hills,pillars`. An `@` after
                    // a preset is how much ground it covers: `dressing=verdant,bare_rock@rare`. Braces after
                    // that steer that territory alone: `landform=spires{stone=copper},hills`.
                    val filling = value.split(',').filter(String::isNotBlank)
                    val named = filling.map { it.substringBefore(STEER_OPEN) }
                    composition
                        .withPresets(
                            slot,
                            named.map { it.substringBefore(SHARE_MARK) },
                            named.map { preset ->
                                val share = preset.substringAfter(SHARE_MARK, missingDelimiterValue = "")
                                if (share.isEmpty()) Share.DOMINANT else namedShare(share)
                            },
                        )
                        .steeredBy(slot, filling)
                }
            }
            require(namedALandform) { "An Age needs a landform. Try `landform=${Landform.HILLS.key}`" }
            composition
        }

        /**
         * The braced steering in `spires{stone=copper},hills{stone=andesite}`, applied to the territory each
         * was written against.
         *
         * Loud about a malformed brace for the same reason the rest of [parse] is loud: this is a command,
         * and `spires{stone` is a typo rather than an utterance.
         */
        private fun AgeComposition.steeredBy(slot: Slot, filling: List<String>): AgeComposition {
            var steered = this
            for ((member, written) in filling.withIndex()) {
                if (STEER_OPEN !in written) continue
                require(written.endsWith(STEER_CLOSE)) { "'$written' opens a $STEER_OPEN and never closes it" }
                val inside = written.substringAfter(STEER_OPEN).dropLast(1)
                for (setting in inside.split(PARAMETER_MARK).filter(String::isNotBlank)) {
                    val (parameter, value) = setting.split('=', limit = 2).takeIf { it.size == 2 }
                        ?: error("'$setting' is not `parameter=value`")
                    steered = steered.withOptionsFor(
                        slot,
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
                setOrSingle(enumCodec<Landform>(), Landform.SHAPES)
                    .fieldOf("landform").forGetter(AgeComposition::landforms),
                setOrSingle(presetCodec<Medium>(Slot.MEDIUM), Medium.VOID)
                    .optionalFieldOf("medium", listOf(Medium.VOID)).forGetter(AgeComposition::mediums),
                setOrSingle(enumCodec<Subsurface>(), Subsurface.SOLID)
                    .optionalFieldOf("subsurface", listOf(Subsurface.SOLID))
                    .forGetter(AgeComposition::subsurfaces),
                setOrSingle(enumCodec<Dressing>(), Dressing.BARE_ROCK)
                    .optionalFieldOf("dressing", listOf(Dressing.BARE_ROCK))
                    .forGetter(AgeComposition::dressings),
                enumCodec<Sky>().optionalFieldOf("sky", Sky.PLAIN).forGetter(AgeComposition::sky),
                SlotOptions.CODEC.optionalFieldOf("options", SlotOptions()).forGetter(AgeComposition::options),
                SlotShares.CODEC.optionalFieldOf("shares", SlotShares()).forGetter(AgeComposition::shares),
            ).apply(instance, ::AgeComposition)
        }
    }
}

/**
 * The options chosen for each slot, **one set per territory** — kept apart per slot so that two presets may
 * both offer a `depth` without one answering for the other, and apart per *member* so that two presets in
 * one slot may be steered differently.
 *
 * **Per member, not per slot, and that was a real flaw rather than a refinement.** A positional slot holds
 * several presets, so "copper spires and andesite hills" names one parameter twice with two values — and a
 * single `Options` per slot has nowhere to put the second. The two contended, one won, and the winner
 * painted *both* territories: a sentence that reads perfectly and quietly does something else.
 *
 * There is a note on record saying not to "fix" per-slot options, on the grounds that it is only wrong if
 * two territories share a preset, which preset-disjointness forbids. That reasoning is sound and does not
 * reach this case: here the territories hold **different** presets, each wanting a different value of the
 * **same** parameter name, so disjointness is what makes it a problem rather than what prevents it.
 *
 * **A lone entry answers for every member**, which is what makes the common case cost nothing: a slot whose
 * territories agree is stored, spelled and read exactly as it was when options were per slot. [with]
 * collapses agreeing entries back to one, so "they all say copper" has a single spelling rather than one
 * per territory count.
 */
data class SlotOptions(private val bySlot: Map<Slot, List<Options>> = emptyMap()) {
    /** What was chosen for the [member]th preset of [slot] — a lone entry answering for all of them. */
    fun of(slot: Slot, member: Int = 0): Options {
        val perMember = bySlot[slot].orEmpty()
        return perMember.singleOrNull() ?: perMember.getOrElse(member) { Options.NONE }
    }

    /** Every member's options as stored — one entry where they agree, [members] of them where they differ. */
    fun allOf(slot: Slot): List<Options> = bySlot[slot].orEmpty()

    /** The same, padded out to one entry per member, so a caller may steer any of them. */
    fun expanded(slot: Slot, members: Int): List<Options> = List(members) { member -> of(slot, member) }

    fun with(slot: Slot, perMember: List<Options>): SlotOptions {
        // Collapsed before it is stored rather than when it is read, so a recipe never records the same
        // answer several times and the round trip has one spelling to reproduce.
        val collapsed = if (perMember.distinct().size == 1) perMember.take(1) else perMember
        val saysNothing = collapsed.all { it.chosen.isEmpty() }
        return SlotOptions(if (saysNothing) bySlot - slot else bySlot + (slot to collapsed))
    }

    companion object {
        /**
         * A lone entry is written **bare**, the way it was before options were per member — so a slot whose
         * territories agree serialises identically to how it always did. The same either-or trick, for the
         * same reason, as [setOrSingle] and [Options.CODEC].
         */
        val CODEC: Codec<SlotOptions> = Codec.unboundedMap(
            StringRepresentable.fromEnum(Slot::values),
            Codec.either(Options.CODEC.listOf(), Options.CODEC).xmap(
                { either -> either.map({ many -> many }, ::listOf) },
                { many -> if (many.size == 1) Either.right(many.first()) else Either.left(many) },
            ),
        ).xmap(::SlotOptions, SlotOptions::bySlot)
    }
}

/**
 * How much of the world each preset of a slot covers, kept per slot beside [SlotOptions] and for the same
 * reasons.
 *
 * An absent slot is an even division, and so is one whose shares are all [Share.DOMINANT] — normalised away
 * on the way in, so that "equal" has exactly one spelling in a recipe rather than five.
 */
data class SlotShares(private val bySlot: Map<Slot, List<Share>> = emptyMap()) {
    fun of(slot: Slot): List<Share> = bySlot[slot] ?: emptyList()

    fun with(slot: Slot, shares: List<Share>): SlotShares =
        SlotShares(if (shares.all { it == Share.DOMINANT }) bySlot - slot else bySlot + (slot to shares))

    companion object {
        val CODEC: Codec<SlotShares> =
            Codec.unboundedMap(StringRepresentable.fromEnum(Slot::values), Share.CODEC.listOf())
                .xmap(::SlotShares, SlotShares::bySlot)
    }
}

/**
 * How much ground a preset covers, written after it: `dressing=verdant,bare_rock@rare`.
 *
 * It was a colon until the medium slot opened, and a colon is now the thing that tells a registry id from
 * an authored key (`namesReferent`) — so `medium=minecraft:air` read as the preset `minecraft` covering an
 * `air` share of the world. Caught by `:common:recipecheck`'s round trip, which is exactly the collision
 * that check exists to find. Nothing persisted moves: shares travel as their own codec field, and this
 * spelling is only ever what `/age list` prints and `/age compose` reads.
 */
private const val SHARE_MARK = '@'

/**
 * How one territory's own steering is written: `landform=spires{stone=copper},hills{stone=andesite}`.
 *
 * Against the preset rather than as a `landform.0.stone=copper` token, because the index form puts the thing
 * being steered and the steering in different places and makes a reader count commas to pair them up. Braces
 * keep them together, which matters most in exactly the case that needs them — a slot holding several
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
 * The preset [slot] calls [key], or a failure saying what it could have been.
 *
 * Loud rather than lenient, for the same reason [AgeComposition.Companion.parse] is: this only ever
 * runs behind a command, where a name nobody recognises is a typo rather than an utterance. An **open**
 * slot has no list to offer instead, so it says what shape it wanted — every id is a legitimate answer
 * there, including one naming content this pack does not have (design §3.1).
 */
private inline fun <reified T : SlotPreset> named(slot: Slot, key: String): T {
    val preset = slot.presetFor(key)
        ?: error(
            if (slot.open) {
                "'$key' is no ${slot.key}. An open slot takes a `namespace:path` id, like `minecraft:water`"
            } else {
                "No ${slot.key} called '$key'. Try: ${slot.authored.joinToString(" ") { it.key }}"
            },
        )
    // Cannot happen unless a slot's `presetFor` and this call site disagree about the slot's own type,
    // which the exhaustive `when` in `withSingle` is there to prevent.
    return preset as? T ?: error("The ${slot.key} slot answered '$key' with a ${preset::class.simpleName}")
}

/**
 * A set-valued slot's codec: reads a list, and also a bare single value for recipes written before that
 * slot held sets. Writes a bare value back when there is only one, so a one-preset Age is spelled the way
 * it always was and stays readable by anything that only ever understood the old shape.
 *
 * Never empty — an empty slot is a world missing a part, so [fallback] stands in rather than letting the
 * recipe describe nothing.
 */
private fun <T> setOrSingle(single: Codec<T>, fallback: T): Codec<List<T>> =
    Codec.either(single.listOf(), single).xmap(
        { either -> either.map({ many -> many.ifEmpty { listOf(fallback) } }, ::listOf) },
        { many -> if (many.size == 1) Either.right(many.first()) else Either.left(many) },
    )

/** A codec over any of our slot-preset enums, which all serialise by their own [SlotPreset.key]. */
private inline fun <reified E> enumCodec(): Codec<E> where E : Enum<E>, E : StringRepresentable =
    StringRepresentable.fromEnum { enumValues<E>() }

/**
 * A codec over one slot's presets, reading and writing the same key `/age compose` spells.
 *
 * What an *open* slot needs that [enumCodec] cannot give it: the set of legal values is not knowable in
 * advance, so the key has to be handed to the slot to interpret rather than matched against a list. It is
 * written for both kinds anyway, since going through [Slot.presetFor] is what stops a recipe, a
 * `preset_tags` file and a command from ever disagreeing about how a preset is spelled.
 *
 * A key an open slot cannot read is a *malformed* recipe rather than missing content — an id naming a block
 * this pack does not have parses perfectly well here and is complained about where it is used.
 */
private inline fun <reified T : SlotPreset> presetCodec(slot: Slot): Codec<T> = Codec.STRING.comapFlatMap(
    { key ->
        when (val preset = slot.presetFor(key)) {
            is T -> DataResult.success(preset)
            else -> DataResult.error { "'$key' is no ${slot.key}" }
        }
    },
    SlotPreset::key,
)
