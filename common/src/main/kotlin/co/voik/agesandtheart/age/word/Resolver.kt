package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.Flaw
import co.voik.agesandtheart.age.Instability
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Parameter
import co.voik.agesandtheart.age.aspect.Share
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.AspectPreset
import co.voik.agesandtheart.age.word.grammar.Constraint
import co.voik.agesandtheart.age.word.grammar.Scope
import co.voik.agesandtheart.age.word.grammar.Sentence
import net.minecraft.world.level.levelgen.XoroshiroRandomSource

/**
 * What a sentence turned into: the world it describes, what it cost to say, and where it argued with
 * itself.
 *
 * The words are kept alongside as **provenance** (§4.6) — what the book said, as opposed to what the Age
 * is. Only the composition and the instability are ever consulted again; the words are for a reader.
 */
data class Resolution(
    val composition: AgeComposition,
    val instability: Instability,
    /** Fine inks: precision tier × aspects constrained, summed over the sentence (§4.4). */
    val cost: Int,
    val words: List<Word>,
    /** Pages the Art could not read. Vagueness, never instability (design §4.3) — carried so a writer sees it. */
    val dropped: List<String> = emptyList(),
) {
    val sentence: List<String> get() = words.map { it.name }
}

/**
 * Words in, a world out — the heart of the Art, and the thing the whole design was least certain of.
 *
 * The mechanism is one paragraph long, which is the finding the Phase 3 spike was built to establish:
 * every word scores every preset in the aspects it may fill, precise words *narrow* the candidates, vague
 * words *tilt* the draw between whatever survived, and the seed picks. There is no per-tier code path and
 * no geometry anywhere.
 *
 * Four things it does that are less obvious, each of them a spike finding that took a session to learn:
 *
 * 1. **Every word is confined to the aspects it is about** ([Word.aspects], §4.4). Letting a word constrain
 *    every aspect its tags happen to touch is catastrophic rather than untidy: `stormy` is a word about the
 *    sky, and unscoped it pinned the *terrain* to caverns and threw `floating` away in silence.
 * 2. **Unsatisfiability is read out of the tag data, never out of the antonym table** (§3.3). The table
 *    explains and prices tension it recognises; what actually breaks an Age is two words with no preset
 *    between them, and that is discovered here by intersecting carrier sets.
 * 3. **A positional aspect divides rather than arbitrating** (§3.4). Two words that no single preset can
 *    satisfy get a territory each, which is why "lush barren" yields both biome families instead of one
 *    of them plus a silence. A aspect divides **only** to absorb a real disagreement: seeing two terrains
 *    in an Age therefore *means* something, and Jonah's call on it keeps the world diagnosable.
 * 4. **The writer's word order never decides anything** (§3.5, which rejected page order as an override).
 *    Where precision cannot separate two words the seed does, so `verdant arid` and `arid verdant` are
 *    the same sentence and neither is safe.
 *
 * Resolution is a pure function of (vocabulary, words, seed). Everything downstream assumes it: an Age is
 * rebuilt from its recipe on every open, and a recipe that resolved differently twice would be a world
 * that changed under its inhabitants.
 */
object Resolver {
    /**
     * How many ways one aspect may divide.
     *
     * Region maps handle any number of members, so this is a limit on *legibility* rather than on
     * machinery: three terrains in one Age already asks a lot of a reader, and past that a sentence is
     * soup. Words that do not fit are displaced and charged, never dropped in silence.
     */
    private const val MOST_TERRITORIES = 3

    // A floor under every candidate so an evocative word tilts the draw rather than deciding it, which is
    // the whole distinction between "shifts weights" and "narrows the set".
    private const val BASE_WEIGHT = 0.35

    // No candidate's chance ever reaches zero: a word that pushes hard against something must not be able
    // to silently eliminate it, or evocative words would start narrowing.
    private const val FAINTEST_CHANCE = 0.02

    // How well liked a preset must be, against the best already seated, to be worth adding for harmony's
    // sake. High enough that a word brings in what it means rather than everything it tolerates.
    private const val COMPANY_SHARE_OF_BEST = 0.75

    // A preset that can do everything the sentence asked keeps its full claim.
    private const val FULLY_CAPABLE = 1.0

    // What one that can do none of it keeps. **Scaled down rather than out-bid**, which an added bonus was
    // and which measurement showed was the wrong shape: "cherry_grove" landed on a dressing with no biome
    // table at *half* of twelve seeds, because a flat bonus only has to beat one rival at a time and there
    // are three. A factor bites however many rivals there are and however strongly they are liked.
    //
    // Small but never zero, for the same reason FAINTEST_CHANCE is not zero: a word that merely sets a
    // parameter must not be able to *eliminate* a preset (design §3.2 — it does not narrow), only make it a
    // poor answer. So a sentence can still land somewhere that cannot honour it, rarely, and be charged.
    private const val INCAPABLE_FACTOR = 0.04

    // What an evocative word is worth where it was not aimed, and where it was. Aiming leans the draw; it
    // never decides it, which is what keeps an aimed evocative word evocative (§4.3.1).
    private const val UNEMPHASISED = 1.0
    private const val AIMED_AT_THIS_SLOT = 2.0

    // Arbitrary large odds, only ever needed to decorrelate one draw from another.
    private const val ASPECT_STRIDE = 0x1F3B_5D79L
    private const val TERRITORY_STRIDE = 0x4C9E_1A2BL
    private const val COMPANY_SALT = 0x600D_C0A1L
    private const val WORD_MIXER = -0x61c8_8646_80b5_83ebL

