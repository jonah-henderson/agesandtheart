package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.AspectPreset
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.StringRepresentable
import java.util.Optional

/**
 * How much freedom a word takes away — the whole of what precision means here (design §4.4). One
 * mechanism serves all three: every word scores every candidate, and the tier decides how hard that
 * score bites. Nothing branches on tier beyond reading these two numbers.
 */
enum class Tier(
    val key: String,
    /** Fine inks per aspect constrained. Value-derived, the value being freedom removed (§4.4). */
    val cost: Int,
    /**
     * How strongly a preset must answer a word for the word to keep it. Zero rather than absent for
     * [EVOCATIVE], so "does this preset qualify?" needs no special case.
     */
    val threshold: Double,
    /**
     * How much a *failure* at this precision costs the Age. Separate from [cost]: cost is ink spent to
     * say something precisely, weight is what it means for that thing not to happen.
     */
    val weight: Int,
) : StringRepresentable {
    /** Shifts the weights over whatever survived. Removes no freedom, so it can never fail. */
    EVOCATIVE("evocative", cost = 1, threshold = 0.0, weight = 1),

    /** Narrows the candidates to those that carry the tag at all. */
    RESTRICTIVE("restrictive", cost = 2, threshold = 0.3, weight = 2),

    /** Pins: only a strong carrier will do. */
    EXACT("exact", cost = 4, threshold = 0.7, weight = 3),
    ;

    /** Whether this tier narrows the candidate set, as opposed to merely tilting the draw between them. */
    val narrows: Boolean get() = this != EVOCATIVE

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<Tier> = StringRepresentable.fromEnum(Tier::values)
    }
}

/**
 * One word of the Art: a query over tag space, at a precision, about some part of the world.
 *
 * A word is *not* a tag — "beautiful" names a *region* of tag space where a tag is a property the world
 * carries, and keeping the two apart is what lets synonyms be a design lever (§3.3).
 *
 * Words are datapack content (`data/<namespace>/art/word/<name>.json`), so a pack can retune vocabulary
 * without touching code. The resolved recipe is what persists, never the words (§4.6).
 */
data class Word(
    /** Where the word was defined. Its path is what a writer says: `agesandtheart:floating` → "floating". */
    val id: ResourceLocation,
    val tier: Tier,
    /**
     * The aspects this word may fill — **empty meaning anywhere**.
     *
     * Load-bearing (§4.4): without it a word gets a say in every aspect where any preset carries any of
     * its tags, so `stormy` — a word about the sky — pinned the *terrain* to caverns and discarded
     * `floating` in silence. It cannot be derived from tag data; which aspect a word is *about* is
     * authorial intent and lives nowhere else.
     */
    val aspects: Set<Aspect>,
    /**
     * The tags this word asks for, weighted and **signed**, because a query may push away as well as pull
     * (§3.3) — without negative weights "beautiful" reliably produced an ocean of lava.
     *
     * Content tags stay unipolar: the negativity belongs to the query, never to the world, so two
     * negative-carrying words down-weight independently rather than cancelling to mush.
     */
    val query: Map<String, Double>,
    /**
     * The one preset this word names outright, by its key — what makes a word referential rather than
     * evaluative (§8.1). Every derived word has one; an authored word may.
     *
     * A word that names a preset **does not search** for carriers, which is what keeps §8.2 structural:
     * it arrives with its answer in hand, and a vague word can never reach it for want of a name.
     *
     * The key rather than a resolved [AspectPreset], so a word stays plain data and an id naming content
     * this pack lacks survives being read.
     */
    val names: String? = null,
    /**
     * Parameters this word chooses, by name — how a word reaches a material (§3.2) or any other knob.
     * Applied to every aspect the word speaks to, since a parameter name only means anything within one.
     *
     * **One value per parameter**: a word names one thing, and what joins two is a conjunction in the
     * grammar. The set arrives at [co.voik.agesandtheart.age.aspect.Options], which does hold several.
     */
    val sets: Map<String, String> = emptyMap(),
) {
    /** What a writer says to use it. */
    val name: String get() = id.path

    /** The preset this word names in [aspect], if it names one that aspect can hold. */
    fun namedPreset(aspect: Aspect): AspectPreset? = names?.let(aspect::presetFor)

    /**
     * Whether this word says nothing except which part of the world it is about — an **aiming page**
     * (§4.3.1), whose whole job is to open a section and scope what follows it.
     *
     * Recognised by shape rather than by a flag, because that shape *is* the definition: a word with no
     * query, no named preset and no parameter has nothing to contribute but its aspects.
     */
    val aims: Boolean get() = query.isEmpty() && names == null && sets.isEmpty() && aspects.isNotEmpty()

    /**
     * Whether this word has anything to say about *which* preset fills an aspect, as opposed to how that
     * preset is steered. A word that only sets a parameter must not be treated as narrowing: an empty
     * carrier set is how the resolver recognises a word the world cannot satisfy (§3.3).
     */
    val constrainsPresets: Boolean get() = names != null || query.values.any { it > 0.0 }

    /**
     * The same question asked of one aspect, which is the honest form. A derived block word names a *sea*
     * and merely *sets* a material on the terrain — asked globally it claims to narrow every aspect it
     * speaks to, finds no carrier in most, and is charged as unbacked for an opinion it never had.
     */
    fun constrainsPresetsIn(aspect: Aspect): Boolean =
        namedPreset(aspect) != null || query.values.any { it > 0.0 }

    /** The tags this word wants, which are the ones that must have a carrier somewhere (§3.3). */
    val wanted: Set<String> get() = query.filterValues { it > 0.0 }.keys

    /**
     * How strongly [tags] answers this word's *positive* terms — the number a narrowing word thresholds.
     * The strongest single term rather than a sum, because a word asking for two tags asks for either.
     */
    fun pull(tags: Map<String, Double>): Double =
        query.filterValues { it > 0.0 }.maxOfOrNull { (tag, weight) -> weight * (tags[tag] ?: 0.0) } ?: 0.0

    /**
     * How much this word likes [tags] overall, positive and negative terms together — what an evocative
     * word tilts a draw by. A dot product where [pull] takes a maximum, which is the tier distinction:
     * narrowing asks "does this qualify at all", tilting asks "how well does this answer".
     */
    fun affinityFor(tags: Map<String, Double>): Double =
        query.entries.sumOf { (tag, weight) -> weight * (tags[tag] ?: 0.0) }

    /**
     * How strongly this word claims one particular preset — [pull], except that naming a preset claims it
     * absolutely. Without this a derived word would be scored on tags it does not have, so "creosote oil
     * beside a lava sea" gave creosote the *smaller* share.
     */
    fun pullOn(preset: AspectPreset, tags: Map<String, Double>): Double =
        if (names == preset.key) NAMED_OUTRIGHT else pull(tags)

    /**
     * Whether this preset qualifies for this word at its tier's strictness. The `> 0` is not redundant
     * with the threshold: [Tier.EVOCATIVE]'s threshold is zero, and a preset that answers the word not at
     * all must never qualify.
     */
    fun accepts(tags: Map<String, Double>): Boolean {
        val strength = pull(tags)
        return strength > 0.0 && strength >= tier.threshold
    }

    override fun toString(): String = name

    companion object {
        /** What naming a thing outright is worth, against a tag weight, which never exceeds one. */
        private const val NAMED_OUTRIGHT = 1.0

        /** A word as its file says it, the id coming from where the file *is*, like every vanilla registry. */
        fun mapCodec(id: ResourceLocation): MapCodec<Word> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Tier.CODEC.fieldOf("tier").forGetter(Word::tier),
                ASPECT_SET_CODEC.optionalFieldOf("aspects", emptySet()).forGetter(Word::aspects),
                // Optional: a word naming a preset outright has nothing to ask of tag space. Both together
                // is legal and means "this, and it is also like these".
                Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("query", emptyMap())
                    .forGetter(Word::query),
                Codec.STRING.optionalFieldOf("names").forGetter { Optional.ofNullable(it.names) },
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("sets", emptyMap())
                    .forGetter(Word::sets),
            ).apply(instance) { tier, aspects, query, names, sets ->
                Word(id, tier, aspects, query, names.orElse(null), sets)
            }
        }

        private val ASPECT_CODEC: Codec<Aspect> = StringRepresentable.fromEnum(Aspect::values)

        // A set rather than a list: "terrain terrain" means nothing, and pricing counts aspects constrained.
        private val ASPECT_SET_CODEC: Codec<Set<Aspect>> = ASPECT_CODEC.listOf().xmap({ it.toSet() }, { it.toList() })
    }
}

