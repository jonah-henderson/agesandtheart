package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.slot.Dressing
import co.voik.agesandtheart.age.slot.Landform
import co.voik.agesandtheart.age.slot.Medium
import co.voik.agesandtheart.age.slot.Options
import co.voik.agesandtheart.age.slot.Sky
import co.voik.agesandtheart.age.slot.Slot
import co.voik.agesandtheart.age.slot.SlotPreset
import co.voik.agesandtheart.age.slot.Subsurface
import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
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
) {
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
        get() = presets.flatMap { preset ->
            options.of(preset.slot).unknownTo(preset).map { name -> "${preset.slot.key}.$name" }
        }

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
    fun withPresets(slot: Slot, keys: List<String>): AgeComposition = when (slot) {
        Slot.LANDFORM -> copy(landforms = keys.map { named(slot, it, Landform.entries) })
        Slot.DRESSING -> copy(dressings = keys.map { named(slot, it, Dressing.entries) })
        Slot.MEDIUM -> copy(mediums = keys.map { named(slot, it, Medium.entries) })
        Slot.SUBSURFACE -> copy(subsurfaces = keys.map { named(slot, it, Subsurface.entries) })
        else -> withSingle(slot, keys.last())
    }

    private fun withSingle(slot: Slot, key: String): AgeComposition = when (slot) {
        Slot.LANDFORM -> copy(landforms = listOf(named(slot, key, Landform.entries)))
        Slot.MEDIUM -> copy(mediums = listOf(named(slot, key, Medium.entries)))
        Slot.SUBSURFACE -> copy(subsurfaces = listOf(named(slot, key, Subsurface.entries)))
        Slot.DRESSING -> copy(dressings = listOf(named(slot, key, Dressing.entries)))
        Slot.SKY -> copy(sky = named(slot, key, Sky.entries))
    }

    /**
     * This composition with one more option chosen for [slot].
     *
     * Whether the preset in that slot understands [parameter] is not asked here. An unrecognised name
     * is kept and reported rather than rejected — see [Options] for why a recipe must never quietly
     * lose part of itself.
     */
    fun withOption(slot: Slot, parameter: String, option: String): AgeComposition =
        copy(options = options.with(slot, Options(options.of(slot).chosen + (parameter to option))))

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
            listOf("${slot.key}=${filling.joinToString(",") { it.key }}") +
                options.of(slot).chosen.entries.sortedBy { it.key }
                    .map { (parameter, option) -> "${slot.key}.$parameter=$option" }
        }.joinToString(" ")

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
                    composition.withOption(slot, key.substringAfter('.'), value)
                } else {
                    namedALandform = namedALandform || slot == Slot.LANDFORM
                    // Commas are how a set-valued slot is written: `landform=hills,pillars`.
                    composition.withPresets(slot, value.split(',').filter(String::isNotBlank))
                }
            }
            require(namedALandform) { "An Age needs a landform. Try `landform=${Landform.HILLS.key}`" }
            composition
        }

        val MAP_CODEC: MapCodec<AgeComposition> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                setOrSingle(enumCodec<Landform>(), Landform.SHAPES)
                    .fieldOf("landform").forGetter(AgeComposition::landforms),
                setOrSingle(enumCodec<Medium>(), Medium.VOID)
                    .optionalFieldOf("medium", listOf(Medium.VOID)).forGetter(AgeComposition::mediums),
                setOrSingle(enumCodec<Subsurface>(), Subsurface.SOLID)
                    .optionalFieldOf("subsurface", listOf(Subsurface.SOLID))
                    .forGetter(AgeComposition::subsurfaces),
                setOrSingle(enumCodec<Dressing>(), Dressing.BARE_ROCK)
                    .optionalFieldOf("dressing", listOf(Dressing.BARE_ROCK))
                    .forGetter(AgeComposition::dressings),
                enumCodec<Sky>().optionalFieldOf("sky", Sky.PLAIN).forGetter(AgeComposition::sky),
                SlotOptions.CODEC.optionalFieldOf("options", SlotOptions()).forGetter(AgeComposition::options),
            ).apply(instance, ::AgeComposition)
        }
    }
}

/**
 * The options chosen for each slot, kept apart per slot so two presets may both offer a `depth` without
 * one silently answering for the other.
 */
data class SlotOptions(private val bySlot: Map<Slot, Options> = emptyMap()) {
    fun of(slot: Slot): Options = bySlot[slot] ?: Options.NONE

    fun with(slot: Slot, options: Options): SlotOptions =
        SlotOptions(if (options.chosen.isEmpty()) bySlot - slot else bySlot + (slot to options))

    companion object {
        val CODEC: Codec<SlotOptions> =
            Codec.unboundedMap(StringRepresentable.fromEnum(Slot::values), Options.CODEC)
                .xmap(::SlotOptions, SlotOptions::bySlot)
    }
}

/**
 * The member of [family] called [key], or a failure naming every alternative.
 *
 * Loud rather than lenient, for the same reason [AgeComposition.Companion.parse] is: this only ever
 * runs behind a command, where a name nobody recognises is a typo rather than an utterance.
 */
private fun <T : SlotPreset> named(slot: Slot, key: String, family: List<T>): T =
    family.firstOrNull { it.key == key }
        ?: error("No ${slot.key} called '$key'. Try: ${family.joinToString(" ") { it.key }}")

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