    /**
     * The world [sentence] describes at [seed].
     *
     * Slots are resolved independently and in aspect order, which is deliberate: a aspect's filling must not
     * depend on what another aspect happened to draw, or the same sentence would resolve differently
     * depending on the order the aspects are visited in.
     */
    fun resolve(vocabulary: Vocabulary, sentence: Sentence, seed: Long): Resolution {
        val said = sentence.constraints
        // §4.6: what is unconstrained should still vary with what was written, or two entirely different
        // sentences at one seed draw identical filler wherever neither of them constrains anything.
        val draw = seed xor saltOf(sentence.words)
        val flaws = mutableListOf<Flaw>()
        val filled = Aspect.entries.associateWith { aspect -> fill(vocabulary, aspect, said, draw, flaws) }
        flaws += tensions(vocabulary, said, filled.mapValues { (_, filling) -> filling.map { it.preset } })

        return Resolution(
            composition = steer(vocabulary, compose(filled), said, draw, flaws),
            instability = Instability(flaws.toList()),
            cost = said.sumOf { it.word.tier.cost * reachOf(vocabulary, it).size },
            words = sentence.words,
            dropped = sentence.dropped,
        )
    }

    /**
     * Which aspects a constraint actually speaks to — **the grammar's answer, not a search**.
     *
     * Where the resolver used to ask [aspectsSpokenTo] what a *word* was about, it now asks what the
     * *sentence* decided, because attachment is precisely what a grammar is for (design §4.3.1). The old
     * question survives underneath as the meaning of "anywhere": an unaimed word still reaches wherever it
     * finds purchase, which is what [Scope.Everywhere] resolves against.
     */
    private fun reachOf(vocabulary: Vocabulary, constraint: Constraint): List<Aspect> =
        constraint.scope.reaches(aspectsSpokenTo(vocabulary, constraint.word))

    /**
     * How much louder an evocative word is where the writer aimed it (§4.3.1's tier rule).
     *
     * A tilt and never a fence: `beautiful sky` still shifts weights over every candidate in the world, it
     * simply shifts them hardest overhead. Confining it instead would demote it to a restrictive word, which
     * is the one thing its tier is defined as not being.
     */
    private fun emphasis(constraint: Constraint, aspect: Aspect): Double {
        val aimed = constraint.scope as? Scope.Everywhere ?: return UNEMPHASISED
        return if (aspect in aimed.emphasised) AIMED_AT_THIS_SLOT else UNEMPHASISED
    }

    /**
     * What fills one aspect: one preset, or several where the sentence left it no way to be one thing.
     *
     * Reads as three steps. Ask each narrowing word which presets it would keep. Gather those into
     * **territories** — groups of words that can be satisfied together — where a word joins the first
     * territory it is compatible with and starts a new one when it is compatible with none. Then draw one
     * preset per territory, tilted by the evocative words.
     */
    private fun fill(
        vocabulary: Vocabulary,
        aspect: Aspect,
        sentence: List<Constraint>,
        draw: Long,
        flaws: MutableList<Flaw>,
    ): List<Filling> {
        val speaking = sentence.filter { aspect in reachOf(vocabulary, it) }
        // Most precise first, and where precision ties the seed decides — never the writer's word order.
        // A word that only sets a parameter narrows nothing however precise it is: it has no opinion about
        // *which* preset fills the aspect, only about how that preset is made.
        val narrowing = speaking.filter { it.word.tier.narrows && it.word.constrainsPresetsIn(aspect) }
            .sortedWith(compareByDescending<Constraint> { it.word.tier }.thenBy { tieBreak(draw, aspect, it.word) })

        val territories = mutableListOf<Territory>()
        for (said in narrowing) {
            val carriers = vocabulary.carriersOf(said.word, aspect)
            if (carriers.isEmpty()) {
                // **Unless it is here to turn a knob rather than to choose a preset.** A word may narrow in one
                // aspect and merely steer in another — `verdant` narrows on `lush` where something carries it and
                // bounds the climate's axes with spans — and charging the second as unbacked told a writer their
                // perfectly good sentence had failed. The same distinction [wordsNothingHonours] draws further
                // down, applied where the carrier set is consulted.
                val steersInstead = said.word.sets.keys.any { vocabulary.turnsAKnob(aspect, it) }
                if (steersInstead) continue
                // Word against *world*: nothing in the aspect can be this, so no arrangement of the others
                // is to blame. A content bug per §3.3, and reported rather than dropped.
                flaws += flaw(Register.UNBACKED, listOf(said), aspect, emptyList(), said.word.tier)
                continue
            }
            val home = territories.indexOfFirst { it.candidates.any { candidate -> candidate in carriers } }
            if (home < 0) {
                territories += Territory(listOf(said), carriers)
            } else {
                territories[home] += Territory(listOf(said), carriers)
            }
        }

        // A positional aspect can honour several answers by giving each its own ground; a singular one has
        // nowhere to put a second, which is where the harsher register earns its place (§3.4).
        val room = if (aspect.positional) MOST_TERRITORIES else 1
        val kept = territories.take(room)
        chargeForContention(vocabulary, aspect, kept, territories.drop(room), flaws)

        val chosen = mutableListOf<AspectPreset>()
        for ((index, territory) in kept.withIndex()) {
            chosen += pick(vocabulary, territory.candidates, speaking, draw, aspect, seat = index)
        }
        if (chosen.isEmpty()) {
            chosen += pick(vocabulary, vocabulary.candidatesFor(aspect), speaking, draw, aspect, seat = 0)
        }

        chosen += company(vocabulary, aspect, kept, speaking, chosen, room, draw)
        return sharedOut(vocabulary, aspect, chosen, speaking)
    }