/**
 * What one preset is *like*: the tags it carries, and how readily the Art reaches for it.
 *
 * Tag membership is **unipolar and weighted**, so a thing may be neither `lush` nor `barren`, or oddly
 * both (§3.3) — which is how the world absorbs a contradiction. A bipolar axis could not: on one scalar
 * "lush, barren" averages to temperate nothing, where independent tags give cherry groves beside desert.
 *
 * Tags belong to presets and content, never to primitives — an `Ellipsoid` is not lush.
 */
data class PresetProfile(
    val tags: Map<String, Double>,
    /**
     * How willingly the Art reaches for this preset when **nothing in the sentence asked**, relative to
     * its siblings. One is ordinary; a quarter is something it would rather not do unbidden.
     *
     * Without a prior an unconstrained aspect draws uniformly, so a sentence saying nothing about the sea
     * got lava one time in three — §8.2's "vagueness draws only from the curated pool", broken. Precision
     * still reaches anything, since readiness only weights an *unasked* draw.
     */
    val readiness: Double?,
) {
    /** This profile with [later] laid over it — a higher-priority pack retuning some of it. */
    fun mergedWith(later: PresetProfile): PresetProfile =
        PresetProfile(tags + later.tags, later.readiness ?: readiness)

    companion object {
        /** What a preset that says nothing about its readiness gets. */
        const val ORDINARY_READINESS = 1.0

        val CODEC: Codec<PresetProfile> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).fieldOf("tags").forGetter(PresetProfile::tags),
                Codec.DOUBLE.optionalFieldOf("readiness").forGetter { Optional.ofNullable(it.readiness) },
            ).apply(instance) { tags, readiness -> PresetProfile(tags, readiness.orElse(null)) }
        }
    }
}

/** One aspect's worth of profiles, which is one `art/preset_tags/<aspect>.json`. */
data class PresetTags(private val byPreset: Map<String, PresetProfile>) {
    fun of(preset: AspectPreset): PresetProfile = byKey(preset.key)

    /** The same, where only the preset's name is known — which is all a file being loaded has. */
    fun byKey(key: String): PresetProfile = byPreset[key] ?: EMPTY_PROFILE

    /** Every preset key this table has something to say about — for validation, not for resolution. */
    val described: Set<String> get() = byPreset.keys

    /** Every tag anything here carries, which bounds what any word can meaningfully ask for. */
    val carried: Set<String> get() = byPreset.values.flatMap { it.tags.keys }.toSet()

    companion object {
        val EMPTY_PROFILE = PresetProfile(emptyMap(), readiness = null)

        val CODEC: Codec<PresetTags> = Codec.unboundedMap(Codec.STRING, PresetProfile.CODEC)
            .xmap(::PresetTags, PresetTags::byPreset)
    }
}
