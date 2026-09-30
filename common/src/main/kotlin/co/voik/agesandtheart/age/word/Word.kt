package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.aspect.MATERIAL_PARAMETERS
import co.voik.agesandtheart.age.aspect.Materials
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.RANGED_PARAMETERS
import co.voik.agesandtheart.age.aspect.Taggable
import co.voik.agesandtheart.math.mix64
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import kotlin.math.roundToInt
import kotlin.random.Random
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.resources.Identifier
import java.util.Optional

/**
 * A page name as an [Aspect] — **file-private rather than `Word`'s own**, because [Claims] needs it too
 * and is declared above `Word`. It is the same reading `/age compose` and every tag table use.
 */
private val ASPECT_CODEC: Codec<Aspect> = Codec.STRING.comapFlatMap(
    { named ->
        Aspect.byPage(named)
            ?.let { DataResult.success(it) }
            ?: DataResult.error { "no part of the world is called '$named'" }
    },
    Aspect::page,
)

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
    val pools: List<Facets> = emptyList(),
) {
    val isEmpty: Boolean get() = sets.isEmpty() && pools.isEmpty()

    /** Everything this side could ever turn, whatever an Age's draw settles on. */
    val everything: Map<String, String> get() = sets + pools.flatMap { it.facets.entries }
        .associate { it.key to it.value }

    companion object {
        val NOTHING = Claims()

        val CODEC: Codec<Claims> = RecordCodecBuilder.create { instance ->
            // **Parameters only.** A lean has no offered half of its own — it cannot fail, so it has
            // nothing to yield, and a required lean and an offered one would behave identically. What is
            // said about a population is said once, in [Word.biases].
            instance.group(
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("sets", emptyMap())
                    .forGetter(Claims::sets),
                Facets.CODEC.listOf().optionalFieldOf("pools", emptyList()).forGetter(Claims::pools),
            ).apply(instance, ::Claims)
        }
    }
}

/**
 * **How precisely a word speaks, what a failure of it costs, and what writing it costs** — one of the
 * three the Art names, or a set of numbers a word states for itself.
 *
 * A value rather than an enum, and the reason is that a name cannot be enforced. Nothing stops an author
 * calling a word `evocative` and then having it choose four members outright and set eight parameters, so
 * the name was a label the word could contradict while still being priced by it. The three remain the
 * defaults and suit nearly every word; stating the numbers is how one that does not fit them is priced
 * honestly rather than mislabelled.
 *
 * One mechanism still serves all of them: every word scores every candidate, and these numbers decide how
 * hard that score bites. Nothing branches on *which* tier a word has — only on what it says.
 */