    /**
     * What the sentence is charged for asking one aspect to be several things it cannot reconcile.
     *
     * Note what is *not* here: the extra presets [company] adds cost nothing. A sentence whose words all
     * point the same way and simply admit several answers is not incoherent — it is being generous, and
     * charging it would make vagueness dangerous, which is the opposite of the design's whole shape.
     */
    private fun chargeForContention(
        vocabulary: Vocabulary,
        aspect: Aspect,
        kept: List<Territory>,
        lost: List<Territory>,
        flaws: MutableList<Flaw>,
    ) {
        val leading = kept.firstOrNull()?.words?.firstOrNull() ?: return
        for (territory in kept.drop(1)) {
            val contender = territory.words.first()
            // **A division the writer asked for costs nothing.** `and` means "keep both, and keep them
            // apart" (§3.2), so two territories a writer joined are harmony rather than contradiction —
            // the same reason [company] is free. Unjoined, they are still two words the world could not
            // reconcile, and still charged.
            if (wereJoined(contender, leading)) continue
            flaws += flaw(
                Register.FRACTURE,
                listOf(contender, leading),
                aspect,
                opposedTags(vocabulary, contender, leading),
                maxOf(contender.word.tier, leading.word.tier),
            )
        }
        for (said in lost.flatMap { it.words }) {
            flaws += flaw(
                Register.DISPLACED,
                listOf(said, leading),
                aspect,
                opposedTags(vocabulary, said, leading),
                said.word.tier,
            )
        }
    }

    /**
     * Whether a writer joined these two with `and`.
     *
     * Both being ungrouped is emphatically **not** a join: unjoined juxtaposition has to keep meaning
     * contention, because if standing side by side already meant "and" then "and" would mean nothing.
     */
    private fun wereJoined(one: Constraint, other: Constraint): Boolean =
        one.group != null && one.group == other.group

    /**
     * The presets a aspect takes on **because the sentence liked several of them**, not because it contradicted
     * itself — the harmonious division, and free (Jonah's call, design §3.4).
     *
     * "Beautiful" should be able to mean a beach *and* a field of flowers, the two standing side by side, in
     * the same way it reaches across aspects to mean a gentle sea and a bright sky. Nothing about that is
     * incoherent, so nothing about it is charged: the only division that costs anything is the one where two
     * words could not both be satisfied by one answer.
     *
     * Three rules keep it from turning every Age into a patchwork:
     *
     * - **An exact word forbids it.** Exact *pins one value* (§4.4); a writer who was precise asked for one
     *   thing and gets one thing.
     * - **Company must be nearly as well liked** as what was already seated, so a word brings in what it
     *   genuinely means rather than everything it can tolerate.
     * - **Each aspect has its own appetite**, and the terrain's is deliberately the smallest: two shapes in
     *   one world is the hardest kind of coexistence to make read well, where two dressings is what
     *   Minecraft's own biomes do everywhere. Per §1 this randomness owes the player a word, and the words
     *   are already named in §4.5 — the quantifiers, *varied* through to *unbroken*.
     */
    private fun company(
        vocabulary: Vocabulary,
        aspect: Aspect,
        territories: List<Territory>,
        speaking: List<Constraint>,
        seated: List<AspectPreset>,
        room: Int,
        draw: Long,
    ): List<AspectPreset> {
        if (!aspect.positional || seated.size >= room) return emptyList()
        // An exact word about *the preset* forbids company. One that merely sets a parameter does not: it
        // expressed no view on how many kinds of place the aspect holds, and treating it as though it had
        // would make naming a material quietly suppress harmony everywhere.
        if (speaking.any { it.word.tier == Tier.EXACT && it.word.constrainsPresetsIn(aspect) }) return emptyList()

        // Whatever the narrowing words left, or the whole aspect where none spoke: company can only ever be
        // something the sentence would have accepted in the first place.
        val eligible = territories.flatMap { it.candidates }.ifEmpty { vocabulary.candidatesFor(aspect) }
        val bar = COMPANY_SHARE_OF_BEST * seated.maxOf { strengthOf(vocabulary, it, speaking, aspect) }
        val welcome = eligible.filter { it !in seated && strengthOf(vocabulary, it, speaking, aspect) >= bar }
        if (welcome.isEmpty()) return emptyList()

        val random = XoroshiroRandomSource(draw xor (aspect.ordinal * ASPECT_STRIDE) xor COMPANY_SALT)
        val joining = mutableListOf<AspectPreset>()
        var appetite = aspect.appetiteForCompany
        while (seated.size + joining.size < room && random.nextDouble() < appetite) {
            val remaining = welcome.filter { it !in joining }
            if (remaining.isEmpty()) break
            joining += pick(vocabulary, remaining, speaking, draw, aspect, seat = seated.size + joining.size)
            // Each further guest is less likely than the last, so three-way harmony stays a rarity.
            appetite *= appetite
        }
        return joining
    }

    /**
     * How much ground each chosen preset covers: its share of the total claim on the aspect, snapped to a
     * named rung (design §3.4).
     *
     * So a word strongly associated with one preset and weakly with another gives mostly the first with
     * scarce islands of the second, which is what makes an Age worth crossing. The strongest claim is always
     * [Share.DOMINANT] and the others are measured against it.
     */
    private fun sharedOut(
        vocabulary: Vocabulary,
        aspect: Aspect,
        chosen: List<AspectPreset>,
        speaking: List<Constraint>,
    ): List<Filling> {
        val claims = chosen.map { claimOn(vocabulary, it, speaking, aspect) }
        val strongest = claims.max()
        // Nothing in the sentence had anything to say about this aspect, so nothing justifies favouring one of
        // its answers over another: an even division is the honest outcome.
        if (strongest <= FAINTEST_CHANCE) return chosen.map { Filling(it, Share.DOMINANT) }
        val shares = Share.findable(claims.map { Share.nearest(it / strongest) })
        // Widest first, so a recipe reads the way the sentence would be spoken — "mostly hills, with
        // scattered pillars" — and so the terrain whose sea prevails is the one named first.
        return chosen.mapIndexed { index, preset -> Filling(preset, shares[index]) }
            .sortedByDescending { it.share.weight }
    }

