package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.MATERIAL_PARAMETERS
import co.voik.agesandtheart.age.aspect.Taggable
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import kotlin.random.Random
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import net.minecraft.util.StringRepresentable
import java.util.Optional

/**
 * A page name as an [Aspect] — **file-private rather than `Word`'s own**, because [Claims] needs it too
 * and is declared above `Word`. It is the same reading `/age compose` and every tag table use.
 */
private val ASPECT_CODEC: Codec<Aspect> = Codec.STRING.comapFlatMap(
    { named ->
        Aspect.entries.firstOrNull { it.page == named }
            ?.let { DataResult.success(it) }
            ?: DataResult.error { "no part of the world is called '$named'" }
    },
    Aspect::page,
)

// A set rather than a list: "terrain terrain" means nothing, and pricing counts aspects constrained.
private val ASPECT_SET_CODEC: Codec<Set<Aspect>> = ASPECT_CODEC.listOf().xmap({ it.toSet() }, { it.toList() })

/**
 * One side of what a word does to the world's parameters — **what it always turns, and what it might.**
 *
 * The same three fields appear twice on a [Word]: once flat at the top level, where they are what the word
 * *demands*, and once under `requests`, where they are what it merely *offers* (`the-world-model.md` §5).
 * Strength and certainty are separate questions and this is the half that answers certainty, so either
 * strength may be a plain claim or a pool drawn from.
 *
 * Flat at the top level rather than under a `required` block of its own, because a word that only demands
 * is nearly every word there is: `{"tier": "exact", "sets": {"colour": "red"}}` should not have to say so.
 */
