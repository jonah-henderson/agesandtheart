package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.slot.Slot
import co.voik.agesandtheart.age.slot.SlotPreset
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.StringRepresentable
import java.util.Optional

/**
 * How much freedom a word takes away — which is the whole of what precision means here (design §4.4).
 *
 * One mechanism serves all three: every word scores every candidate preset, and the tier decides only
 * how hard that score bites. Nothing below branches on tier beyond reading these two numbers, which is
 * the property the Phase 3 spike was built to check.
 */
enum class Tier(
    val key: String,
    /** Fine inks per slot constrained. Value-derived, the value being freedom removed (§4.4). */
    val cost: Int,
    /**
     * How strongly a preset must answer to a word for the word to keep it.
     *
     * Meaningless for [EVOCATIVE], which narrows nothing — it is given a threshold of zero rather than
     * left absent so that "does this preset qualify?" needs no special case anywhere.
     */
    val threshold: Double,
    /**
     * How much a *failure* at this precision costs the Age.
     *
     * Separate from [cost] because they price different things: cost is ink spent to say something
     * precisely, weight is what it means for that precise thing not to happen. An exact word going
     * unhonoured is a plan defeated; a vague one going unhonoured is barely a disappointment.
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
 * A word is *not* a tag. "Beautiful" names a region of tag space — lush, bright, lovely, and pointedly
 * *not* hostile — where a tag is a property the world carries. Keeping the two apart is what lets
 * synonyms be a design lever: three words may reach overlapping regions at different rarities without
 * anything in the world being retagged (§3.3).
 *
 * Words are **datapack content** (`data/<namespace>/art/word/<name>.json`), so a pack can add, retune or
 * replace vocabulary without touching code — and §8's tag-derived words will join the same corpus at
 * boot. The resolved recipe is what persists, never the words (§4.6), so a pack changing its vocabulary
 * cannot rewrite an Age already written.
 */
data class Word(
    /** Where the word was defined. Its path is what a writer says: `agesandtheart:floating` → "floating". */
    val id: ResourceLocation,
    val tier: Tier,
    /**
     * The slots this word may fill — **empty meaning anywhere**.
     *
     * The spike's single most important finding (§4.4): without this, a word gets a say in every slot
     * where any preset happens to carry any of its tags, so `stormy` — a word about the sky — pinned the
     * *landform* to caverns and discarded `floating` in silence. It cannot be derived from tag data,
     * because that a tag legitimately describes a landform is exactly what tagging a landform with it
     * says; which slot a word is *about* is authorial intent and lives nowhere else.
     *
     * Empty is right for evocative words specifically: spanning slots is what makes a word evocative.
     * Phase 4 will absorb this into grammatical role, so treat it as a placeholder rather than a schema.
     */
    val slots: Set<Slot>,
    /**
     * The tags this word asks for, weighted — and **signed**, because a query may push away as well as
     * pull (§3.3). Without negative weights "beautiful" reliably produced an ocean of lava, lava being
     * defensibly `dramatic`; "beautiful" really does mean *not hostile* as much as it means *lush*.
     *
     * Content tags stay strictly unipolar, so the case against bipolar axes is untouched: the negativity
     * belongs to the query, never to the world, and two negative-carrying words down-weight
     * independently rather than cancelling to mush.
     */
    val query: Map<String, Double>,
) {
    /** What a writer says to use it. */
    val name: String get() = id.path

    /** The tags this word wants, which are the ones that must have a carrier somewhere (§3.3). */
    val wanted: Set<String> get() = query.filterValues { it > 0.0 }.keys

    /**
     * How strongly [tags] answers this word's *positive* terms — the number a narrowing word thresholds.
     *
     * The strongest single term rather than a sum, because a restrictive word asking for two tags is
     * asking for either: "arid" reaching both `barren` and `dry` should keep a preset that is thoroughly
     * one of them, not demand both.
     */
    fun pull(tags: Map<String, Double>): Double =
        query.filterValues { it > 0.0 }.maxOfOrNull { (tag, weight) -> weight * (tags[tag] ?: 0.0) } ?: 0.0

    /**
     * How much this word likes [tags] overall, positive terms and negative ones together — the number an
     * evocative word tilts a draw by.
     *
     * A dot product here, where [pull] takes a maximum, and the difference is the tier distinction
     * itself: narrowing asks "does this qualify at all", tilting asks "how well does this answer",
     * which is a question every term deserves a say in.
     */
    fun affinityFor(tags: Map<String, Double>): Double =
        query.entries.sumOf { (tag, weight) -> weight * (tags[tag] ?: 0.0) }

    /**
     * Whether this preset qualifies for this word at its own tier's strictness.
     *
     * The `> 0` is not redundant with the threshold: [Tier.EVOCATIVE]'s threshold is zero, and a preset
     * that answers a word not at all must never count as qualifying for it.
     */
    fun accepts(tags: Map<String, Double>): Boolean {
        val strength = pull(tags)
        return strength > 0.0 && strength >= tier.threshold
    }

    override fun toString(): String = name

    companion object {
        /**
         * A word as its file says it, the id coming from where the file *is* — the same convention every
         * vanilla datapack registry follows, and one less thing a content author can spell inconsistently.
         */
        fun mapCodec(id: ResourceLocation): MapCodec<Word> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Tier.CODEC.fieldOf("tier").forGetter(Word::tier),
                SLOT_SET_CODEC.optionalFieldOf("slots", emptySet()).forGetter(Word::slots),
                Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).fieldOf("query").forGetter(Word::query),
            ).apply(instance) { tier, slots, query -> Word(id, tier, slots, query) }
        }

        private val SLOT_CODEC: Codec<Slot> = StringRepresentable.fromEnum(Slot::values)

        // A set rather than a list: a word saying "landform landform" means nothing, and pricing counts
        // slots constrained.
        private val SLOT_SET_CODEC: Codec<Set<Slot>> = SLOT_CODEC.listOf().xmap({ it.toSet() }, { it.toList() })
    }
}