    /**
     * How hard the sentence claims this preset — **the tag weights alone**, with none of the machinery that
     * merely gets *something* chosen.
     *
     * The distinction against [strengthOf] is the whole reason shares mean anything. That function decides
     * which preset is drawn, so it needs a floor and a readiness term: something must be picked even where
     * nothing was asked for. A *share* answers a different question — how much of the world this preset
     * deserves — and a floor there would compress every ratio towards one, snapping every Age back to an even
     * division. So this is the association strength and nothing else: a word that names something at `0.9`
     * against one that allows it at `0.3` really does give mostly the first.
     */
    private fun claimOn(
        vocabulary: Vocabulary,
        preset: AspectPreset,
        speaking: List<Constraint>,
        aspect: Aspect,
    ): Double {
        val tags = vocabulary.tagsOf(preset)
        val named = speaking.filter { it.word.tier.narrows }
            .maxOfOrNull { it.word.pullOn(preset, tags) * it.word.tier.weight } ?: 0.0
        val liked = speaking.filter { !it.word.tier.narrows }
            .sumOf { it.word.affinityFor(tags) * emphasis(it, aspect) }
        return (named + liked).coerceAtLeast(0.0)
    }

    /**
     * How strong a claim the sentence makes on one preset — the single number that decides both which preset
     * is drawn and how much ground it then covers.
     *
     * Three terms, and each is a rule from the design in arithmetic form: a **base** scaled by the preset's
     * readiness, so an unasked-for draw prefers the ordinary (§3.3); the strongest **pull** of any narrowing
     * word, weighted by its precision, so a word that names something exactly claims it harder than one that
     * merely allows it; and the summed **affinity** of the evocative words, which may be negative, so
     * "beautiful" pushes lava away as surely as it pulls flowers in.
     */
    private fun strengthOf(
        vocabulary: Vocabulary,
        preset: AspectPreset,
        speaking: List<Constraint>,
        aspect: Aspect,
    ): Double {
        val tags = vocabulary.tagsOf(preset)
        val named = speaking.filter { it.word.tier.narrows }
            .maxOfOrNull { it.word.pullOn(preset, tags) * it.word.tier.weight } ?: 0.0
        val liked = speaking.filter { !it.word.tier.narrows }
            .sumOf { it.word.affinityFor(tags) * emphasis(it, aspect) }
        val wanted = BASE_WEIGHT * vocabulary.readinessOf(preset) + named + liked
        return (wanted * capabilityFactor(preset, speaking)).coerceAtLeast(FAINTEST_CHANCE)
    }

    /**
     * How much of what the sentence *set* this preset could actually honour.
     *
     * Without this, "a cherry grove Age" draws a dressing at random and lands on `bare_rock` two times in
     * three — a dressing with no biome table at all — so the word sets a parameter that nothing reads and
     * the writer gets grey rock. The word was neither dropped nor charged; it simply evaporated, which is
     * §3.3's silent drop by the newest route.
     *
     * A *tilt* rather than a filter, deliberately. Parameter-setting words are not supposed to choose
     * presets — that is the whole of [Word.constrainsPresets] — so this leans the draw toward a preset that
     * can honour them without ever forbidding one that cannot. A writer who says "cherry grove floating"
     * still gets floating islands, and the fact that they have no biomes is then a real contradiction for
     * [wordsNothingHonours] to charge rather than an arbitrary silence.
     */
    private fun capabilityFactor(preset: AspectPreset, speaking: List<Constraint>): Double {
        val parametersAsked = speaking.flatMap { it.word.sets.keys }.distinct()
        if (parametersAsked.isEmpty()) return FULLY_CAPABLE
        val honoured = parametersAsked.count(preset::honoursParameterNamed)
        val share = honoured.toDouble() / parametersAsked.size
        return INCAPABLE_FACTOR + (FULLY_CAPABLE - INCAPABLE_FACTOR) * share
    }

    /**
     * A set of words that can all be satisfied at once, and what is left that satisfies them.
     *
     * Territories are disjoint by construction: a new one is only ever started by a word that shares no
     * candidate with any existing one, and narrowing an existing one only ever removes candidates. So two
     * territories can never draw the same preset, and the world really does end up with as many kinds of
     * place as it has territories.
     */
    /** One preset a aspect ended up holding, and how much of the world it covers. */
    private data class Filling(val preset: AspectPreset, val share: Share)

    private data class Territory(val words: List<Constraint>, val candidates: List<AspectPreset>) {
        operator fun plus(joining: Territory) =
            Territory(words + joining.words, candidates.filter { it in joining.candidates })
    }

    /**
     * Which aspects this word has a say in: the ones it is about, or wherever it finds purchase.
     *
     * Public because it is also what a word *costs* (§4.4) and what `VocabularyCheck` has to know
     * to tell a word with no carrier from a word about a aspect it cannot reach.
     */
    fun aspectsSpokenTo(vocabulary: Vocabulary, word: Word): List<Aspect> {
        if (word.aspects.isNotEmpty()) return word.aspects.sortedBy { it.ordinal }
        // An evocative word declares no aspect, meaning anywhere it can find a foothold — spanning aspects is
        // what makes a word evocative in the first place.
        return Aspect.entries.filter { aspect ->
            vocabulary.candidatesFor(aspect).any { word.pull(vocabulary.tagsOf(it)) > 0.0 }
        }
    }