data class Claims(
    val sets: Map<String, String> = emptyMap(),
    val pool: Map<String, String> = emptyMap(),
    val draws: Int = 0,
    /**
     * Tags this side wants, **keyed by aspect and never flat** — how an offer reaches the *choice* of
     * preset rather than a parameter on the one that was chosen. An inferno's sea is the case: a sea is picked
     * from a catalogue by tag, and there is no parameter that says "lava".
     *
     * Keyed because a flat query must never be derived from (§4.4's worst finding: `stormy` means
     * `gloomy`, `caverns` is also `gloomy`, and a word about the sky pinned the ground). An offer states
     * its aspect and so may widen the reach honestly, exactly as [Word.queries] does.
     */
    val queries: Map<Aspect, Map<String, Double>> = emptyMap(),
) {
    val isEmpty: Boolean get() = sets.isEmpty() && pool.isEmpty() && queries.isEmpty()

    /** Everything this side could ever turn, whatever an Age's draw settles on. */
    val everything: Map<String, String> get() = sets + pool

    companion object {
        val NOTHING = Claims()

        val CODEC: Codec<Claims> = RecordCodecBuilder.create { instance ->
            // Queries before parameters, as `Word`'s own codec has it — a word file reads the same way at both
            // levels, so nobody has to learn a second order for the nested block.
            instance.group(
                Codec.unboundedMap(ASPECT_CODEC, Codec.unboundedMap(Codec.STRING, Codec.DOUBLE))
                    .optionalFieldOf("queries", emptyMap()).forGetter(Claims::queries),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("sets", emptyMap())
                    .forGetter(Claims::sets),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("pool", emptyMap())
                    .forGetter(Claims::pool),
                Codec.INT.optionalFieldOf("draws", 0).forGetter(Claims::draws),
            ).apply(instance) { queries, sets, pool, draws -> Claims(sets, pool, draws, queries) }
        }
    }
}

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
     * `floating` in silence.
     *
     * **Partly derived, and only ever widened** ([reaching]). A parameter is owned by exactly one aspect,
     * so a word that sets one must reach that aspect or the setting is silently inert; the codec unions
     * those in, which closes that hole and is the whole of what deriving buys. It cannot replace the
     * declaration, because what a word reaches through its query or its weights is invisible from here:
     * `clear` sets `murk` — how far you see underwater — and is *also* a clear sky, which is the `bright`
     * on `plain` and nothing a parameter can say. Reach by tag stays authorial intent and lives nowhere
     * else.
     */
    val aspects: Set<Aspect>,
    /**
     * Tags this word asks of **every part of the world at once** — spelled `queries: { all: … }`.
     *
     * **Only an evocative word may have one**, and for it the global reach is the whole point: a word
     * that does not narrow is written on the Age rather than on a part of it, and `Constraint.aimedAt` is
     * empty for one because nothing consults it. `beautiful` leans the whole world green and cannot rule
     * anything out.
     *
     * On a word that *narrows* it is §4.4's worst finding waiting to happen — `stormy` means `gloomy`,
     * `caverns` is also `gloomy`, and a word about the sky pinned the ground. `VocabularyCheck` refuses
     * one there, which is what lets this contribute nothing to [reaching] and stay safe by construction.
     */
    val everywhere: Map<String, Double> = emptyMap(),
    /**
     * The same, **asked only of one part of the world** — for a word that means different things in
     * different places rather than one thing everywhere.
     *
     * `clear` is the case: a clear sky is `bright` and clear water is a `murk` of nearly nothing, and a
     * flat query wanting `bright` had to be trusted not to find something bright to do in the sea. Keyed
     * by aspect exactly as [weights] is, and for the same reason — an aspect is the unit a claim lands in.
     *
     * **Merged over [query], not instead of it**: a word may mean something everywhere *and* something
     * more particular somewhere, and a tag named in both takes the keyed weight there. Most words want
     * neither half — `beautiful` means `lovely` wherever it lands, and enumerating that per aspect is the
     * exhaustive taxonomy §3.3 refused.
     */
    val queries: Map<Aspect, Map<String, Double>> = emptyMap(),
    /**
     * The one preset this word means in a part of the world, by its key — what makes a word referential
     * rather than evaluative (§8.1).
     *
     * A word that means a preset outright **does not search** for carriers, which is what keeps §8.2
     * structural: it arrives with its answer in hand, and a vague word can never reach it for want of a
     * name. That is also the one way to reach something `Vocabulary.askableIn` leaves out.
     *
     * **A name somebody chose**, and so not the same relation as [entryOf]: `spires` means
     * `spire_islands` and `inverted` means `inverse_caves`, mappings that exist nowhere else. A word that
     * simply *is* its content — every derived block word — says [entryOf] instead and repeats nothing.
     *
     * Keyed by aspect exactly as [queries] and [weights] are, and for the reason those are: an id says
     * nothing about which registry it belongs to, so an unkeyed name let `minecraft:diamond_block` be a
     * sea and a structure set and a biome at once.
     *
     * The key rather than a resolved [Taggable], so a word stays plain data and a key naming content this
     * pack lacks survives being read.
     */
    val meansExactly: Map<Aspect, String> = emptyMap(),
    /**
     * Parameters this word chooses, by name — how a word reaches a material (§3.2) or any other parameter.
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
    val pool: Map<String, String> = emptyMap(),
    /**
     * How many of [pool] an Age takes. Zero means none of it, and a number at or past the pool's size
     * means all of it — so a word with a pool and no `draws` is simply a word with more `sets`.
     */
    val draws: Int = 0,
    /**
     * The same three, **requested rather than required** — laid *under* the sentence instead of over it
     * (`the-world-model.md` §5).
     *
     * `scorching` "insists on the heat and offers a red sky, large suns, several of them", and until this
     * existed there was no way to write the second half: [pool] draws *which* facets fire, and then
     * demands whatever it drew. So an offer contended with the writer, and `a blue sun. an inferno Age.`
     * displaced one of them and charged somebody for a contradiction the writer never made — the same
     * fault the size words' coupling to mingling was retired for.
     *
     * **Strength and certainty are separate questions**, which is why this is the whole record again
     * rather than a flag: a word may insist on one thing always, insist on another sometimes, offer a
     * third always and offer a fourth sometimes, and an inferno wants at least three of those four.
     *
     * What it yields to is a *demand* on the same parameter of the same aspect, and nothing else. Two requests
     * settle between themselves exactly as two demands would, and neither is ever charged for the other.
     */
    val requests: Claims = Claims.NOTHING,
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
    /**
     * The **world this book starts from**, by its key, or null for the overwhelming majority of words that
     * say something about a world rather than choosing one (`the-world-model.md` §4).
     *
     * Not an aspect and deliberately not one: an aspect is a part of the world a writer aims at, where this
     * is *which world they began with*. Making it an aspect would mint an aiming page for it, and there is
     * no honest name for that page — the Age is the world.
     *
     * Two words naming different templates is a thing a sentence can say and the first laid wins, which is
     * the one place order decides anything. It is rare enough to be worth the simplicity: these are found
     * pages, and holding two is already unusual.
     */
    val template: String? = null,
    /**
     * The **pattern this word mints from** — a placed feature the game already has, whose shape a new one
     * borrows (world model §2). Null for every word that is not one of the few.
     *
     * `springs` mints from vanilla's water spring and `veins` from an ore, so `ink springs` and `gold block
     * veins` are that shape carrying a substance the game never puts there. The material comes from the
     * same clause, which is the one thing about it a writer says.
     *
     * **A minting word aims**, because it closes the clause the material qualifies — the same shape `sun`
     * has, and the reason a material may stand somewhere nothing is *made of* anything.
     */
    val mints: String? = null,
    /**
     * Whether the pattern [mints] names can only be made of something that **flows**.
     *
     * A spring runs with a fluid, and `fluidState` of a block that is not one is `Fluids.EMPTY` — so
     * `gold_block springs` would rebuild a spring that placed nothing at all. What it gets instead is a
     * spring that *tried*: the substance seeping from the wall and setting a block or two down, charged
     * for as a displacement (Jonah, 2026-08-25, walked).
     *
     * **On the word rather than read off the pattern**, so the resolver can charge for it: instability is
     * a pure function of (vocabulary, sentence, seed), and asking a `PlacedFeature` what its configuration
     * is means a registry the resolver has no business holding. The corpus already decides which patterns
     * are worth minting from; this is the same statement carried one step further.
     *
     * A boolean because there are two patterns and one distinction. A third that wanted something else —
     * a pattern that can only be made of a full block, say — makes this an enumeration, and the word data
     * is where it would be spelled either way.
     */
    val mintsSomethingThatFlows: Boolean = false,
    /**
     * The registry this word **is** an entry of, where its own name is the content's id and nobody chose
     * it — `minecraft:stone`, said `stone`.
     *
     * The other half of [meansExactly], and a different fact rather than a shorthand for the same one.
     * Writing the key here would restate the word's own id 1168 times and say nothing; what is genuinely
     * unknown is the registry, because **an id cannot say which one it belongs to** —
     * `minecraft:diamond_block` and `minecraft:village_plains` are the same shape, and an open aspect's
     * `presetFor` parses rather than looks up. Without it every derived word meant a preset in *every*
     * open aspect: a block was a sea and a structure set and a biome at once, and `diamond_block
     * structures` parsed, resolved, charged ink and generated a structure set no registry holds.
     *
     * The aspects it answers for fall out of [Aspect.presetsAreEntriesOf] rather than being listed.
     *
     * Not in the codec: a pack author writes [meansExactly], which says the same thing about one entry in
     * the language the file already speaks. This is what a *derivation* over a whole registry says instead.
     */
    val entryOf: ResourceKey<out Registry<*>>? = null,
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
    val canSet: Map<String, String> get() = everySet + bare(pool) + bare(requests.sets) + bare(requests.pool)

    /** The demanded half, as one record — the shape [requests] already has, for the code that asks both. */
    val required: Claims get() = Claims(sets, pool, draws)

    /**
     * Whether anything here names the aspect it is meant for — `sun.absent` rather than `absent`.
     *
     * Held rather than asked, because [setsIn] is called for every word in a sentence against every
     * aspect it might land in, and the answer is no for nearly every word ever written. Where it is no,
     * that method hands back the map it already has and allocates nothing.
     */
    private val someParameterNamesItsAspect: Boolean =
        (sets.keys + pool.keys + requests.sets.keys + requests.pool.keys).any(::namesAnAspect)

    /**
     * What this word sets on [aspect] — what it sets everywhere, and what it sets **only** here.
     *
     * The same shape as [queryIn], and for the same reason an aspect keys [weights]: an aspect is the unit
     * a claim lands in. What it adds is the other direction — a word that must *not* say the same thing
     * everywhere it could. `sun.absent` and `moon.absent` are one parameter on two bodies, and `sunless` means
     * only the first; without a way to say so the two words would be indistinguishable, since
     * [reaching] only ever widens (`decisions.md`).
     *
     * An unqualified key still reaches every aspect owning it, which is what makes one `colour` word paint
     * eight aspects. Qualifying is the exception and reads as one.
     */
    fun setsIn(aspect: Aspect): Map<String, String> = meantFor(aspect, sets)

    /** What this word *requests* on [aspect] — [setsIn] for the half that yields rather than demands. */
    fun requestsIn(aspect: Aspect): Map<String, String> = meantFor(aspect, requests.sets)

    private fun meantFor(aspect: Aspect, parameters: Map<String, String>): Map<String, String> =
        if (!someParameterNamesItsAspect) parameters else parameters.mapNotNull { (spelled, value) ->
            val meant = aspectMeantBy(spelled)
            if (meant != null && meant != aspect) null else parameterNameIn(spelled) to value
        }.toMap()

    /** Everything it could set anywhere, under plain names — the capability question, never the landing one. */
    val everySet: Map<String, String> get() = bare(sets)

    private fun bare(parameters: Map<String, String>): Map<String, String> =
        if (!someParameterNamesItsAspect) parameters else parameters.mapKeys { parameterNameIn(it.key) }

    /**
     * The aspects a key names that no aspect answers to — a typo, reported at load rather than ignored.
     *
     * A key nothing can read is inert, which is §3.3's silent drop wearing a different hat: the word costs
     * a page, sets nothing, and says so nowhere.
     */
    val unreadableParameters: List<String>
        get() = (sets.keys + pool.keys + requests.sets.keys + requests.pool.keys)
            .filter { it.contains(PARAMETER_MARK) && aspectMeantBy(it) == null }

    /**
     * What it actually chooses in the Age [draw] belongs to — the core, and [draws] of the pool.
     *
     * Salted by the word's own id, so two broad words in one sentence draw differently and the same word
     * draws the same thing every time the Age is rebuilt. Resolution is a pure function of (vocabulary,
     * sentence, seed) and this stays inside that promise.
     */
    fun setsDrawnAt(draw: Long): Map<String, String> = settled(required, draw, REQUIRED_SALT)

    /**
     * The same for the requested half.
     *
     * **Salted apart from the required draw**, or one pool's shuffle would decide the other's: both are
     * seeded from the Age and the word, and two pools of the same size would then fire the same positions
     * every time — the facets a word demands and the ones it merely offers would move together for ever.
     */
    fun requestsDrawnAt(draw: Long): Map<String, String> = settled(requests, draw, REQUESTED_SALT)

    private fun settled(claims: Claims, draw: Long, salt: Long): Map<String, String> =
        facetsDrawnAt(claims, draw, salt).mapValues { (parameter, value) -> oneOf(value, draw, parameter) }

    /**
     * Whether anything about this word is left to the Age — a pool to draw from, or a value with
     * alternatives in it. A word with neither is the same word in every world it appears in.
     */
    val varies: Boolean
        get() = listOf(required, requests).any { claims ->
            (claims.pool.isNotEmpty() && claims.draws > 0) ||
                (claims.sets + claims.pool).values.any { ALTERNATIVE in it }
        }

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

    private fun facetsDrawnAt(claims: Claims, draw: Long, salt: Long): Map<String, String> {
        val (sets, pool, draws) = claims
        if (pool.isEmpty() || draws <= 0) return sets
        if (draws >= pool.size) return sets + pool
        // Sorted first so the map's own iteration order cannot reach the answer, then shuffled by a
        // generator seeded from the Age and the word. An earlier version sorted by a hash of the two
        // xored together and drew the *same* facets every time: the draw only moves low bits, and the
        // keys' hashes differ by far more than that, so nothing ever reordered.
        val order = pool.keys.sorted().shuffled(Random(scrambled(draw xor id.hashCode().toLong() xor salt)))
        return sets + order.take(draws).associateWith { pool.getValue(it) }
    }

    /**
     * The one preset this word means in [aspect], or null where it means nothing there in particular.
     *
     * Two ways to mean one: a name somebody chose for a preset ([meansExactly]), or being the registry
     * entry oneself ([entryOf]). The aspect has to be checked either way, because an open aspect parses
     * any well-formed id into its own kind of preset and would otherwise take a block for a biome.
     */
    fun meaningIn(aspect: Aspect): Taggable? {
        val itself = id.toString().takeIf { entryOf != null && aspect.presetsAreEntriesOf == entryOf }
        return (meansExactly[aspect] ?: itself)?.let(aspect::presetFor)
    }

    /**
     * Whether this word says nothing except which part of the world it is about — an **aiming page**
     * (§4.3.1), whose whole job is to open a section and scope what follows it.
     *
     * Recognised by shape rather than by a flag, because that shape *is* the definition: a word with no
     * query, no named preset and no parameter has nothing to contribute but its aspects.
     */
    val aims: Boolean get() = everyQuery.isEmpty() && meansNothingOutright && canSet.isEmpty() &&
        weights.isEmpty() && template == null && aspects.isNotEmpty()

    /** What this word asks of [aspect] — what it asks everywhere, and what it asks only here. */
    fun queryIn(aspect: Aspect): Map<String, Double> = everywhere + queries[aspect].orEmpty()

    /**
     * Every tag this word has an opinion about anywhere, which is the honest answer to "could it want X".
     *
     * Public as [everyTagAsked] for the checks and the forge, which used to read the flat `query` and now
     * have to ask across the keyed ones as well.
     */
    val everyTagAsked: Map<String, Double> get() = everyQuery

    private val everyQuery: Map<String, Double>
        get() = queries.values.fold(everywhere) { standing, next -> standing + next }

    /**
     * Whether this word has anything to say about *which* preset fills an aspect, as opposed to how that
     * preset is steered. A word that only sets a parameter must not be treated as narrowing: an empty
     * carrier set is how the resolver recognises a word the world cannot satisfy (§3.3).
     */
    val constrainsPresets: Boolean get() = !meansNothingOutright || everyQuery.values.any { it > 0.0 }

    /** Whether nothing anywhere is meant outright — neither a chosen name nor an entry it *is*. */
    private val meansNothingOutright: Boolean get() = meansExactly.isEmpty() && entryOf == null

    /**
     * The same question asked of one aspect, which is the honest form. A derived block word names a *sea*
     * and merely *sets* a material on the terrain — asked globally it claims to narrow every aspect it
     * speaks to, finds no carrier in most, and is charged as unbacked for an opinion it never had.
     */
    fun constrainsPresetsIn(aspect: Aspect): Boolean =
        meaningIn(aspect) != null || queryIn(aspect).values.any { it > 0.0 }

    /**
     * The tags this word wants, which are the ones that must have a carrier somewhere (§3.3).
     *
     * Every aspect's, unioned. This is asked by opposition-finding, where the question is whether two
     * words can ever be at odds, and a word that wants `bright` only overhead still wants it.
     */
    val wanted: Set<String> get() = everyQuery.filterValues { it > 0.0 }.keys

    /**
     * The tags this word pushes *away* — [wanted]'s mirror, and half of what lets two words be found to
     * disagree with no antonym table involved (`Vocabulary.disagreement`).
     */
    val unwanted: Set<String> get() = everyQuery.filterValues { it < 0.0 }.keys

    /**
     * Every tag this word merely **offers** an opinion about — deliberately apart from [wanted].
     *
     * The coverage checks want these: a misspelled tag in an offer is as inert as one in a demand. What
     * must *not* see them is `Vocabulary.disagreement`, which reads [wanted] to say two words cannot both
     * stand — an offer yields rather than argues, so an inferno offering the sea `molten` does not
     * contradict a writer who wrote `drowned`; it simply is not there.
     */
    val offeredTags: Set<String> get() = requests.queries.values.flatMap { it.keys }.toSet()

    /**
     * How much this word would *lean* the draw in [aspect] toward a preset carrying [tags] — a tilt, and
     * so the one kind of claim that yields by construction: where a demand narrowed the aspect to one
     * survivor there is nothing left for a tilt to choose between.
     */
    fun offeredAffinityIn(aspect: Aspect, tags: Map<String, Double>): Double =
        requests.queries[aspect].orEmpty().entries.sumOf { (tag, weight) -> weight * (tags[tag] ?: 0.0) }

    /**
     * The block this word names, or null where it names none — every derived block word sets one, and
     * nothing authored does.
     *
     * Asked of [Parameter.material] rather than of a parameter by name. Two readers wanted this and both
     * looked for `Terrain.STONE` by name: `Grammar` to decide a page is a material at all, and `Resolver`
     * to find what a minted pattern is made of. One aspect's parameter was standing in for "a block".
     */
    val material: String? get() = sets.entries.firstOrNull { it.key in MATERIAL_PARAMETERS }?.value

    /**
     * **How many places this page may be laid** — the second half of what it costs (world model §9).
     *
     * An evocative word is one: it may only ever be written on the Age itself, which is what makes it the
     * cheapest thing in the language *with no exception written anywhere*. A narrowing word is at home in
     * as many parts of the world as it declares, and one that landed nowhere is priced as though it landed
     * somewhere — being empty on purpose so `DerivedAspectsCheck` can refuse it, not so it can be free.
     */
    val versatility: Int get() = if (!tier.narrows) ONE_PLACE else aspects.size.coerceAtLeast(ONE_PLACE)

    /**
     * **What this page costs: specificity × versatility** (world model §9).
     *
     * Precision is what a writer is buying, so precision is priced; and a page usable in several places is
     * a better page to own than one usable in one, so **the charge is for what the page *can* do** and is
     * the same wherever it is laid. `clear` is a clear sky and clear water alike where `murky` is only ever
     * the water, and the dearer of the two is the one worth owning.
     *
     * One number with two readers, which is the point of it being here rather than in either: the book's
     * cost is the sum of its pages ([Resolver.resolve]) and the page's own
     * price is what the desk charges for writing it (`WriteCost`). They were separately computed and
     * disagreed — the desk priced by tier alone, so versatility was charged to a book nobody paid for and
     * not to the page anybody buys.
     */
    val price: Int get() = tier.cost * versatility

    /**
     * How strongly [tags] answers this word's *positive* terms in [aspect] — the number a narrowing word
     * thresholds. The strongest single term rather than a sum, because a word asking for two tags asks
     * for either.
     */
    fun pullIn(aspect: Aspect, tags: Map<String, Double>): Double =
        queryIn(aspect).filterValues { it > 0.0 }
            .maxOfOrNull { (tag, weight) -> weight * (tags[tag] ?: 0.0) } ?: 0.0

    /**
     * How much this word likes [tags] in [aspect], positive and negative terms together — what an
     * evocative word tilts a draw by. A dot product where [pullIn] takes a maximum, which is the tier
     * distinction: narrowing asks "does this qualify at all", tilting asks "how well does this answer".
     */
    fun affinityIn(aspect: Aspect, tags: Map<String, Double>): Double =
        queryIn(aspect).entries.sumOf { (tag, weight) -> weight * (tags[tag] ?: 0.0) }

    /**
     * How strongly this word claims one particular preset — [pull], except that naming a preset claims it
     * absolutely. Without this a derived word would be scored on tags it does not have, so "creosote oil
     * beside a lava sea" gave creosote the *smaller* share.
     */
    fun pullOn(preset: Taggable, tags: Map<String, Double>): Double = when {
        meaningIn(preset.aspect)?.key == preset.key -> MEANT_EXACTLY
        // The preset carries the aspect, so a per-aspect query needs no argument threaded to it: what a
        // word asks of a candidate is decided by where the candidate lives.
        else -> weightOn(preset) ?: pullIn(preset.aspect, tags)
    }

    /** What this word says about [preset] by name, in the aspect it belongs to, or null where it is silent. */
    fun weightOn(preset: Taggable): Double? =
        weights.entries.firstNotNullOfOrNull { (aspect, byPreset) ->
            byPreset[preset.key]?.takeIf { aspect.presetFor(preset.key) != null }
        }

    /** [affinityIn], with a direct weight winning where this word named this preset outright. */
    fun affinityOn(preset: Taggable, tags: Map<String, Double>): Double =
        weightOn(preset) ?: affinityIn(preset.aspect, tags)

    /** [accepts], asked of a preset this word may have an opinion about by name. */
    fun acceptsOn(preset: Taggable, tags: Map<String, Double>): Boolean {
        val strength = pullOn(preset, tags)
        return strength > 0.0 && strength >= tier.threshold
    }

    /**
     * Whether this preset qualifies for this word at its tier's strictness. The `> 0` is not redundant
     * with the threshold: [Tier.EVOCATIVE]'s threshold is zero, and a preset that answers the word not at
     * all must never qualify.
     */
    fun acceptsIn(aspect: Aspect, tags: Map<String, Double>): Boolean {
        val strength = pullIn(aspect, tags)
        return strength > 0.0 && strength >= tier.threshold
    }

    override fun toString(): String = name

    companion object {
        // SplitMix64's finalizer, unchanged: the odd increment walks the whole 64-bit space and the two
        // multipliers are what spread one changed bit across all of them.
        private const val GOLDEN = -0x61c8864680b583ebL
        private const val FIRST_MIX = -0x40a7b892e31b1a47L
        private const val SECOND_MIX = -0x6b2fb644ecceee15L

        /** What meaning a thing outright is worth, against a tag weight, which never exceeds one. */
        private const val MEANT_EXACTLY = 1.0

        /** What separates one alternative from the next inside a single value. */
        private const val ALTERNATIVE = '|'

        /** What a page at home in one part of the world is worth, as versatility — see [Word.price]. */
        private const val ONE_PLACE = 1

        /** What keeps the two pools' draws independent — see [Word.requestsDrawnAt]. */
        private const val REQUIRED_SALT = 0L
        private const val REQUESTED_SALT = 0x5eed_0ffe_2ed0_1a1dL

        /**
         * Where a word speaks, **derived from everything it claims and from nothing else.**
         *
         * A word acts on an aspect four ways, and three of them say which aspect outright: a parameter
         * ([steers]) is owned by exactly one, a preset [meant] outright is keyed by the aspect it is meant
         * in, and [weighted] is keyed already. Each is unioned in, because a claim landing in an aspect the
         * word does not reach is never applied — and worse than unapplied, it counts against the draw in
         * the aspect the word *does* reach as a parameter nothing there honours.
         *
         * **The fourth is a tag query, and it must not be derived from.** Tags are properties of the
         * world, not of a word: `stormy` means `gloomy`, `gloomy` is also on `caverns`, so a word about
         * the sky pinned the *terrain* to caverns and discarded `floating` in silence (§4.4, the spike's
         * single most important finding). A query says what a word likes, never where it belongs.
         *
         * **[Tier] decides what claiming nowhere means.** An evocative word means *anywhere*, because
         * tilting everywhere is what makes it evocative: `beautiful` nudges the climate axes and weights
         * the biomes, and shutting it into those two would stop it being beautiful anywhere else. A
         * narrowing word that lands nowhere is left empty on purpose, so `DerivedAspectsCheck` can refuse
         * it — a word that removes candidates and is aimed at nothing removes them everywhere.
         *
         * There used to be a declared set of aspects laid alongside, and its only real job was aiming a
         * flat query: every other claim already names the parts of the world it touches, so declaring was
         * either the one thing holding a word up or noise. Measured before it went: of 128 authored
         * words, 47 needed it and every one of those was a bare `query`; 33 declared it and changed
         * nothing. Keying the query says the same thing in one place, so the declaration went.
         */
        fun reaching(
            steers: Map<String, String>,
            meant: Set<Aspect>,
            weighted: Set<Aspect>,
            /** A pattern this word mints a member out of — see [mints], and `Resolver.mintedFeatures`. */
            minted: String? = null,
        ): Set<Aspect> {
            val steered = Aspect.entries.filter { aspect ->
                steers.keys.any { spelled ->
                    val meantBy = aspectMeantBy(spelled)
                    (meantBy == null || meantBy == aspect) && aspect.ownsParameterNamed(parameterNameIn(spelled))
                }
            }
            // **Minting is the features' own.** `Resolver.mintedFeatures` is the only reader of `mints`
            // and it makes a placed feature out of the pattern, so a word that mints is a word about
            // them — `lakes`, `springs` and `veins` claim nothing else at all.
            val mints = if (minted == null) emptySet() else setOf(Aspect.FEATURES)
            return (steered + meant + weighted + mints).toSet()
        }

        /** The `queries` key that means every part of the world at once — see [everywhere]. */
        const val EVERYWHERE = "all"

        /**
         * `queries`, keyed by aspect page or by [EVERYWHERE].
         *
         * Validated on the way in so a mistyped page is a word that fails to load and is reported, rather
         * than a query that quietly asks nothing of nowhere.
         */
        private val QUERIES_CODEC: Codec<Map<String, Map<String, Double>>> =
            Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Codec.DOUBLE))
                .comapFlatMap(
                    { raw ->
                        val strange = raw.keys.filterNot { said ->
                            said == EVERYWHERE || Aspect.entries.any { it.page == said }
                        }
                        if (strange.isEmpty()) {
                            com.mojang.serialization.DataResult.success(raw)
                        } else {
                            com.mojang.serialization.DataResult.error {
                                "queries names ${strange.joinToString(" ")}, which is no aspect page nor '$EVERYWHERE'"
                            }
                        }
                    },
                    { it },
                )

        /** A word as its file says it, the id coming from where the file *is*, like every vanilla registry. */
        fun mapCodec(id: Identifier): MapCodec<Word> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Tier.CODEC.fieldOf("tier").forGetter(Word::tier),
                // **One field for both**, keyed by aspect page or by `all` — see [everywhere]. A key
                // that names neither is a parse error rather than a silently dropped query.
                QUERIES_CODEC.optionalFieldOf("queries", emptyMap()).forGetter { word ->
                    word.queries.mapKeys { it.key.page } +
                        (if (word.everywhere.isEmpty()) emptyMap() else mapOf(EVERYWHERE to word.everywhere))
                },
                Codec.unboundedMap(ASPECT_CODEC, Codec.STRING).optionalFieldOf("means_exactly", emptyMap())
                    .forGetter(Word::meansExactly),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("sets", emptyMap())
                    .forGetter(Word::sets),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("pool", emptyMap())
                    .forGetter(Word::pool),
                Codec.INT.optionalFieldOf("draws", 0).forGetter(Word::draws),
                Claims.CODEC.optionalFieldOf("requests", Claims.NOTHING).forGetter(Word::requests),
                Codec.unboundedMap(ASPECT_CODEC, Codec.unboundedMap(Codec.STRING, Codec.DOUBLE))
                    .optionalFieldOf("weights", emptyMap()).forGetter(Word::weights),
                Codec.STRING.optionalFieldOf("template").forGetter { Optional.ofNullable(it.template) },
                Codec.STRING.optionalFieldOf("mints").forGetter { Optional.ofNullable(it.mints) },
                Codec.BOOL.optionalFieldOf("mints_something_that_flows", false)
                    .forGetter(Word::mintsSomethingThatFlows),
            ).apply(instance) {
                tier, queries, meansExactly, sets, pool, draws, requests, weights, template,
                mints, flows,
                ->
                val everywhere = queries[EVERYWHERE].orEmpty()
                val keyed = queries.filterKeys { it != EVERYWHERE }
                    .mapNotNull { (page, tags) -> Aspect.entries.firstOrNull { it.page == page }?.to(tags) }
                    .toMap()
                // **Both halves widen the reach.** A word that only *offers* to redden a sun is still a
                // word about the sun, and one that reached nowhere would have its offer skipped in the
                // only aspect it meant it — which is the silent drop §3.3 exists to forbid.
                val steers = sets + pool + requests.everything
                // `all` deliberately adds nothing: a global tilt is not a claim on any one part.
                val aimed = weights.keys + keyed.keys + requests.queries.keys
                val reaches = reaching(steers, meansExactly.keys, aimed, mints.orElse(null))
                Word(
                    id, tier, reaches, everywhere, keyed, meansExactly, sets, pool, draws, requests,
                    weights, template.orElse(null), mints.orElse(null), flows,
                )
            }
        }

        /**
         * **By page, not by key.** A word file is corpus content that a person authors and the game re-reads
         * on every load, so it names parts of the world the way a writer does. Only a *save* is written in
         * keys, and no save holds one of these.
         */
        /** What separates the aspect a parameter is meant for from the parameter — `/age compose`'s own spelling. */
        private const val PARAMETER_MARK = '.'

        private fun namesAnAspect(spelled: String) = spelled.contains(PARAMETER_MARK)

        /** The aspect a key names, or null where it names none — including where it names one wrongly. */
        private fun aspectMeantBy(spelled: String): Aspect? {
            if (!spelled.contains(PARAMETER_MARK)) return null
            val named = spelled.substringBefore(PARAMETER_MARK)
            return Aspect.entries.firstOrNull { it.page == named }
        }

        /** The parameter itself, with any aspect it named taken off — and left whole where it named none. */
        private fun parameterNameIn(spelled: String): String =
            if (aspectMeantBy(spelled) == null) spelled else spelled.substringAfter(PARAMETER_MARK)

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
    /**
     * Tags this entry takes back off whatever was **derived** for the same member — the way to say that a
     * rule reached something it should not have without giving up the rest of what it got right.
     */
    val dropped: Set<String> = emptySet(),
    /**
     * Whether this entry stands **instead of** the derivation rather than over it, for the member the
     * rules simply cannot read.
     */
    val replaces: Boolean = false,
) {
    /** This profile with [later] laid over it — a higher-priority pack retuning some of it. */
    fun mergedWith(later: PresetProfile): PresetProfile =
        PresetProfile(tags + later.tags, later.readiness ?: readiness, dropped + later.dropped, replaces || later.replaces)

    /**
     * This profile laid over what was **derived** for the same member (`notes/the-tag-layer.md` §5).
     *
     * Three levers, and the format carries all three from the start so slotting one in is never a
     * migration: an authored weight wins outright, [dropped] takes a derived tag back off, and [replaces]
     * ignores the derivation entirely.
     */
    fun over(derived: Map<String, Double>): PresetProfile {
        if (replaces) return this
        return copy(tags = (derived - dropped) + tags)
    }

    companion object {
        /** What a preset that says nothing about its readiness gets. */
        const val ORDINARY_READINESS = 1.0

        val CODEC: Codec<PresetProfile> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("tags", emptyMap())
                    .forGetter(PresetProfile::tags),
                Codec.DOUBLE.optionalFieldOf("readiness").forGetter { Optional.ofNullable(it.readiness) },
                Codec.STRING.listOf().optionalFieldOf("drop", emptyList())
                    .forGetter { it.dropped.toList() },
                Codec.BOOL.optionalFieldOf("replace", false).forGetter(PresetProfile::replaces),
            ).apply(instance) { tags, readiness, dropped, replaces ->
                PresetProfile(tags, readiness.orElse(null), dropped.toSet(), replaces)
            }
        }
    }
}