/**
 * What one preset is *like*: the tags it carries, and how readily the Art reaches for it.
 *
 * Tag membership is **unipolar and weighted**, so a thing may be neither `lush` nor `barren`, or oddly
 * both (§3.3). Being oddly both is not a defect to design out — it is how the world absorbs a
 * contradiction without anyone having to arbitrate one. Bipolar axes could not do that: on a single scalar
 * "lush, barren" averages to temperate nothing, where independent tags give cherry groves abutting desert.
 *
 * Tags belong to **presets and content, never to primitives** — an `Ellipsoid` is not lush. Tier A stays
 * pure mechanism; Tier B carries meaning.
 */
data class PresetProfile(
    val tags: Map<String, Double>,
    /**
     * How willingly the Art reaches for this preset when **nothing in the sentence asked**, relative to
     * its siblings. One is ordinary; a quarter is something it would rather not do unbidden.
     *
     * This is not in the design as written, and the implementation wanted it badly enough to be worth
     * adding: without a prior, an unconstrained slot draws uniformly, so a sentence that said nothing
     * about the sea got a sea of *lava* one time in three. That is not the generator being wild, it is the
     * generator ignoring the plain reading of a vague sentence — and §8.2's "vagueness draws only from the
     * curated pool" is exactly the promise being broken. Precision still reaches anything: a writer who
     * asks for lava gets lava, because readiness only weights an *unasked* draw.
     *
     * Per §1 this is a default awaiting vocabulary like any other — the word that will take it away is one
     * for deliberate strangeness, something like *unruly*.
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

/** One slot's worth of profiles, which is one `art/preset_tags/<slot>.json`. */
data class PresetTags(private val byPreset: Map<String, PresetProfile>) {
    fun of(preset: SlotPreset): PresetProfile = byKey(preset.key)

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