    /**
     * One preset from [candidates], drawn in proportion to how strongly the sentence claims each.
     *
     * The same [strengthOf] that decides shares, so the two agree by construction: a preset the words like
     * best is both likelier to be chosen and, once chosen, given more of the world.
     */
    private fun pick(
        vocabulary: Vocabulary,
        candidates: List<AspectPreset>,
        speaking: List<Constraint>,
        draw: Long,
        aspect: Aspect,
        seat: Int,
    ): AspectPreset {
        candidates.singleOrNull()?.let { return it }
        val scores = candidates.map { preset -> strengthOf(vocabulary, preset, speaking, aspect) }
        val random = XoroshiroRandomSource(draw xor (aspect.ordinal * ASPECT_STRIDE) xor (seat * TERRITORY_STRIDE))
        var remaining = random.nextDouble() * scores.sum()
        for ((index, score) in scores.withIndex()) {
            remaining -= score
            if (remaining <= 0.0) return candidates[index]
        }
        return candidates.last()
    }

    /**
     * Every tension the world *honoured*: two words that mean opposite things, both of them present in the
     * same aspect's answer.
     *
     * Deliberately asked of the outcome rather than of the sentence, which is what keeps the index from
     * charging one disagreement twice. A tension the world could not honour has already been charged as a
     * [Register.FRACTURE] or a [Register.DISPLACED] — and where it *was* honoured, "you wrote opposites"
     * and "the world tore in two to do it" are two different facts about the sentence (§3.3), so both are
     * charged, gently.
     */
    private fun tensions(
        vocabulary: Vocabulary,
        sentence: List<Constraint>,
        filled: Map<Aspect, List<AspectPreset>>,
    ): List<Flaw> = buildList {
        for ((first, second) in sentence.pairs()) {
            // Joined words are not in tension: a writer who said "keep both" was not contradicting himself,
            // and charging it would make the conjunction cost something it was defined as not costing.
            if (wereJoined(first, second)) continue
            val shared = reachOf(vocabulary, first).intersect(reachOf(vocabulary, second).toSet())
            for (aspect in shared) {
                val chosen = filled[aspect].orEmpty()
                if (chosen.none { first.word.accepts(vocabulary.tagsOf(it)) }) continue
                if (chosen.none { second.word.accepts(vocabulary.tagsOf(it)) }) continue
                val opposition = oppositionBetween(vocabulary, first, second) ?: continue
                add(
                    Flaw(
                        Register.TENSION,
                        listOf(first.word.name, second.word.name),
                        aspect,
                        listOf(opposition.first, opposition.second),
                        opposition.severity,
                    ),
                )
            }
        }
    }

    /** The first known opposition between what two words ask for, if the table has heard of one. */
    private fun oppositionBetween(vocabulary: Vocabulary, first: Constraint, second: Constraint): Antonym? =
        first.word.wanted.firstNotNullOfOrNull { wanted ->
            second.word.wanted.firstNotNullOfOrNull { against -> vocabulary.opposition(wanted, against) }
        }

    /** The two tags that disagreed, where the table knows them — for the explanation, not the detection. */
    private fun opposedTags(vocabulary: Vocabulary, first: Constraint, second: Constraint): List<String> =
        oppositionBetween(vocabulary, first, second)?.let { listOf(it.first, it.second) } ?: emptyList()

    private fun flaw(register: Register, said: List<Constraint>, aspect: Aspect, tags: List<String>, tier: Tier) =
        Flaw(register, said.map { it.word.name }, aspect, tags, register.charge(tier))

    /**
     * The composition these fillings describe.
     *
     * The stand-in terrain is overwritten immediately — every aspect in [filled] holds at least one preset,
     * terrain included — and exists only because a composition cannot be built without one. The same
     * trick, for the same reason, as [AgeComposition.Companion.parse].
     */
    private fun compose(filled: Map<Aspect, List<Filling>>): AgeComposition {
        var composition = AgeComposition(terrains = listOf(Terrain.SHAPES))
        for ((aspect, filling) in filled) {
            check(filling.isNotEmpty()) { "the ${aspect.key} aspect resolved to nothing, which no sentence can do" }
            composition = composition.withPresets(aspect, filling.map { it.preset.key }, filling.map { it.share })
        }
        return composition
    }

    /**
     * The composition with every parameter the sentence chose applied — materials, and any later knob a
     * word learns to turn (design §3.2).
     *
     * Runs after the presets are settled rather than alongside them, and that ordering is the whole design:
     * a parameter steers whatever filled the aspect, so it cannot be resolved until something has. It also
     * means a material never influences *which* preset was drawn, which is right — "a world of blackstone"
     * says what the rock is, not whether the world has hills.
     *
     * **Two words setting one parameter is a contradiction with nowhere to go.** A parameter holds one
     * value, and unlike a positional aspect it cannot divide — options are per aspect, not per territory — so
     * this is the singular case §3.4 describes, and it charges the same way a contested sky does. The
     * seed picks the survivor, never the writer's word order (§3.5).
     */
    private fun steer(
        vocabulary: Vocabulary,
        composition: AgeComposition,
        sentence: List<Constraint>,
        draw: Long,
        flaws: MutableList<Flaw>,
    ): AgeComposition {
        var steered = composition
        for (aspect in Aspect.entries) {
            val setting = sentence.filter { it.word.sets.isNotEmpty() && aspect in reachOf(vocabulary, it) }
            if (setting.isEmpty()) continue
            // **Every ranged axis of the aspect at once, before the rest.** A fragment is a whole climate, not a
            // temperature — so grouping has to be asked of all the axes a word touches together. Resolving them
            // one at a time produced a measured bug: `tropical frozen` fractured on temperature and then, with
            // the aspect already divided, *displaced* humidity, leaving the frozen fragment carrying tropical's
            // wetness. See [spanned].
            steered = steered.spanned(vocabulary, aspect, setting, draw, flaws)
            val settled = rangedNames(steered, aspect)
            for (parameter in setting.flatMap { it.word.sets.keys }.distinct()
                .filter { it !in settled && holds(steered, aspect, it) }) {
                val contenders = setting.filter { parameter in it.word.sets }
                    .sortedWith(compareByDescending<Constraint> { it.word.tier }.thenBy { tieBreak(draw, aspect, it.word) })
                steered = when {
                    // "There are cherry groves" and "there are deserts" do not conflict, so nothing is
                    // charged and nothing is dropped — every word's value simply joins the set (§3.2).
                    //
                    // **The polarity travels with the value**, which is what makes `only` and `except` reach a
                    // population at all: the grammar has attached it since the parser landed and the resolver
                    // dropped it on the floor, so "except pillager outposts" parsed perfectly and did nothing.
                    // See [co.voik.agesandtheart.age.aspect.Claim] for the spelling and why it is a mark.
                    isPopulative(steered, aspect, parameter) ->
                        steered.withOptions(aspect, parameter, contenders.map { it.claimed(parameter) }.distinct())
                    canFracture(steered, aspect, parameter, contenders) ->
                        steered.fractured(vocabulary, aspect, parameter, contenders, flaws)
                    else -> steered.contended(aspect, parameter, contenders, flaws)
                }
            }
            flaws += wordsNothingHonours(steered, aspect, setting)
        }
        return steered
    }