/** One aspect's worth of profiles, which is one `art/preset_tags/<aspect>.json`. */
data class PresetTags(private val byPreset: Map<String, PresetProfile>) {
    fun of(preset: Taggable): PresetProfile = byKey(preset.key)

    /** The same, where only the preset's name is known — which is all a file being loaded has. */
    fun byKey(key: String): PresetProfile = byPreset[key] ?: EMPTY_PROFILE

    /** Every preset key this table has something to say about — for validation, not for resolution. */
    val described: Set<String> get() = byPreset.keys

    /** Every tag anything here carries, which bounds what any word can meaningfully ask for. */
    val carried: Set<String> get() = byPreset.values.flatMap { it.tags.keys }.toSet()

    /**
     * This table laid over what was **derived** — every member some rule spoke to, plus every member the
     * pack authored, with the authored entry winning where both have something to say.
     */
    fun over(derived: Map<String, Map<String, Double>>): PresetTags {
        if (derived.isEmpty()) return this
        val members = derived.keys + byPreset.keys
        return PresetTags(
            members.associateWith { member ->
                byKey(member).over(derived[member].orEmpty())
            },
        )
    }

    companion object {
        val EMPTY_PROFILE = PresetProfile(emptyMap(), readiness = null)

        val CODEC: Codec<PresetTags> = Codec.unboundedMap(Codec.STRING, PresetProfile.CODEC)
            .xmap(::PresetTags, PresetTags::byPreset)
    }
}