data class Tier(
    /** Fine inks per part of the world constrained, before [Word.versatility]. */
    val cost: Int,
    /**
     * How strongly a preset must answer a word for the word to keep it. Zero rather than absent where a
     * word only tilts, so "does this preset qualify?" needs no special case.
     */
    val threshold: Double,
    /**
     * How much a *failure* at this precision costs the Age. Separate from [cost]: cost is ink spent to say
     * something precisely, weight is what it means for that thing not to happen.
     */
    val weight: Int,
    /** Whether it narrows the candidates, as opposed to merely tilting the draw between them. */
    val narrows: Boolean,
    /**
     * How many times over each part of the world this word reaches counts towards its price —
     * [Word.versatility].
     *
     * One is the ordinary answer and gives world model §9's rule exactly: a page usable in four places
     * costs four times a page usable in one, because it is that much better a page to own. Zero prices the
     * page flat, for a word whose whole point is that it says the same small thing wherever it is laid;
     * the numbers between are how much a pack thinks reach is worth.
     */
    val versatilityMultiplier: Double,
) : Comparable<Tier> {

    /** What to call it: the name of the one it matches, or [CUSTOM] where a word states its own. */
    val key: String get() = NAMED.entries.firstOrNull { it.value == this }?.key ?: CUSTOM

    /**
     * Whether a word this precise leaves no room for a second answer beside its own (`Resolver.company`).
     *
     * Read off the threshold rather than off a name, which is the whole of what a value tier buys: a word
     * stating [EXACT]'s strictness in numbers behaves as one, and always did — it simply could not say so.
     */
    val leavesNoRoomForCompany: Boolean get() = narrows && threshold >= EXACT.threshold

    /**
     * Least precise first. **Narrowing outranks tilting whatever the numbers say**, since a word that only
     * tilts never removes a candidate and so can never be the one that wins a contention.
     */
    override fun compareTo(other: Tier): Int = BY_PRECISION.compare(this, other)

    companion object {
        /** Shifts the weights over whatever survived. Removes no freedom, so it can never fail. */
        val EVOCATIVE = Tier(cost = 1, threshold = 0.0, weight = 1, narrows = false, versatilityMultiplier = FLAT)

        /** Narrows the candidates to those that carry the tag at all. */
        val RESTRICTIVE = Tier(cost = 2, threshold = 0.3, weight = 2, narrows = true, versatilityMultiplier = BY_REACH)

        /** Pins: only a strong carrier will do. */
        val EXACT = Tier(cost = 4, threshold = 0.7, weight = 3, narrows = true, versatilityMultiplier = BY_REACH)

        /** The three the Art names, in the order they grow stricter — what a screen offers and a file spells. */
        val NAMED: Map<String, Tier> = linkedMapOf(
            "evocative" to EVOCATIVE,
            "restrictive" to RESTRICTIVE,
            "exact" to EXACT,
        )

        /** What a tier matching none of the three is called, there being nothing else to call it. */
        const val CUSTOM = "custom"

        /** The same price wherever the page is laid. */
        const val FLAT = 0.0

        /** World model §9's rule: a page usable in four places costs four times one usable in one. */
        const val BY_REACH = 1.0

        private val BY_PRECISION = compareBy<Tier>({ it.narrows }, { it.threshold }, { it.weight })

        /** The named tier called [key], or a complaint naming the three there are. */
        fun named(key: String): DataResult<Tier> = NAMED[key]?.let { DataResult.success(it) }
            ?: DataResult.error { "no specificity is called '$key' — try ${NAMED.keys.joinToString(", ")}" }

        private val WRITTEN_OUT: Codec<Tier> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("ink").forGetter(Tier::cost),
                Codec.DOUBLE.optionalFieldOf("threshold", EVOCATIVE.threshold).forGetter(Tier::threshold),
                Codec.INT.optionalFieldOf("weight", EVOCATIVE.weight).forGetter(Tier::weight),
                Codec.BOOL.optionalFieldOf("narrows", true).forGetter(Tier::narrows),
                Codec.DOUBLE.optionalFieldOf("versatility_multiplier", BY_REACH)
                    .forGetter(Tier::versatilityMultiplier),
            ).apply(instance, ::Tier)
        }

        /**
         * A name where one of the three fits, and the numbers where none does.
         *
         * Written back the same way round, so a word saying `"exact"` still says `"exact"` after a
         * round trip and only a word that really has its own numbers spells them out.
         */
        val CODEC: Codec<Tier> = Codec.either(Codec.STRING, WRITTEN_OUT).comapFlatMap(
            { either -> either.map({ named(it) }, { DataResult.success(it) }) },
            { tier -> if (tier.key == CUSTOM) Either.right(tier) else Either.left(tier.key) },
        )
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
     * **The one member this word chooses outright**, by its key — what makes a word referential rather
     * than evaluative (§8.1), and the step the whole pipeline short-circuits on.
     *
     * A word that chooses **does not search**: it arrives with its answer in hand, which is what keeps
     * §8.2 structural and is the one way to reach something curation left out of the pool entirely.
     *
     * **A name somebody chose**, and so not the same relation as [entryOf]: `spires` chooses
     * `spire_islands` and `inverted` chooses `inverse_caves`, mappings that exist nowhere else. A word
     * that simply *is* its content — every derived block word — says [entryOf] instead and repeats nothing.
     *
     * Keyed by aspect, like every other claim about a population, because an id says nothing about which
     * registry it belongs to: unkeyed, `minecraft:diamond_block` was a sea and a structure set and a biome
     * at once.
     */
    val chooses: Map<Aspect, String> = emptyMap(),
    /**
     * Members this word **puts into the pool by name** — the second step, and the only one that can widen
     * what an Age may draw between.
     *
     * The pool a vague word draws from is what `preset_tags/<aspect>.json` describes and nothing else
     * (§8.2), which keeps the resolver's work proportional to our curation rather than to the size of the
     * modpack. This is how a word reaches past that deliberately, for one member, in one sentence.
     *
     * **By name only, and that is not an omission**: everything carrying a tag is in the tag table, and
     * being in the tag table is what puts it in the pool — so admitting *by tag* could never add anything.
     */
    val admits: Map<Aspect, Set<String>> = emptyMap(),
    /**
     * Members this word **takes out of the pool** — by key, or by `#tag` for everything carrying one.
     *
     * The third step, and it beats [admits] rather than racing it: removals apply after additions, so a
     * member both admitted and excluded stays out however the sentence was laid. **Word order decides
     * nothing** (§3.5), which is what an ordering rule here would have quietly broken — two books with the
     * same pages in a different order would generate different worlds.
     */
    val excludes: Map<Aspect, Set<String>> = emptyMap(),
    /**
     * **A bar per tag** the pool is narrowed by — how strongly a member must carry it (world model §3,
     * "Retiring tier"). Spelled `">0.6"` in a file and held here as the signed number: positive is *at least*
     * that much, negative *at most* its size. At-least bars are alternatives, so a member qualifies by
     * clearing any one; at-most bars all bind, so it is out for breaking any one. See [Bars].
     *
     * The other half of the third step, and the one nearly every narrowing word is written with —
     * `riddled` keeps what is cavernous and drops the rest. A removal by complement, where [excludes]
     * removes what it names.
     *
     * **Keyed by aspect and never flat.** `stormy` means `gloomy`, `caverns` is also `gloomy`, and a word
     * about the sky pinned the *terrain* to caverns and discarded `floating` in silence — §4.4's worst
     * finding, and the reason a restriction has to say what part of the world it restricts.
     */
    val restricts: Map<Aspect, Map<String, Double>> = emptyMap(),
    /**
     * What this word **leans the draw toward or away from**, once the pool is settled — by member key, or
     * by `#tag` for everything carrying one. Signed: positive pulls, negative pushes.
     *
     * The last step, and the only one that never filters. It cannot fail and so cannot contend: leaning on
     * a choice already made moves nothing, which is how an offer yields without a rule saying so — `an
     * inferno Age. A drowned sea.` leaves water the only survivor and the lean toward lava is spent on it.
     * That is also why there is no offered half of this: a tilt has nothing to yield, so a required bias
     * and an offered one would behave identically.
     *
     * **[EVERYWHERE] is allowed here and nowhere else.** A word that does not narrow is written on the Age
     * rather than on a part of it, and leaning everything is what makes it evocative; `beautiful` leans the
     * whole world green and rules nothing out.
     */
    val biases: Map<Aspect, Map<String, Double>> = emptyMap(),
    /**
     * The same, leaned on **every part of the world at once** — spelled `biases: { all: … }`.
     *
     * Only an evocative word may have one, and for it the global reach is the whole point: it is written
     * on the Age rather than on a part of it, and `Constraint.aimedAt` is empty for one because nothing
     * consults it.
     */
    val leansEverywhere: Map<String, Double> = emptyMap(),
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
     * and a pool it draws some of.
     *
     * **The core is what makes it that word**, and the pool is what makes this one different from the last:
     * a drawn subset can never leave an Age un-scorched, because `sets` was never in the draw. Together
     * they are also the whole of what a word *can* do, which is a different question from what it does
     * here — see [canSet] against [setsDrawnAt].
     */
    val pools: List<Facets> = emptyList(),
    /**
     * The same three, **requested rather than required** — laid *under* the sentence instead of over it
     * (`the-world-model.md` §5).
     *
     * `scorching` "insists on the heat and offers a red sky, large suns, several of them", and until this
     * existed there was no way to write the second half: [pools] draws *which* facets fire, and then
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
     * `springs` mints from vanilla's water spring and `deposits` from an ore, so `ink springs` and `gold
     * block deposits` are that shape carrying a substance the game never puts there. The material comes from the
     * same clause, which is the one thing about it a writer says.
     *
     * **A minting word aims**, because it closes the clause the material qualifies — the same shape `sun`
     * has, and the reason a material may stand somewhere nothing is *made of* anything.
     */
    val mints: String? = null,
    /**
     * What the pattern [mints] names is made of when the clause says nothing — a block id, or a block
     * **tag** naming a small pool the Age's seed draws one from.
     *
     * `obelisks` used to mint nothing at all and put nothing in the ground: a writer laid a page, paid for
     * it, and got a world with no obelisks in it, which reads as the word being broken. What a pattern is
     * made of when nobody says is a fact about the pattern, so it is written beside it.
     *
     * A pool rather than one answer because a formation nobody described should not be the same rock every
     * time: a few plain stones with a rarer one among them is what makes an unasked-for obelisk worth
     * walking to. A tag is resolved where the registries are (`Features.wanted`), never here — the
     * resolver is a pure function of (vocabulary, sentence, seed) and holds no registry.
     */
    val unstated: String? = null,
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
     * The other half of [chooses], and a different fact rather than a shorthand for the same one.
     * Writing the key here would restate the word's own id 1168 times and say nothing; what is genuinely
     * unknown is the registry, because **an id cannot say which one it belongs to** —
     * `minecraft:diamond_block` and `minecraft:village_plains` are the same shape, and an open aspect's
     * `presetFor` parses rather than looks up. Without it every derived word meant a preset in *every*
     * open aspect: a block was a sea and a structure set and a biome at once, and `diamond_block
     * structures` parsed, resolved, charged ink and generated a structure set no registry holds.
     *
     * The aspects it answers for fall out of [Aspect.presetsAreEntriesOf] rather than being listed.
     *
     * Not in the codec: a pack author writes [chooses], which says the same thing about one entry in
     * the language the file already speaks. This is what a *derivation* over a whole registry says instead.
     */
    val entryOf: ResourceKey<out Registry<*>>? = null,
    /**
     * **How likely each part of the world is to feel this word when it is laid bare** — on the Age itself,
     * aimed at nothing. A part not listed feels it as it always has; a part listed at a quarter feels it in
     * one Age in four, and at nought only when the word is aimed there.
     *
     * `polar` sets a cold temperature and a polar path, and lists the sun and the moon at a quarter: `polar
     * sun` always sets the path, `polar climate` always the cold, and a polar Age is always cold and now and
     * then has a polar sun. One word, whose reach laid bare is a gamble and aimed is certain. Rolled per Age
     * off the seed ([Resolver.resolve]), so an Age rebuilds identically. **It never moves the price**: ink is
     * a fact about the word written, the same wherever it is laid (Jonah, 2026-09-30).
     */
    val unaimed: Map<Aspect, Double> = emptyMap(),
) {
    /** What a writer says to use it. */
    val name: String get() = id.path

    /**
     * This word with nothing it does to [missed] — what a bare word is in an Age whose roll missed those
     * parts (see [unaimed]).
     *
     * A parameter goes where it names a missed part, or where it names none and every part of this word
     * owning it was missed. One owned by a missed part and a kept one stays, and a word that narrows is kept
     * off the missed part by its scope, which [Resolver.resolve] shrinks beside this.
     */
    fun withoutAspects(missed: Set<Aspect>): Word {
        if (missed.isEmpty()) return this
        val kept = aspects - missed
        fun stillLands(key: String): Boolean {
            val named = aspectNamedBy(key)
            if (named != null) return named !in missed
            return missed.none { landsOn(key, it) } || kept.any { landsOn(key, it) }
        }
        fun keptOf(pool: Facets): Facets? {
            val offers = pool.offers.map { offer -> offer.filterKeys(::stillLands) }.filter { it.isNotEmpty() }
            return if (offers.isEmpty()) null else pool.copy(offers = offers)
        }
        return copy(
            aspects = kept,
            chooses = chooses - missed,
            admits = admits - missed,
            excludes = excludes - missed,
            restricts = restricts - missed,
            biases = biases - missed,
            sets = sets.filterKeys(::stillLands),
            pools = pools.mapNotNull(::keptOf),
            requests = Claims(requests.sets.filterKeys(::stillLands), requests.pools.mapNotNull(::keptOf)),
        )
    }

    /**
     * Every registry this word names an entry of: its own ([entryOf]), and each open aspect it [chooses] in.
     * A word merged from two derivations — `blue_ice`, a block and a placed feature both — gets both, and an
     * authored word naming nothing gets none.
     */
    val referentRegistries: Set<ResourceKey<out Registry<*>>>
        get() = setOfNotNull(entryOf) + chooses.keys.mapNotNull { it.presetsAreEntriesOf }

    /**
     * Everything this word could ever choose — its core and its whole pool.
     *
     * **The capability question, and not the same as [setsDrawnAt].** Whether a word belongs in an aspect,
     * whether it steers anything at all, and whether the world can back it are all questions about what it
     * *means*, which a draw must not move: a word charged as unbacked because this Age's draw happened to
     * miss the parameter that would have landed is a writer paying for a coin they did not toss.
     */
    val canSet: Map<String, String>
        get() = everySet + bare(required.everything) + bare(requests.everything)

    /** The demanded half, as one record — the shape [requests] already has, for the code that asks both. */
    val required: Claims get() = Claims(sets, pools)

    /**
     * Whether anything here names the aspect it is meant for — `sun.absent` rather than `absent`.
     *
     * Held rather than asked, because [setsIn] is called for every word in a sentence against every
     * aspect it might land in, and the answer is no for nearly every word ever written. Where it is no,
     * that method hands back the map it already has and allocates nothing.
     */
    private val someParameterNamesItsAspect: Boolean =
        (sets.keys + pools.flatMap { it.facets.keys } + requests.everything.keys).any { it.contains(PARAMETER_MARK) }

    /**
     * What this word sets on [aspect] — what it sets everywhere, and what it sets **only** here.
     *
     * The same shape as [restrictsIn], and for the same reason an aspect keys [restricts]: an aspect is the unit
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
            val meant = aspectNamedBy(spelled)
            if (meant != null && meant != aspect) null else parameterIn(spelled) to value
        }.toMap()

    /** Everything it could set anywhere, under plain names — the capability question, never the landing one. */
    val everySet: Map<String, String> get() = bare(sets)

    private fun bare(parameters: Map<String, String>): Map<String, String> =
        if (!someParameterNamesItsAspect) parameters else parameters.mapKeys { parameterIn(it.key) }

    /**
     * The aspects a key names that no aspect answers to — a typo, reported at load rather than ignored.
     *
     * A key nothing can read is inert, which is §3.3's silent drop wearing a different hat: the word costs
     * a page, sets nothing, and says so nowhere.
     */
    val unreadableParameters: List<String>
        get() = (required.everything.keys + requests.everything.keys)
            .filter { it.contains(PARAMETER_MARK) && aspectNamedBy(it) == null }

    /**
     * What it actually chooses in the Age [draw] belongs to — the core, and what each pool drew.
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
     * **What this word decides rather than suggests** — the fields as its file spells them, and for `sets`
     * and `pools` the parameters.
     *
     * A tier that does not narrow is one whose words only nudge: they tilt a draw ([biases]), offer what
     * nothing else demanded ([requests]), put members in a pool ([admits]) that may or may not be picked,
     * and bend a range towards where they would like it, which is all the resolver lets such a word do to
     * one. Anything here settles part of the world outright, which is the narrowing tiers' business
     * (Jonah, 2026-09-29).
     */
    val decidesOutright: List<String> get() = buildList {
        if (chooses.isNotEmpty()) add("chooses")
        if (excludes.isNotEmpty()) add("excludes")
        if (restricts.isNotEmpty()) add("restricts")
        if (template != null) add("template")
        if (mints != null) add("mints")
        val required = sets.keys + pools.flatMap { it.facets.keys }
        addAll(required.map(::parameterIn).filterNot { it in RANGED_PARAMETERS }.distinct().sorted())
    }

    /**
     * Whether anything about this word is left to the Age — a pool to draw from, or a value with
     * alternatives in it. A word with neither is the same word in every world it appears in.
     */
    val varies: Boolean
        get() = listOf(required, requests).any { claims ->
            claims.pools.any { it.offers.isNotEmpty() && it.draws.most > 0 } ||
                claims.everything.values.any { ALTERNATIVE in it }
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
        val seed = mix64(draw xor id.hashCode().toLong() xor parameter.hashCode().toLong())
        return offered[Random(seed).nextInt(offered.size)]
    }

    /**
     * The core, and what each pool drew.
     *
     * **A generator per pool, salted by where it sits**, so what one pool takes can never decide what
     * another does — two pools of the same size sharing a generator would fire the same positions for
     * ever, which is the fault the required and requested halves were already salted apart for.
     */
    private fun facetsDrawnAt(claims: Claims, draw: Long, salt: Long): Map<String, String> {
        var settled = claims.sets
        for ((at, pool) in claims.pools.withIndex()) {
            val seed = mix64(draw xor id.hashCode().toLong() xor salt xor at.toLong().inv())
            settled = settled + pool.drawnWith(Random(seed))
        }
        return settled
    }

    /**
     * The one member this word chooses in [aspect], or null where it chooses none — **step one, and the
     * only one that ends the pipeline.**
     *
     * Two ways to choose: a name somebody chose for a member ([chooses]), or being the registry entry
     * oneself ([entryOf]). The aspect has to be checked either way, because an open aspect parses any
     * well-formed id into its own kind of preset and would otherwise take a block for a biome.
     */
    fun choiceIn(aspect: Aspect): Taggable? {
        val itself = id.toString().takeIf { entryOf != null && aspect.presetsAreEntriesOf == entryOf }
        return (chooses[aspect] ?: itself)?.let(aspect::presetFor)
    }

    /** Step two: the members this word puts into [aspect]'s pool by name. */
    fun admitsIn(aspect: Aspect): Set<String> = admits[aspect].orEmpty()

    /**
     * Step three: whether this word takes [preset] out of the pool — by its key, or by a `#tag` it carries.
     *
     * Applied after [admitsIn], so a member both admitted and excluded stays out. Nothing here depends on
     * the order the words were laid in (§3.5).
     */
    fun excludes(preset: Taggable, tags: Map<String, Double>): Boolean =
        excludes[preset.aspect].orEmpty().any { struck ->
            if (struck.startsWith(TAG_MARK)) tags.containsKey(struck.drop(1)) else struck == preset.key
        }

    /** Whether this word makes any claim at all about [aspect]'s members, of any of the five kinds. */
    fun saysSomethingOf(aspect: Aspect): Boolean =
        choiceIn(aspect) != null || admits[aspect].orEmpty().isNotEmpty() ||
            excludes[aspect].orEmpty().isNotEmpty() || restrictsIn(aspect).isNotEmpty() ||
            biases[aspect].orEmpty().isNotEmpty() || leansEverywhere.isNotEmpty()

    /** Step three's other half: the tags [aspect]'s pool is narrowed to, or empty where it is not. */
    fun restrictsIn(aspect: Aspect): Map<String, Double> = restricts[aspect].orEmpty()

    /**
     * Whether this word says nothing except which part of the world it is about — an **aiming page**
     * (§4.3.1), whose whole job is to open a section and scope what follows it.
     *
     * Recognised by shape rather than by a flag, because that shape *is* the definition: a word claiming
     * nothing about a population and nothing about a property has only its aspects to contribute.
     */
    val aims: Boolean get() = saysNothingOfAPopulation && canSet.isEmpty() &&
        template == null && aspects.isNotEmpty()

    private val saysNothingOfAPopulation: Boolean
        get() = chooses.isEmpty() && entryOf == null && admits.isEmpty() && excludes.isEmpty() &&
            restricts.isEmpty() && biases.isEmpty() && leansEverywhere.isEmpty()

    /**
     * Every tag this word has an opinion about anywhere, which is the honest answer to "could it want X".
     *
     * Both halves of the pipeline that take one — what it narrows to, and what it leans by — since a
     * misspelled tag is as inert in a lean as in a restriction.
     */
    val everyTagAsked: Map<String, Double>
        get() = restricts.values.fold(emptyMap<String, Double>()) { standing, next -> standing + next } +
            (biases.values + listOf(leansEverywhere)).flatMap { it.entries }
                .filter { it.key.startsWith(TAG_MARK) }.associate { it.key.drop(1) to it.value }

    /**
     * Whether this word has anything to say about *which* member fills [aspect], as opposed to how that
     * member is steered — the three steps that can remove a candidate, and never the one that cannot.
     *
     * A word that only leans must not be treated as narrowing: an empty carrier set is how the resolver
     * recognises a word the world cannot satisfy (§3.3), and a lean can never empty one.
     */
    fun constrainsPresetsIn(aspect: Aspect): Boolean =
        choiceIn(aspect) != null || excludes[aspect].orEmpty().isNotEmpty() || restrictsIn(aspect).isNotEmpty()

    /**
     * The tags this word narrows on, which are the ones that must have a carrier somewhere (§3.3).
     *
     * Every aspect's, unioned. This is asked by opposition-finding, where the question is whether two
     * words can ever be at odds, and a word that wants `bright` only overhead still wants it.
     */
    val wanted: Set<String> get() = restricts.values.flatMap { tags ->
        tags.filterValues { it > 0.0 }.keys
    }.toSet()

    /**
     * The tags this word strikes out or leans away from — [wanted]'s mirror, and half of what lets two
     * words be found to disagree with no antonym table involved (`Vocabulary.disagreement`).
     */
    val unwanted: Set<String> get() = (
        excludes.values.flatMap { struck -> struck.filter { it.startsWith(TAG_MARK) }.map { it.drop(1) } } +
            restricts.values.flatMap { it.filterValues { weight -> weight < 0.0 }.keys }
        ).toSet()

    /**
     * Every tag this word merely **leans** by — deliberately apart from [wanted].
     *
     * The coverage checks want these: a misspelled tag in a lean is as inert as one in a restriction. What
     * must *not* see them is `Vocabulary.disagreement`, which reads [wanted] to say two words cannot both
     * stand — a lean yields rather than argues, so an inferno leaning the sea toward `molten` does not
     * contradict a writer who wrote `drowned`; it simply is spent.
     */
    val leanedTags: Set<String>
        get() = (biases.values + listOf(leansEverywhere)).flatMap { it.keys }
            .filter { it.startsWith(TAG_MARK) }.map { it.drop(1) }.toSet()

    /**
     * **Step four**: how far this word leans the draw toward [preset] — by its key, or by a `#tag` it
     * carries, summed because two leanings on one member are two opinions and not a choice between them.
     *
     * A tilt and never a filter, which is the whole of how a lean yields: a restriction narrows the aspect
     * to what it will keep, and leaning on a choice already made moves nothing.
     */
    fun biasOn(preset: Taggable, tags: Map<String, Double>): Double =
        leaning(leansEverywhere, preset.key, tags) + leaning(biases[preset.aspect].orEmpty(), preset.key, tags)

    private fun leaning(by: Map<String, Double>, key: String, tags: Map<String, Double>): Double =
        by.entries.sumOf { (named, weight) ->
            when {
                named == key -> weight
                named.startsWith(TAG_MARK) -> weight * (tags[named.drop(1)] ?: 0.0)
                else -> 0.0
            }
        }

    /**
     * The block this word names, or null where it names none — every derived block word sets one, and
     * no authored word does. An authored word may *ask* for a material by tag (`#sandy`), and that names
     * no block: it is a word about what the ground is like, not a page that is a block.
     *
     * Asked of [Parameter.material] rather than of a parameter by name. Two readers wanted this and both
     * looked for `Terrain.STONE` by name: `Grammar` to decide a page is a material at all, and `Resolver`
     * to find what a minted pattern is made of. One aspect's parameter was standing in for "a block".
     */
    val material: String? get() = sets.entries
        .firstOrNull { it.key in MATERIAL_PARAMETERS && !Materials.isQuery(it.value) }?.value

    /**
     * The size this word asks for, or null where it says nothing about size — read the same way
     * [material] is read, off what the word sets rather than off a name we would have to keep in step.
     *
     * A span rather than a number, since `enormous` is `0.7..1.0`; the middle of it is what a clause takes.
     * The seed draws within a span where a *parameter* is filled, and has no business deciding how big a
     * thing a writer named outright is — two obelisks in one book should not differ because one was read
     * first.
     */
    val sizeAsked: Double? get() = sets[SIZE_PARAMETER]
        ?.let(Span::read)
        ?.let { (it.least + it.most) / 2.0 }

    /**
     * The same word with its size taken away and everything else it says left standing.
     *
     * **For a size a minting clause spent** (`Resolver.resolve`). Discarding the whole claim instead was
     * the fault this exists to prevent: `colossal` also restricts the landmass to `monumental`, and `rich`
     * admits ores and biases three tags besides — so spending the size by dropping the word silently threw
     * all of that away, and a writer paid for a page that then meant nothing at all.
     */
    fun withoutItsSize(): Word = if (SIZE_PARAMETER in sets) copy(sets = sets - SIZE_PARAMETER) else this

    /**
     * How deep in the column this word asks a thing to sit, or null where it says nothing — read as
     * [sizeAsked] is, and for the same reason: `shallow veins` says where the veins are, and read as a word
     * about the features aspect it would also have moved every ore in the Age.
     */
    val heightAsked: Double? get() = everySet[HEIGHT_PARAMETER]
        ?.let(Span::read)
        ?.let { (it.least + it.most) / 2.0 }

    /** The same word with its height taken away — [withoutItsSize]'s twin, for a height a minting spent. */
    fun withoutItsHeight(): Word {
        val kept = sets.filterKeys { parameterIn(it) != HEIGHT_PARAMETER }
        return if (kept.size == sets.size) this else copy(sets = kept)
    }

    /**
     * **How many places this page may be laid** — the second half of what it costs (world model §9).
     *
     * An evocative word is one: it may only ever be written on the Age itself, which is what makes it the
     * cheapest thing in the language. A narrowing word is at home in as many parts of the world as it
     * declares, and one that landed nowhere is priced as though it landed somewhere — being empty on
     * purpose so `DerivedAspectsCheck` can refuse it, not so it can be free.
     *
     * **How much reaching further is worth is the word's own to say** ([Tier.versatilityMultiplier]). It
     * used to be read off `narrows`, which made the two inseparable: a word that narrows and wants a flat
     * price had no way to say so.
     *
     * **Never below one**, so no page is ever free. That floor is what a multiplier of zero means — the
     * base cost, flat — and it is what keeps the beginner's sentence the cheapest thing in the language
     * rather than the free one.
     */
    val versatility: Double get() {
        val reach = aspects.size.coerceAtLeast(ONE_PLACE)
        return (reach * tier.versatilityMultiplier).coerceAtLeast(ONE_PLACE.toDouble())
    }

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
    val price: Int get() = (tier.cost * versatility).roundToInt().coerceAtLeast(0)

    /**
     * How well [tags] answers what this word narrowed [aspect] to: **how far past its bar the member's best
     * wanted tag lands**, one being exactly at the bar. The strongest single term rather than a sum, because
     * a word restricting on two tags asks for either.
     *
     * Scaled by the tier's threshold for now, which makes it exactly the weight × carried it replaces for
     * every converted word; the scale goes with the tier.
     */
    fun pullIn(aspect: Aspect, tags: Map<String, Double>): Double =
        restrictsIn(aspect).filterValues(Bars::isAtLeast)
            .maxOfOrNull { (tag, bar) -> (tags[tag] ?: 0.0) / bar }
            ?.let { it * tier.threshold } ?: 0.0

    /** Whether [tags] breaks none of the at-most bars this word set on [aspect]. Read strictly, always. */
    fun withinItsLimitsIn(aspect: Aspect, tags: Map<String, Double>): Boolean =
        restrictsIn(aspect).filterValues(Bars::isAtMost).all { (tag, bar) -> (tags[tag] ?: 0.0) <= -bar }

    /**
     * How strongly this word claims [preset] — **absolute where it chose it**, else how well the member
     * answers what the word restricted to.
     *
     * Without the first half a derived word would be scored on tags it does not have, so "creosote oil
     * beside a lava sea" gave creosote the *smaller* share.
     */
    fun claimOn(preset: Taggable, tags: Map<String, Double>): Double =
        if (choiceIn(preset.aspect)?.key == preset.key) CHOSEN_OUTRIGHT else pullIn(preset.aspect, tags)

    /**
     * **Whether [preset] survives this word's pipeline** — the third step, asked of one member.
     *
     * Excluded members are out however they got in. What is left has to clear [strictness] on whatever the
     * word restricted to; a word that restricted nothing removes nobody, since a lean is never a filter.
     *
     * **[share] is how much of each at-least bar must be cleared, and defaults to all of it, which is the
     * strict reading** — every member that answers this word whatever the Age. An Age may be more generous
     * and pass its own lower share (`Resolver.strictnessOf`); nothing may be stricter, so what this answers
     * by default is what a word is *guaranteed* to reach, which is what every check and every screen wants
     * of it. At-most bars are never eased: a limit is an instruction, not a preference.
     */
    fun acceptsOn(preset: Taggable, tags: Map<String, Double>, share: Double = Bars.STRICT): Boolean {
        // **A choice answers for the whole aspect.** The pipeline ends there, so nothing else this word
        // says about that part of the world is asked — and every other member is out, not merely unranked.
        choiceIn(preset.aspect)?.let { return it.key == preset.key }
        if (excludes(preset, tags)) return false
        val bars = restrictsIn(preset.aspect)
        if (bars.isEmpty()) return true
        if (!withinItsLimitsIn(preset.aspect, tags)) return false
        val wanted = bars.filterValues(Bars::isAtLeast)
        if (wanted.isEmpty()) return true
        fun clears(tag: String, bar: Double): Boolean {
            val carried = tags[tag] ?: 0.0
            return carried > 0.0 && carried >= bar * share
        }
        return wanted.any { (tag, bar) -> clears(tag, bar) }
    }

    override fun toString(): String = name

    companion object {
        /** The parameter a size word sets — `Features.SIZE`'s name, and every other axis that shares it. */
        private const val SIZE_PARAMETER = "size"
        private const val HEIGHT_PARAMETER = "height"

        /** What choosing a member outright is worth, against a tag weight, which never exceeds one. */
        private const val CHOSEN_OUTRIGHT = 1.0

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
                    val meantBy = aspectNamedBy(spelled)
                    (meantBy == null || meantBy == aspect) && aspect.ownsParameterNamed(parameterIn(spelled))
                }
            }
            // **Minting is the features' own.** `Resolver.mintedFeatures` is the only reader of `mints`
            // and it makes a placed feature out of the pattern, so a word that mints is a word about
            // them — `lakes`, `springs` and `deposits` claim nothing else at all.
            val mints = if (minted == null) emptySet() else setOf(Aspect.FEATURES)
            return (steered + meant + weighted + mints).toSet()
        }

        /** The `biases` key that means every part of the world at once — see [leansEverywhere]. */
        const val EVERYWHERE = "all"

        /** What marks a **tag** where a member's own key would otherwise stand — `#watery`. */
        const val TAG_MARK = "#"

        /**
         * `biases`, keyed by aspect page or by [EVERYWHERE].
         *
         * Validated on the way in so a mistyped page is a word that fails to load and is reported, rather
         * than a query that quietly asks nothing of nowhere.
         */
        private val LEANINGS_CODEC: Codec<Map<String, Map<String, Double>>> =
            Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Codec.DOUBLE))
                .comapFlatMap(
                    { raw ->
                        val strange = raw.keys.filterNot { said ->
                            said == EVERYWHERE || Aspect.byPage(said) != null
                        }
                        if (strange.isEmpty()) {
                            com.mojang.serialization.DataResult.success(raw)
                        } else {
                            com.mojang.serialization.DataResult.error {
                                "biases names ${strange.joinToString(" ")}, which is no aspect page nor '$EVERYWHERE'"
                            }
                        }
                    },
                    { it },
                )

        /** A word as its file says it, the id coming from where the file *is*, like every vanilla registry. */
        fun mapCodec(id: Identifier): MapCodec<Word> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Tier.CODEC.fieldOf("tier").forGetter(Word::tier),
                Codec.unboundedMap(ASPECT_CODEC, Codec.STRING).optionalFieldOf("chooses", emptyMap())
                    .forGetter(Word::chooses),
                Codec.unboundedMap(ASPECT_CODEC, Codec.STRING.listOf())
                    .optionalFieldOf("admits", emptyMap())
                    .forGetter { word -> word.admits.mapValues { it.value.toList() } },
                Codec.unboundedMap(ASPECT_CODEC, Codec.STRING.listOf())
                    .optionalFieldOf("excludes", emptyMap())
                    .forGetter { word -> word.excludes.mapValues { it.value.toList() } },
                Codec.unboundedMap(ASPECT_CODEC, Codec.unboundedMap(Codec.STRING, Bars.CODEC))
                    .optionalFieldOf("restricts", emptyMap()).forGetter(Word::restricts),
                // **One field for both**, keyed by aspect page or by `all` — a key that names neither is a
                // parse error rather than a silently dropped lean.
                LEANINGS_CODEC.optionalFieldOf("biases", emptyMap()).forGetter { word ->
                    word.biases.mapKeys { it.key.page } +
                        (if (word.leansEverywhere.isEmpty()) emptyMap() else mapOf(EVERYWHERE to word.leansEverywhere))
                },
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("sets", emptyMap())
                    .forGetter(Word::sets),
                Facets.CODEC.listOf().optionalFieldOf("pools", emptyList()).forGetter(Word::pools),
                Claims.CODEC.optionalFieldOf("requests", Claims.NOTHING).forGetter(Word::requests),
                Codec.STRING.optionalFieldOf("template").forGetter { Optional.ofNullable(it.template) },
                Codec.STRING.optionalFieldOf("mints").forGetter { Optional.ofNullable(it.mints) },
                Codec.BOOL.optionalFieldOf("mints_something_that_flows", false)
                    .forGetter(Word::mintsSomethingThatFlows),
                Codec.STRING.optionalFieldOf("unstated").forGetter { Optional.ofNullable(it.unstated) },
                Codec.unboundedMap(ASPECT_CODEC, Codec.doubleRange(0.0, 1.0)).optionalFieldOf("unaimed", emptyMap())
                    .forGetter(Word::unaimed),
            ).apply(instance) {
                tier, chooses, admits, excludes, restricts, leanings, sets, pools, requests, template,
                mints, flows, unstated, unaimed,
                ->
                val everywhere = leanings[EVERYWHERE].orEmpty()
                val leaned = leanings.filterKeys { it != EVERYWHERE }
                    .mapNotNull { (page, tags) -> Aspect.byPage(page)?.to(tags) }
                    .toMap()
                // **Both halves widen the reach.** A word that only *offers* to redden a sun is still a
                // word about the sun, and one that reached nowhere would have its offer skipped in the
                // only aspect it meant it — which is the silent drop §3.3 exists to forbid.
                val steers = sets + Claims(sets, pools).everything + requests.everything
                // `all` deliberately adds nothing: a global tilt is not a claim on any one part.
                val aimed = admits.keys + excludes.keys + restricts.keys + leaned.keys
                val reaches = reaching(steers, chooses.keys, aimed, mints.orElse(null))
                Word(
                    id, tier, reaches, chooses, admits.mapValues { it.value.toSet() },
                    excludes.mapValues { it.value.toSet() }, restricts, leaned, everywhere,
                    sets, pools, requests, template.orElse(null), mints.orElse(null),
                    unstated.orElse(null), flows, unaimed = unaimed,
                )
            }
        }
    }
}