    /**
     * A **ranged** parameter's claimants, gathered and applied: those that do not disagree **broaden** into one
     * span, and those that do take ground of their own.
     *
     * **Overlap is the arbiter.** Two spans that share ground can both be honoured, so they broaden into the span
     * that holds them and nothing is charged — a world trying to be two things comes out *broader*, which is a
     * technique rather than a fault (Jonah: "it becomes a technique astute writers can pick up on"). Two spans
     * that share none cannot both be honoured anywhere, so each takes ground of its own.
     *
     * **Jonah proposed the antonym table as the arbiter and this uses overlap instead, deliberately.** Antonymy
     * is a *proxy* for "these cannot both hold", and a lossy one in both directions: two words with no antonym
     * pair between them but disjoint spans would broaden into a hull covering ground **neither** of them asked
     * for, which is exactly §3.3's mush arriving silently; and the table is authored per *tag*, while a span
     * belongs to an *axis*, so an opposition would have had to be invented for every pair of axes a word touches.
     * Overlap is the mechanical truth the table was standing in for, it needs no data to maintain, and it cannot
     * fail quietly. The table keeps its own job: [opposedTags] still names *why* two words disagreed in the flaw.
     *
     * Note this is the one combining rule where the outcome of two words is **wider** than either alone. That
     * inverts how a predicative parameter behaves and is deliberate; see [Parameter.Kind.RANGED].
     */
    private fun AgeComposition.spanned(
        vocabulary: Vocabulary,
        aspect: Aspect,
        setting: List<Constraint>,
        draw: Long,
        flaws: MutableList<Flaw>,
    ): AgeComposition {
        val axes = rangedNames(this, aspect)
        val speaking = setting.filter { said -> axes.any { it in said.word.sets } }
            .sortedWith(compareByDescending<Constraint> { it.word.tier }.thenBy { tieBreak(draw, aspect, it.word) })
        if (speaking.isEmpty()) return this

        fun boundsIn(said: Constraint): Map<String, Span> = axes.mapNotNull { axis ->
            said.word.sets[axis]?.let { axis to (Span.read(it) ?: Span.NATURAL) }
        }.toMap()

        fun agree(one: Constraint, other: Constraint): Boolean {
            if (wereJoined(one, other)) return true
            val mine = boundsIn(one)
            val theirs = boundsIn(other)
            // Two words about *different* axes never disagree — "hot" and "wet" is an ordinary sentence. Two
            // about the same axis have to leave each other room on every axis they share.
            return (mine.keys intersect theirs.keys).all { axis ->
                mine.getValue(axis).overlaps(theirs.getValue(axis))
            }
        }

        val groups = gathered(speaking, ::agree)
        // Each group's words broaden together, axis by axis, into the climate they jointly describe.
        val climates = groups.map { group ->
            axes.mapNotNull { axis ->
                group.mapNotNull { boundsIn(it)[axis] }
                    .reduceOrNull { held, next -> held.broadenedTo(next) }
                    ?.let { axis to it }
            }.toMap()
        }

        fun written(composition: AgeComposition, member: Int, bounds: Map<String, Span>): AgeComposition {
            var steered = composition
            for ((axis, span) in bounds) {
                steered = steered.withOptionsFor(aspect, member, axis, listOf(span.spelled()))
            }
            return steered
        }

        val couldFracture = aspect.positional && presets.count { it.aspect == aspect } == 1
        if (climates.size == 1 || !couldFracture || climates.size > MOST_TERRITORIES) {
            // One coherent climate, or nowhere to put a second. Where there is nowhere, the leading group wins
            // and the rest are displaced — the same fallback every other parameter has.
            val leading = groups.first().first()
            if (climates.size > 1) {
                for (group in groups.drop(1)) {
                    flaws += flaw(Register.DISPLACED, listOf(group.first(), leading), aspect, emptyList(), group.first().word.tier)
                }
            }
            return written(this, member = 0, bounds = climates.first())
        }

        val leading = groups.first().first()
        for (group in groups.drop(1)) {
            val contender = group.first()
            if (wereJoined(contender, leading)) continue
            flaws += flaw(
                Register.FRACTURE,
                listOf(contender, leading),
                aspect,
                opposedTags(vocabulary, contender, leading),
                maxOf(contender.word.tier, leading.word.tier),
            )
        }
        val seated = presets.first { it.aspect == aspect }
        var fractured = withPresets(aspect, List(groups.size) { seated.key })
        for ((member, bounds) in climates.withIndex()) fractured = written(fractured, member, bounds)
        return fractured
    }

    /** Which of [aspect]'s parameters bound a continuous axis — asked of the seated presets, as they own them. */
    private fun rangedNames(composition: AgeComposition, aspect: Aspect): List<String> =
        composition.presets.filter { it.aspect == aspect }
            .flatMap { it.parameters }
            .filter { it.kind == Parameter.Kind.RANGED }
            .map { it.name }
            .distinct()

