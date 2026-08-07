package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.AspectPreset
import com.mojang.serialization.Codec
import kotlin.random.Random
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
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
    val id: Identifier,
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
    /**
     * Parameters this word **might** choose — the breadth of what it means, drawn from per Age (§4.4).
     *
     * A broad word covers a spectrum, and [sets] alone could not say so: `scorching` named evaporation,
     * sunburn and embers outright, so every scorched Age was the same scorched Age. What varies is not
     * *whether* a word lands but *which of its facets* do, so the word declares a core it always applies
     * and a pool it draws [draws] of.
     *
     * **The core is what makes it that word**, and the pool is what makes this one different from the last:
     * a drawn subset can never leave an Age un-scorched, because `sets` was never in the draw. Together
     * they are also the whole of what a word *can* do, which is a different question from what it does
     * here — see [canSet] against [setsDrawnAt].
     */
    /**
     * What this word thinks of particular presets, by key — **said outright, where a tag is too coarse**.
     *
     * Tags carry the broad strokes and reach content nobody enumerated: a pack tags its own biome `lovely`
     * in one file and every word wanting `lovely` finds it. That generalisation is the whole reason they
     * exist and it is not up for negotiation. What they were also being asked to do is *taxonomise every
     * aspect exhaustively*, so that `beautiful` could only ever be as precise as the tag set allowed, and
     * ten biomes carrying tags at all is what that ambition actually amounted to (Jonah, 2026-08-06).
     *
     * So a word may also just say what it means. **Direct beats tag**: where a weight names a preset, it is
     * the answer and the query is not consulted for it. The tag set no longer has to be complete — only
     * useful — because anything it is too coarse for can be said here instead.
     *
     * Negative weights work and mean what they look like: this word wants *not* that.
     *
     * **Keyed by aspect**, and that is not ceremony. An open aspect makes a preset out of any id it is
     * handed, so a flat list of ids offered to every aspect turned `beautiful`'s biomes into candidate
     * *structure sets* — which then broke `untouched`, a word that empties a population by striking
     * everything in it, because the pool it had to strike was suddenly full of biomes.
     */
    val weights: Map<Aspect, Map<String, Double>> = emptyMap(),
    val pool: Map<String, String> = emptyMap(),
    /**
     * How many of [pool] an Age takes. Zero means none of it, and a number at or past the pool's size
     * means all of it — so a word with a pool and no `draws` is simply a word with more `sets`.
     */
    val draws: Int = 0,
) {
    /** What a writer says to use it. */
    val name: String get() = id.path

    /**
     * Everything this word could ever choose — its core and its whole pool.
     *
     * **The capability question, and not the same as [setsDrawnAt].** Whether a word belongs in an aspect,
     * whether it steers anything at all, and whether the world can back it are all questions about what it
     * *means*, which a draw must not move: a word charged as unbacked because this Age's draw happened to
     * miss the parameter that would have landed is a writer paying for a coin they did not toss.
     */
    val canSet: Map<String, String> get() = sets + pool

    /** What separates one alternative from the next inside a single value. */
    private val ALTERNATIVE = '|' 

    /**
     * What it actually chooses in the Age [draw] belongs to — the core, and [draws] of the pool.
     *
     * Salted by the word's own id, so two broad words in one sentence draw differently and the same word
     * draws the same thing every time the Age is rebuilt. Resolution is a pure function of (vocabulary,
     * sentence, seed) and this stays inside that promise.
     */
    fun setsDrawnAt(draw: Long): Map<String, String> = facetsDrawnAt(draw).mapValues { (parameter, value) ->
        oneOf(value, draw, parameter)
    }

    /**
     * Whether anything about this word is left to the Age — a pool to draw from, or a value with
     * alternatives in it. A word with neither is the same word in every world it appears in.
     */
    val varies: Boolean get() = (pool.isNotEmpty() && draws > 0) || (sets + pool).values.any { ALTERNATIVE in it }

    /**
     * One of `a|b|c`, chosen for this Age — **which value**, where the pool chooses **which parameter**.
     *
     * The two compose and are deliberately separate questions: a word may offer three skies and take one,
     * offer five facets and wear two, or both. Salted by the parameter's own name as well as the word's, so
     * two parameters offering the same alternatives do not move together.
     */
    private fun oneOf(value: String, draw: Long, parameter: String): String {
        if (ALTERNATIVE !in value) return value
        val offered = value.split(ALTERNATIVE).map(String::trim).filter(String::isNotEmpty)
        if (offered.size <= 1) return offered.firstOrNull() ?: value
        val seed = scrambled(draw xor id.hashCode().toLong() xor parameter.hashCode().toLong())
        return offered[Random(seed).nextInt(offered.size)]
    }

    /**
     * [value] mixed until adjacent inputs give unrelated outputs — SplitMix64's finalizer.
     *
     * **Handing a seed straight to `Random` is not enough**, and this is the second time that has bitten.
     * Two Ages written a seed apart differ in a handful of low bits; xoring in a word's name and a
     * parameter's shifts those bits but does not spread them, and `nextInt(3)` over such seeds came back
     * with the same answer every time — fourteen scorching Ages, fourteen embers. An avalanche step makes
     * one bit of input change half the output, which is the property a draw needed all along.
     */
    private fun scrambled(value: Long): Long {
        var mixed = value + GOLDEN
        mixed = (mixed xor (mixed ushr 30)) * FIRST_MIX
        mixed = (mixed xor (mixed ushr 27)) * SECOND_MIX
        return mixed xor (mixed ushr 31)
    }

    private fun facetsDrawnAt(draw: Long): Map<String, String> {
        if (pool.isEmpty() || draws <= 0) return sets
        if (draws >= pool.size) return sets + pool
        // Sorted first so the map's own iteration order cannot reach the answer, then shuffled by a
        // generator seeded from the Age and the word. An earlier version sorted by a hash of the two
        // xored together and drew the *same* facets every time: the draw only moves low bits, and the
        // keys' hashes differ by far more than that, so nothing ever reordered.
        val order = pool.keys.sorted().shuffled(Random(scrambled(draw xor id.hashCode().toLong())))
        return sets + order.take(draws).associateWith { pool.getValue(it) }
    }

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
    fun pullOn(preset: AspectPreset, tags: Map<String, Double>): Double = when {
        names == preset.key -> NAMED_OUTRIGHT
        else -> weightOn(preset) ?: pull(tags)
    }

    /** What this word says about [preset] by name, in the aspect it belongs to, or null where it is silent. */
    fun weightOn(preset: AspectPreset): Double? =
        weights.entries.firstNotNullOfOrNull { (aspect, byPreset) ->
            byPreset[preset.key]?.takeIf { aspect.presetFor(preset.key) != null }
        }

    /** [affinityFor], with a direct weight winning where this word named this preset outright. */
    fun affinityOn(preset: AspectPreset, tags: Map<String, Double>): Double =
        weightOn(preset) ?: affinityFor(tags)

    /** [accepts], asked of a preset this word may have an opinion about by name. */
    fun acceptsOn(preset: AspectPreset, tags: Map<String, Double>): Boolean {
        val strength = pullOn(preset, tags)
        return strength > 0.0 && strength >= tier.threshold
    }

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
        // SplitMix64's finalizer, unchanged: the odd increment walks the whole 64-bit space and the two
        // multipliers are what spread one changed bit across all of them.
        private const val GOLDEN = -0x61c8864680b583ebL
        private const val FIRST_MIX = -0x40a7b892e31b1a47L
        private const val SECOND_MIX = -0x6b2fb644ecceee15L

        /** What naming a thing outright is worth, against a tag weight, which never exceeds one. */
        private const val NAMED_OUTRIGHT = 1.0

        /** A word as its file says it, the id coming from where the file *is*, like every vanilla registry. */
        fun mapCodec(id: Identifier): MapCodec<Word> = RecordCodecBuilder.mapCodec { instance ->
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
                Codec.unboundedMap(ASPECT_CODEC, Codec.unboundedMap(Codec.STRING, Codec.DOUBLE))
                    .optionalFieldOf("weights", emptyMap()).forGetter(Word::weights),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("pool", emptyMap())
                    .forGetter(Word::pool),
                Codec.INT.optionalFieldOf("draws", 0).forGetter(Word::draws),
            ).apply(instance) { tier, aspects, query, names, sets, weights, pool, draws ->
                Word(id, tier, aspects, query, names.orElse(null), sets, weights, pool, draws)
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