/** The mark between an aspect and the parameter it qualifies — `sun.colour`. */
private const val PARAMETER_MARK = '.'

/**
 * The aspect a parameter key names, or null where it names none — including where it names one wrongly.
 *
 * `sun.colour` is the sun's alone; a plain `colour` belongs to every aspect that owns one, which is what
 * lets one word paint eight of them.
 */
fun aspectNamedBy(spelled: String): Aspect? {
    if (!spelled.contains(PARAMETER_MARK)) return null
    val named = spelled.substringBefore(PARAMETER_MARK)
    return Aspect.byPage(named)
}

/** The parameter itself, with any aspect it named taken off — and left whole where it named none. */
fun parameterIn(spelled: String): String =
    if (aspectNamedBy(spelled) == null) spelled else spelled.substringAfter(PARAMETER_MARK)

/** Whether a key spelled [spelled] can land on [aspect] — it named this one, or it named none and this owns it. */
fun landsOn(spelled: String, aspect: Aspect): Boolean {
    val named = aspectNamedBy(spelled)
    if (named != null) return named == aspect
    return aspect.ownsParameterNamed(spelled)
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
    /**
     * Whether this member is in the world **anyway**, so that naming it asks for *more* of it rather than
     * for it at all — the flag the mention bump turns on (`Resolver.claimForMember`, design §3.3).
     *
     * **True by default, which is what keeps every entry written before this correct.** A biome, a creature,
     * a vanilla feature a biome already grows: all present unless something strikes them, so naming one at
     * the ordinary share would be a page read, charged for, and worth nothing. The bump is what makes the
     * word mean something.
     *
     * **False is for a member that only exists because a word asked** — a volcano, a magma chamber. It goes
     * in at the ordinary share and is moved from there by the other words in the sentence, which is what a
     * quantifier is for (Jonah, 2026-09-10: *"if it otherwise would not be in the pool, and the word adds
     * it, it goes in at the default weight, which other words may then modify"*).
     *
     * **Why this is not `alreadyInThePool`, which is the subtlety the design turned on.** That predicate
     * means *listed in `art/preset_tags/<aspect>.json`*. For biomes it happens to coincide with being
     * present anyway; for **features** it does not, because `Features.placedIn` starts from what each biome
     * already carries — so a feature is present-anyway when a biome grows it, which the tag table knows
     * nothing about. Tagging the volcanic features `molten` so `volcanic` could query them would have put
     * them "in the pool" and handed the bump straight back.
     *
     * Per member rather than per aspect, so a vanilla feature that really does grow anyway keeps its bump
     * while ours do not. **Revisit if flagging each one becomes a chore** as the pack gains custom features
     * (Jonah).
     */
    val presentAnyway: Boolean = true,
    /**
     * Whether a vague word, or the draw for an aspect nobody spoke to, may land on this member. False keeps
     * it for words that name it outright — how a rare thing stays out of cheap writing without losing its
     * word or its tags.
     */
    val availableToBroadWords: Boolean = true,
) {
    /** This profile with [later] laid over it — a higher-priority pack retuning some of it. */
    fun mergedWith(later: PresetProfile): PresetProfile =
        PresetProfile(
            tags + later.tags,
            later.readiness ?: readiness,
            dropped + later.dropped,
            replaces || later.replaces,
            // A later pack saying nothing about this leaves the earlier answer standing, as `readiness` does.
            later.presentAnyway && presentAnyway,
            later.availableToBroadWords && availableToBroadWords,
        )

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
                Codec.BOOL.optionalFieldOf("present_anyway", true).forGetter(PresetProfile::presentAnyway),
                Codec.BOOL.optionalFieldOf("available_to_broad_words", true)
                    .forGetter(PresetProfile::availableToBroadWords),
            ).apply(instance) { tags, readiness, dropped, replaces, presentAnyway, availableToBroadWords ->
                PresetProfile(
                    tags, readiness.orElse(null), dropped.toSet(), replaces, presentAnyway, availableToBroadWords,
                )
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

    /** This table with a higher-priority pack's [later] laid on it, preset by preset. */
    fun stackedWith(later: PresetTags): PresetTags = PresetTags(
        (described + later.described).associateWith { key ->
            val mine = byPreset[key]
            val theirs = later.byPreset[key]
            when {
                mine == null -> later.byKey(key)
                theirs == null -> mine
                else -> mine.mergedWith(theirs)
            }
        },
    )

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

/**
 * **A bar on a tag, as a filter states it** — `">0.6"`, at least 0.6, or `"<0.2"`, at most 0.2 — held as one
 * signed number, positive for at least and negative for at most. The sign is this object's to read and
 * nobody else's; a bar is never zero, since at most nothing is an [Word.excludes] and at least nothing
 * asks for nothing.
 */
object Bars {
    /** The whole of every at-least bar — the strict reading, what a word reaches in every Age. */
    const val STRICT = 1.0

    fun isAtLeast(bar: Double): Boolean = bar > 0.0

    fun isAtMost(bar: Double): Boolean = bar < 0.0

    /** How the bar is spelled in a word file. */
    fun spell(bar: Double): String = if (isAtLeast(bar)) "$AT_LEAST$bar" else "$AT_MOST${-bar}"

    /** The bar [spelled] states, or a complaint saying how a bar is spelled. */
    fun read(spelled: String): DataResult<Double> {
        val direction = spelled.firstOrNull()
        val level = spelled.drop(1).toDoubleOrNull()
        val isSpelledRight = (direction == AT_LEAST || direction == AT_MOST) && level != null && level > 0.0 &&
            level <= 1.0
        if (!isSpelledRight || level == null) {
            return DataResult.error { "'$spelled' is no bar: write '>0.6' for at least 0.6, '<0.2' for at most 0.2" }
        }
        return DataResult.success(if (direction == AT_LEAST) level else -level)
    }

    val CODEC: Codec<Double> = Codec.STRING.comapFlatMap(::read, ::spell)

    private const val AT_LEAST = '>'
    private const val AT_MOST = '<'
}