    /**
     * **Parameters divide, not just presets** — the mechanism §3.4's rule was missing (Jonah, 2026-07-29).
     *
     * A aspect divides when it cannot reconcile two words: both are honoured, in different places, and the
     * world merely gets strange. That has always been true of *presets* and never of the knobs on them, so
     * "copper andesite" put two claims on one parameter, one won, and the other was charged as displaced —
     * §3.4's own principle, unapplied one level down. Here it is applied.
     *
     * Whether the groups can each be given ground. Three conditions, and each is a real one:
     *
     * - the aspect must be able to divide at all — a sky cannot;
     * - it must hold **exactly one preset**. Where it already divided on presets, a parameter conflict has to
     *   contend instead: the two divisions would multiply, and *which* territory a word was about is a question
     *   only the grammar could answer and it cannot yet (design §4.3.1's aiming reaches aspects, not members);
     * - there must be two or more groups, and no more than there is room for.
     */
    private fun canFracture(
        composition: AgeComposition,
        aspect: Aspect,
        parameter: String,
        contenders: List<Constraint>,
    ): Boolean {
        if (!aspect.positional) return false
        if (composition.presets.count { it.aspect == aspect } != 1) return false
        return groupsOf(parameter, contenders).size in 2..MOST_TERRITORIES
    }

    /**
     * The claimants gathered into the fewest sets that can each be satisfied at once — the parameter-level
     * echo of [Territory].
     *
     * Two claimants belong together when they asked for **the same value**, or when the writer **joined them
     * with `and`**, which is what keeps the conjunction meaning "mingle" rather than "divide".
     *
     * Order-blind, because [contenders] arrives sorted by precision and then by a seed-derived tie-break, never
     * by where the pages were laid out (§3.5).
     */
    private fun groupsOf(parameter: String, contenders: List<Constraint>): List<List<Constraint>> {
        fun agree(one: Constraint, other: Constraint) =
            one.word.sets[parameter] == other.word.sets[parameter] || wereJoined(one, other)
        return gathered(contenders, ::agree)
    }

    /**
     * [contenders] gathered into the fewest sets whose members **all** [agree] with one another.
     *
     * The shared shape behind [Territory] and both fracture paths: join the first group you are compatible with,
     * start a new one when you are compatible with none. What *compatible* means is the caller's — the same
     * value, or overlapping spans.
     *
     * **Every member, not merely one of them, and that distinction is the whole correctness of it** (Jonah,
     * 2026-07-29). Agreement between spans is *not transitive*: `hot` overlaps `warm`, `warm` overlaps
     * `temperate`, `temperate` overlaps `cool` — so joining on any single member walks a chain and swallows the
     * five rungs of a ladder into one group whose hull is **the entire axis**, handing back the full-range world
     * the writer was narrowing away from, silently and charged nothing. Requiring all of them makes a group mean
     * what it says: words that can *all* hold together at once. It is also what [Territory] gets for free by
     * intersecting carrier sets, which a hull cannot do.
     */
    private fun gathered(
        contenders: List<Constraint>,
        agree: (Constraint, Constraint) -> Boolean,
    ): List<List<Constraint>> {
        val groups = mutableListOf<MutableList<Constraint>>()
        for (said in contenders) {
            val home = groups.firstOrNull { group -> group.all { seated -> agree(seated, said) } }
            if (home == null) groups += mutableListOf(said) else home += said
        }
        return groups
    }

    /**
     * The aspect seated once per group, each territory steered by its own group — "copper spires **and**
     * andesite hills" without the `and`, and without either word being lost.
     *
     * **Seating one preset several times is the point, not a workaround.** §3.2 settled that a *list* on a
     * parameter means mingled rather than divided, on the grounds that division already has a spelling — name
     * the preset twice. This writes that spelling: the result is `dressing=verdant{stone=copper},verdant{stone=
     * andesite}`, which a writer could already have typed into `/age compose`, and which `toString` and `parse`
     * have round-tripped since options became per member.
     *
     * Shares are left unsaid, so the territories divide evenly. There is nothing in a parameter conflict to say
     * which claim deserves more ground — unlike a preset claim, which carries a tag strength [claimOn] can weigh.
     */
    private fun AgeComposition.fractured(
        vocabulary: Vocabulary,
        aspect: Aspect,
        parameter: String,
        contenders: List<Constraint>,
        flaws: MutableList<Flaw>,
    ): AgeComposition {
        val groups = groupsOf(parameter, contenders)
        val seated = presets.first { it.aspect == aspect }
        val leading = groups.first().first()
        for (group in groups.drop(1)) {
            val contender = group.first()
            // The same exemption [chargeForContention] makes: a writer who joined them asked for both to
            // stand, so keeping them apart is what was wanted rather than a fault.
            if (wereJoined(contender, leading)) continue
            flaws += flaw(
                Register.FRACTURE,
                listOf(contender, leading),
                aspect,
                opposedTags(vocabulary, contender, leading),
                maxOf(contender.word.tier, leading.word.tier),
            )
        }
        var fractured = withPresets(aspect, List(groups.size) { seated.key })
        for ((member, group) in groups.withIndex()) {
            val asked = group.map { it.word.sets.getValue(parameter) }.distinct()
            fractured = fractured.withOptionsFor(aspect, member, parameter, asked)
        }
        return fractured
    }

    /**
     * What this constraint asks of [parameter], spelled the way a recipe holds it.
     *
     * Only a *populative* parameter reads the polarity back: `only` on a material is a claim about the
     * dressing's cover rather than about a list (design §3.2), and is deliberately not built, so a predicative
     * parameter keeps taking the bare value through [contended].
     */
    private fun Constraint.claimed(parameter: String): String =
        Claim(word.sets.getValue(parameter), polarity).spelled()

    /**
     * A predicative parameter with more than one claimant — where **`and` earns its place** (§3.2).
     *
     * A parameter holds one answer, so unjoined claims contend: the most precise wins, the seed breaks a
     * tie, and every loser is charged as displaced. Joining them changes what was asked for rather than who
     * wins — "the rock is blackstone **and** tuff" is one rock made of both, which [Options] has been able
     * to hold since 3a and `Palette.mingled` has been able to paint for just as long. This is the wire
     * between them, and it is the whole of what the conjunction needed.
     *
     * Only the winner's *own* group joins it. A second group in the same parameter is a genuinely different
     * claim about one thing, and still loses.
     */
    private fun AgeComposition.contended(
        aspect: Aspect,
        parameter: String,
        contenders: List<Constraint>,
        flaws: MutableList<Flaw>,
    ): AgeComposition {
        val winner = contenders.first()
        val mingled = contenders.filter { it == winner || wereJoined(it, winner) }
        for (loser in contenders - mingled.toSet()) {
            flaws += flaw(Register.DISPLACED, listOf(loser, winner), aspect, emptyList(), loser.word.tier)
        }
        return withOptions(aspect, parameter, mingled.map { it.word.sets.getValue(parameter) }.distinct())
    }

    /**
     * Whether this aspect has such a knob at all.
     *
     * One word may speak to several aspects and carries **one** `sets` map, so a derived block word setting a
     * material reaches the sea as well — and a sea does not *wear* a substance, it **is** one. Without
     * this, naming lava wrote `sea.stone=minecraft:lava` into the recipe and charged the sentence for a
     * knob the aspect never had, which is the resolver inventing a fault out of a word doing exactly its job.
     *
     * Asked of what the seated presets **declare**, not of what they honour — the two come apart on purpose.
     * A aspect that has never heard of the knob is simply not being addressed; one that declares it and does
     * nothing with it is a sentence the world could not honour, and [wordsNothingHonours] still charges for
     * that. Note this does not soften the *recipe's* own report: an option nobody understands still reaches
     * `AgeComposition.unknownOptions` and `/age list`, because a hand-written typo is a different thing from
     * a word reaching past its aspect.
     */
    private fun holds(composition: AgeComposition, aspect: Aspect, parameter: String): Boolean =
        composition.presets.filter { it.aspect == aspect }.any { preset ->
            preset.parameters.any { it.name == parameter }
        }

    /**
     * Whether [parameter] accumulates rather than contends — asked of the presets actually seated in
     * [aspect], since the parameter is theirs to declare (§3.2).
     *
     * Unknown to every seated preset counts as predicative, which is the safe reading: an option nobody
     * understands is kept and reported (see [co.voik.agesandtheart.age.aspect.Options]), and quietly
     * accumulating values for a knob that does not exist would make a typo look deliberate.
     */
    private fun isPopulative(composition: AgeComposition, aspect: Aspect, parameter: String): Boolean =
        composition.presets.filter { it.aspect == aspect }
            .flatMap { it.parameters }
            .any { it.name == parameter && it.kind == Parameter.Kind.POPULATIVE }

    /**
     * A material named at something that cannot wear it — reported rather than ignored (§3.3).
     *
     * Vanilla's overworld palette is a rule tree we do not own and cannot substitute a stone into, so
     * `dressing=overworld` painted in blackstone is a sentence the world cannot honour. Silence there
     * would be the failure the whole vocabulary check exists to prevent, so it charges as an unbacked word:
     * the writer said something true of the language that this Age had no way to be.
     */
    private fun wordsNothingHonours(
        composition: AgeComposition,
        aspect: Aspect,
        setting: List<Constraint>,
    ): List<Flaw> {
        val seated = composition.presets.filter { it.aspect == aspect }
        // Charged only where *every* seated preset ignores the word. One territory that honours it is
        // enough: the Age does what was asked somewhere, which is what a divided aspect is for.
        fun anythingSeatedHonours(parameter: String) = seated.any { it.honoursParameterNamed(parameter) }
        // And only where the word was addressing this aspect's knobs in the first place. A derived block word
        // carries one `sets` map across every aspect it speaks to, so naming lava reaches the sea — which
        // holds no material, because a sea *is* its block rather than being made of one. Charging that
        // told a writer their perfectly good sentence had failed. See [holds] for why declared and honoured
        // are different questions.
        val addressing = setting.filter { said -> said.word.sets.keys.any { holds(composition, aspect, it) } }
        val wentUnheeded = addressing.filter { said -> said.word.sets.keys.none(::anythingSeatedHonours) }
        return wentUnheeded.map { said -> flaw(Register.UNBACKED, listOf(said), aspect, emptyList(), said.word.tier) }
    }

    /**
     * A seed-sized number standing for what was written.
     *
     * Order-insensitive on purpose: page order is word order (§4.1), but §3.5 rejected letting page order
     * decide a contradiction, so it must not leak into the seed either — otherwise the same words in a
     * different order would quietly be a different world.
     */
    private fun saltOf(sentence: List<Word>): Long =
        sentence.fold(0L) { salt, word -> salt xor (word.id.hashCode().toLong() * WORD_MIXER) }

    /** A stable, unpredictable ordering key — how the seed arbitrates between equally precise words. */
    private fun tieBreak(draw: Long, aspect: Aspect, word: Word): Long =
        XoroshiroRandomSource(draw xor (aspect.ordinal * ASPECT_STRIDE) xor (word.id.hashCode().toLong() * WORD_MIXER))
            .nextLong()

    private fun <T> List<T>.pairs(): List<Pair<T, T>> =
        indices.flatMap { left -> (left + 1..<size).map { right -> this[left] to this[right] } }
}
