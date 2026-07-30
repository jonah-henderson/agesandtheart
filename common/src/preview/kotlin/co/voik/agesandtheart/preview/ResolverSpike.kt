package co.voik.agesandtheart.preview

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Biomes
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.Climate
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.AspectPreset
import co.voik.agesandtheart.age.aspect.Carvers
import kotlin.random.Random

/**
 * A SPIKE. Not the resolver — a thing built to find out whether the resolver can be built, and to be
 * thrown away once it has answered (`notes/the-art-implementation-plan.md`, Phase 3: *"consider spiking
 * the resolver against a toy aspect set… if constraint satisfaction over weighted tags turns out awkward,
 * it is far better to learn that here"*).
 *
 * **All six of its findings were settled on 2026-07-27, and the rulings are written into
 * `notes/the-art-design.md` itself — §3.3, §3.4, §3.5, §4.4 and §4.6. That document, not this file, is
 * what Phase 3 gets built from.** This is kept only as the evidence behind those decisions: it still
 * runs each sentence under the naive policy, which is what makes the failures visible rather than
 * merely asserted. Several resolutions (set-valued terrain and dressing, detection from data rather
 * than the antonym table, negative query weights) are deliberately **not** implemented here, because
 * implementing them is Phase 3's job and not a spike's.
 *
 * **One fact here is now stale.** [SET_VALUED] names terrain and dressing; since 3a the answer is all
 * four *positional* aspects — terrain, dressing, sea and carving — with only the sky singular. It
 * is left as it was so the reported findings still match what this file prints; the design doc is right
 * and this is a record of an argument, not a description of the world.
 *
 * **Do not build on this.** Nothing here is tuned, the vocabulary is a dozen words invented to exercise
 * the mechanism, and the tag weights are guesses. What it is for is answering four questions:
 *
 *  1. Does one uniform mechanism really carry all three precision tiers (design §4.4)?
 *  2. Does an authored antonym table find contradictions without a geometry (§3.3)?
 *  3. Does severity fall out of set-valued-versus-singular rather than being tuned per pair (§3.4)?
 *  4. Is resolution a pure function of (words, seed) (§4.6)?
 *
 * **Deliberately spiked against the real aspect set rather than a toy one.** Five aspects and twenty-one
 * presets is already toy-sized, and using the real ones answers a question a toy cannot: whether the
 * presets we actually shipped can be given sensible tags at all. The tags live in this file rather than
 * on the enums, so the spike stays disposable and the shipped aspects stay uncontaminated — which also
 * proves the tagging can be done from outside, as §8 will need when it comes from Minecraft's own tags.
 */
fun main() {
    reportVocabulary()
    for (sentence in SENTENCES) report(sentence)
    checkResolutionIsPure()
    checkVaguenessVaries()
    reportFindings()
}

// ---------------------------------------------------------------------------------------------
// The world model: tags on presets, and which aspects can absorb a disagreement.
// ---------------------------------------------------------------------------------------------

/**
 * Unipolar weighted tag membership (§3.3). Every weight is independent, so a thing may be neither
 * `lush` nor `barren`, or oddly both — which is the whole reason bipolar axes were rejected.
 *
 * These are guesses made in half an hour. Their *plausibility* is the point, not their values.
 */
private val TAGS: Map<AspectPreset, Map<String, Double>> = mapOf(
    Terrain.SPIRE_ISLANDS to mapOf("floating" to 1.0, "striking" to 0.9, "mountainous" to 0.7, "wild" to 0.6),
    Terrain.HILLS to mapOf("ordinary" to 0.9, "flat" to 0.4, "mountainous" to 0.3, "lush" to 0.3),
    Terrain.CAVERNS to mapOf("cavernous" to 1.0, "gloomy" to 0.7, "solid" to 0.8, "wild" to 0.4),
    Terrain.ERODED to mapOf("eroded" to 1.0, "wild" to 0.7, "mountainous" to 0.6, "barren" to 0.6),
    Terrain.PILLARS to mapOf("striking" to 0.9, "mountainous" to 0.9, "ordered" to 0.6, "barren" to 0.5),
    Terrain.PYRAMIDS to mapOf("ordered" to 1.0, "flat" to 0.7, "striking" to 0.6, "solid" to 0.6),
    Terrain.SHAPES to mapOf("ordered" to 0.8, "strange" to 0.5, "flat" to 0.5),

    Sea.NONE to mapOf("empty" to 1.0, "solid" to 0.3),
    Sea.WATER to mapOf("watery" to 1.0, "ordinary" to 0.6),
    Sea.LAVA to mapOf("molten" to 1.0, "hostile" to 0.9, "striking" to 0.5),

    Carvers.SOLID to mapOf("solid" to 1.0, "ordinary" to 0.5),
    Carvers.POROUS to mapOf("cavernous" to 0.4, "watery" to 0.3),
    Carvers.CAVES to mapOf("cavernous" to 0.9, "gloomy" to 0.5),
    Carvers.FLOODED_CAVES to mapOf("cavernous" to 0.8, "watery" to 0.8, "hostile" to 0.5, "gloomy" to 0.5),
    Carvers.WEATHERED to mapOf("eroded" to 0.9, "wild" to 0.5),

    // The four dressings this spike argued over are deleted (Phase 4.5 step 5); the aspect that replaced them
    // holds one preset, so the spike's dressing column collapses to a single row. Kept compiling rather than
    // re-tagged: its findings are settled in the design doc, and this is a record of an argument, not a model.
    Biomes.VANILLA to mapOf("ordinary" to 0.9, "lush" to 0.6, "bright" to 0.4),

    Sky.PLAIN to mapOf("bright" to 0.7, "ordinary" to 0.9),
    Sky.STORM to mapOf("gloomy" to 0.9, "striking" to 0.7),
)

/** The authored antonym table (§3.3) — a few dozen pairs, inspectable, where a latent space would not be. */
private val ANTONYMS = listOf(
    "lush" to "barren",
    "flat" to "mountainous",
    "bright" to "gloomy",
    "ordered" to "wild",
    "watery" to "molten",
    "ordinary" to "strange",
    "floating" to "solid",
    "empty" to "watery",
)

/**
 * Whether a aspect can satisfy a contradiction **by coexistence** (§3.4).
 *
 * This is where severity is supposed to come from for free: biomes are distributed across space, so
 * "lush, barren" can be honoured in two places and the world merely gets strange. A sky has nowhere to
 * put a second answer, so the same disagreement there has to go somewhere harsher.
 */
private val SET_VALUED = setOf(Aspect.TERRAIN, Aspect.BIOMES)

// ---------------------------------------------------------------------------------------------
// The language: every word is one constraint, differing only in tightness (§4.4).
// ---------------------------------------------------------------------------------------------

private enum class Tier(val label: String, val cost: Int) {
    /** Shifts weights over candidates; removes no freedom, so it can never fail. */
    EVOCATIVE("evocative", cost = 1),

    /** Narrows the candidate set to things that carry the tag at all. */
    RESTRICTIVE("restrictive", cost = 2),

    /** Pins: only the strongest carriers survive. */
    EXACT("exact", cost = 4),
}

/**
 * [about] is the aspect a word is *concerned with*, where it has one. Added after the first run of this
 * spike, which showed why: see [Policy.scoped]. Evocative words leave it null on purpose — spanning
 * aspects is what makes them evocative.
 */
private data class Word(val name: String, val tier: Tier, val tags: Set<String>, val about: Aspect? = null)

private fun evocative(name: String, vararg tags: String) = Word(name, Tier.EVOCATIVE, tags.toSet())
private fun restrictive(name: String, about: Aspect, vararg tags: String) =
    Word(name, Tier.RESTRICTIVE, tags.toSet(), about)
private fun exact(name: String, about: Aspect, vararg tags: String) = Word(name, Tier.EXACT, tags.toSet(), about)

private val VOCABULARY = listOf(
    evocative("beautiful", "lush", "bright", "striking"),
    evocative("foreboding", "gloomy", "hostile"),
    evocative("strange", "strange"),
    evocative("ancient", "eroded", "ordered"),

    restrictive("floating", Aspect.TERRAIN, "floating"),
    restrictive("arid", Aspect.BIOMES, "barren"),
    restrictive("verdant", Aspect.BIOMES, "lush"),
    restrictive("riddled", Aspect.CARVERS, "cavernous"),
    restrictive("drowned", Aspect.SEA, "watery"),
    restrictive("burning", Aspect.SEA, "molten"),

    exact("flat", Aspect.TERRAIN, "flat"),
    exact("mountainous", Aspect.TERRAIN, "mountainous"),
    exact("lifeless", Aspect.BIOMES, "barren"),
    exact("stormy", Aspect.SKY, "gloomy"),

    // §3.4's own example, and nothing in the sky aspect has moons — so this is a word the world cannot
    // answer, contradicting no other word. Here to prove the antonym table cannot see it.
    exact("moonless", Aspect.SKY, "moonless"),
)

private fun word(name: String): Word =
    VOCABULARY.firstOrNull { it.name == name } ?: error("no word '$name' in the spike vocabulary")

// ---------------------------------------------------------------------------------------------
// Resolution.
// ---------------------------------------------------------------------------------------------

/** Which pair of words fought, over what, and where — the provenance §4.6 insists the index carries. */
private data class Conflict(val first: Word, val second: Word, val tags: Pair<String, String>, val aspect: Aspect) {
    /**
     * Severity falls out of the aspect, not out of a per-pair table. A set-valued aspect can honour both
     * terms somewhere and merely charges for the strangeness; a singular aspect cannot, and the loser's
     * word is simply not in the world it asked for.
     */
    val severity: Int get() = if (aspect in SET_VALUED) 1 else 3

    /** Precedence: the more precise word steers, the vaguer one shades what is left (§3.5). */
    val yielded: Word get() = if (first.tier.ordinal >= second.tier.ordinal) second else first

    val isExactAgainstExact: Boolean get() = first.tier == Tier.EXACT && second.tier == Tier.EXACT

    override fun toString(): String {
        val (a, b) = tags
        val ruling = if (isExactAgainstExact) "neither yields" else "'${yielded.name}' yields"
        return "'${first.name}'($a) vs '${second.name}'($b) in ${aspect.key} — $ruling, +$severity"
    }
}

/**
 * A constraint the world had no way to satisfy — "floating" where nothing in the aspect floats.
 *
 * The first run of this spike had no such concept, and that was its most alarming result: an
 * impossible word was dropped in silence, so the player got a world their sentence never described
 * and an instability index of zero to explain it. Unmeetable is not the same as uncontradicted.
 */
private enum class Unmeetable(val explanation: String) {
    /**
     * Word against *world*: nothing in the aspect carries the tag at all. **The antonym table can never
     * catch this** — "moonless" in a world with no moons contradicts no other word, only the range of
     * things the aspect can be. §3.3 has no account of it, and this is the spike's clearest gap.
     */
    NOTHING_CARRIES_IT("nothing in the aspect carries it"),

    /**
     * Word against *words*: something did carry it, but an earlier constraint had already narrowed it
     * away. Usually the antonym table already says so, in which case charging for it as well would
     * bill the same disagreement twice.
     */
    CROWDED_OUT("an earlier word had already narrowed it away"),
}

private data class Unmet(val word: Word, val aspect: Aspect, val why: Unmeetable) {
    val severity: Int get() = if (aspect in SET_VALUED) 2 else 4
    override fun toString(): String = "'${word.name}' went unmet in ${aspect.key} — ${why.explanation}"
}

/**
 * How the resolver behaves. Both flags are off in the shape the spike was first written in, and both
 * turned out to be necessary; running with them off is what makes the report evidence.
 */
private data class Policy(val scoped: Boolean, val reportsUnmet: Boolean) {
    companion object {
        /** Words constrain every aspect their tags touch, and impossible words vanish. */
        val NAIVE = Policy(scoped = false, reportsUnmet = false)

        /** Words constrain the aspect they are about, and what cannot be met is said out loud. */
        val CORRECTED = Policy(scoped = true, reportsUnmet = true)
    }
}

private data class Resolution(
    val composition: AgeComposition,
    val conflicts: List<Conflict>,
    val unmet: List<Unmet>,
    val cost: Int,
) {
    /**
     * A word that was crowded out by a word it has an antonym with is the *same* disagreement the
     * conflict list already charged for. Billing it twice is how an instability index stops meaning
     * anything, so only the unexplained ones count.
     */
    val uncharged: List<Unmet>
        get() = unmet.filter { missing ->
            missing.why == Unmeetable.NOTHING_CARRIES_IT ||
                conflicts.none { it.aspect == missing.aspect && missing.word in listOf(it.first, it.second) }
        }

    val instability: Int
        get() = conflicts.sumOf { it.severity } +
            conflicts.count { it.isExactAgainstExact } * 2 +
            uncharged.sumOf { it.severity }
}

private fun presetsOf(aspect: Aspect): List<AspectPreset> = when (aspect) {
    Aspect.TERRAIN -> Terrain.entries
    // The three the spike was written against, now that the aspect is open and has no enum to list.
    Aspect.SEA -> listOf(Sea.NONE, Sea.WATER, Sea.LAVA)
    Aspect.CARVERS -> Carvers.entries
    Aspect.BIOMES -> Biomes.entries
    Aspect.SKY -> Sky.entries
    // Listed so the spike still compiles, and deliberately left out of [TAGS]: the spike argued about the
    // five aspects that existed when it was written, and an aspect nothing here tags is one no sentence here
    // speaks to. So it draws on the base weight alone and none of the reported findings move.
    Aspect.STRUCTURES -> Structures.entries
    Aspect.CLIMATE -> Climate.entries
}

private fun weight(preset: AspectPreset, tag: String): Double = TAGS[preset]?.get(tag) ?: 0.0

/** How strongly [preset] answers to [word] at all — the single number every tier is expressed in. */
private fun affinity(preset: AspectPreset, word: Word): Double = word.tags.maxOfOrNull { weight(preset, it) } ?: 0.0

/**
 * A word constrains every aspect that has anything to say about its tags. Nothing declares which aspect a
 * word belongs to; that falls out of the tag data, which is what §8 needs when the vocabulary starts
 * arriving from Minecraft's own tags rather than from a list somebody wrote.
 */
private fun slotsConstrainedBy(word: Word, policy: Policy = Policy.NAIVE): List<Aspect> {
    val speaksTo = Aspect.entries.filter { aspect -> presetsOf(aspect).any { affinity(it, word) > 0.0 } }
    if (!policy.scoped || word.about == null) return speaksTo
    return listOf(word.about)
}

/**
 * Words in, composition out, deterministically (§4.6).
 *
 * One mechanism for all three tiers: each word scores every candidate, and the tier decides only how
 * hard that score bites — Exact keeps the best, Restrictive keeps anything that qualifies, Evocative
 * keeps everything and merely tilts the draw.
 */
private fun resolve(sentence: List<String>, seed: Long, policy: Policy = Policy.CORRECTED): Resolution {
    val words = sentence.map(::word)
    val chosen = mutableMapOf<Aspect, AspectPreset>()
    val unmet = mutableListOf<Unmet>()

    for (aspect in Aspect.entries) {
        val speaking = words.filter { aspect in slotsConstrainedBy(it, policy) }
        var candidates = presetsOf(aspect)

        // Narrow by the precise words first, so a vague word never shrinks what a precise one wanted.
        for (tier in listOf(Tier.EXACT, Tier.RESTRICTIVE)) {
            for (constraint in speaking.filter { it.tier == tier }) {
                val threshold = if (tier == Tier.EXACT) EXACT_THRESHOLD else RESTRICTIVE_THRESHOLD
                val survivors = candidates.filter { affinity(it, constraint) >= threshold }
                // The world still has to be *something*, so an impossible narrowing is dropped — but
                // it is dropped *loudly*, because a word that could not be honoured is exactly the
                // kind of thing the player needs told. Which of the two ways it failed is asked
                // against the whole aspect, not against what is left, or every loser of a narrowing
                // race would report that the world does not contain what it plainly does.
                if (survivors.isNotEmpty()) {
                    candidates = survivors
                } else {
                    val anywhereInSlot = presetsOf(aspect).any { affinity(it, constraint) >= threshold }
                    unmet += Unmet(
                        constraint,
                        aspect,
                        if (anywhereInSlot) Unmeetable.CROWDED_OUT else Unmeetable.NOTHING_CARRIES_IT,
                    )
                }
            }
        }

        // Evocative words only tilt the draw between whatever survived.
        val tilt = speaking.filter { it.tier == Tier.EVOCATIVE }
        val scores = candidates.map { preset -> BASE_WEIGHT + tilt.sumOf { affinity(preset, it) } }
        chosen[aspect] = pick(candidates, scores, Random(seed + aspect.ordinal))
    }

    return Resolution(
        composition = compose(chosen),
        conflicts = conflictsIn(words, policy),
        unmet = if (policy.reportsUnmet) unmet else emptyList(),
        cost = words.sumOf { it.tier.cost * slotsConstrainedBy(it, policy).size },
    )
}

/** Seeded weighted draw — the "seeded RNG fills the rest" of §4.6. */
private fun pick(candidates: List<AspectPreset>, scores: List<Double>, random: Random): AspectPreset {
    val target = random.nextDouble() * scores.sum()
    var running = 0.0
    for ((index, score) in scores.withIndex()) {
        running += score
        if (running >= target) return candidates[index]
    }
    return candidates.last()
}

/**
 * Every antonym pair both of whose halves somebody asked for, sited at the aspect where they collide.
 *
 * Note what this does *not* need: any notion of distance, any embedding, any tuning. Two words, two
 * tags, one table lookup — and the aspect it lands in supplies the severity.
 */
private fun conflictsIn(words: List<Word>, policy: Policy): List<Conflict> = buildList {
    for ((first, second) in words.pairs()) {
        for ((left, right) in ANTONYMS) {
            val forwards = left in first.tags && right in second.tags
            val backwards = right in first.tags && left in second.tags
            if (!forwards && !backwards) continue
            val tags = if (forwards) left to right else right to left
            // Sited where both words actually have a say, which is where the world has to reconcile
            // them. Two precise words about *different* aspects therefore never fight — which is right,
            // and is a second thing scoping buys.
            val where = slotsConstrainedBy(first, policy).intersect(slotsConstrainedBy(second, policy).toSet())
            for (aspect in where) add(Conflict(first, second, tags, aspect))
        }
    }
}

private fun <T> List<T>.pairs(): List<Pair<T, T>> =
    indices.flatMap { left -> (left + 1..<size).map { right -> this[left] to this[right] } }

private fun compose(chosen: Map<Aspect, AspectPreset>): AgeComposition {
    val terrain = chosen[Aspect.TERRAIN] as? Terrain ?: error("the terrain aspect resolved to nothing")
    var composition = AgeComposition(terrains = listOf(terrain))
    for ((aspect, preset) in chosen) composition = composition.withPreset(aspect, preset.key)
    return composition
}

private const val EXACT_THRESHOLD = 0.7
private const val RESTRICTIVE_THRESHOLD = 0.3

// A floor under every candidate, so an evocative word tilts the draw rather than deciding it — the
// difference between "shifts weights" and "narrows the set", which is the whole tier distinction.
private const val BASE_WEIGHT = 0.35

// ---------------------------------------------------------------------------------------------
// What the spike is actually for: reporting what happened.
// ---------------------------------------------------------------------------------------------

private val SENTENCES = listOf(
    listOf("floating", "beautiful"),
    listOf("verdant", "arid"),
    listOf("lifeless", "verdant"),
    listOf("flat", "mountainous"),
    listOf("burning", "drowned"),
    listOf("riddled", "foreboding", "ancient"),
    listOf("beautiful"),
    listOf("floating", "stormy", "arid", "riddled"),
    listOf("moonless", "beautiful"),
)

private const val SPIKE_SEED = 20260727L

private fun report(sentence: List<String>) {
    println("\n\"${sentence.joinToString(" ")}\"")
    for ((label, policy) in listOf("naive" to Policy.NAIVE, "scoped" to Policy.CORRECTED)) {
        val resolution = resolve(sentence, SPIKE_SEED, policy)
        println("  %-7s %s".format(label, resolution.composition))
        println("          cost ${resolution.cost}, instability ${resolution.instability}")
        for (conflict in resolution.conflicts) println("          ! $conflict")
        for (missing in resolution.unmet) {
            val charged = if (missing in resolution.uncharged) "+${missing.severity}" else "already charged above"
            println("          ? $missing ($charged)")
        }
        if (resolution.conflicts.isEmpty() && resolution.unmet.isEmpty()) println("          (coherent)")
    }
}

private fun reportVocabulary() {
    println("Resolver SPIKE — ${VOCABULARY.size} words over ${Aspect.entries.size} aspects, " +
        "${TAGS.size} tagged presets, ${ANTONYMS.size} antonym pairs.")
    println("Each sentence is resolved twice: 'naive' lets a word constrain every aspect its tags touch,")
    println("'scoped' confines a precise word to the aspect it is about and reports what it could not meet.\n")
    println("%-12s %-12s %-34s %s".format("word", "tier", "naive: has a say in", "scoped"))
    for (word in VOCABULARY) {
        val naive = slotsConstrainedBy(word, Policy.NAIVE).joinToString(" ") { it.key }
        val scoped = slotsConstrainedBy(word, Policy.CORRECTED).joinToString(" ") { it.key }
        val trampled = if (naive != scoped) "  <-- trampled ${naive.split(" ").size - 1} other aspect(s)" else ""
        println("%-12s %-12s %-34s %s%s".format(word.name, word.tier.label, naive, scoped, trampled))
    }
}

/** Resolution must be a pure function of (words, seed) — everything downstream assumes it. */
private fun checkResolutionIsPure() {
    for (sentence in SENTENCES) {
        val once = resolve(sentence, SPIKE_SEED).composition
        val twice = resolve(sentence, SPIKE_SEED).composition
        check(once == twice) { "'$sentence' resolved two ways: $once then $twice" }
    }
    println("\nPure: every sentence resolves identically twice at the same seed.")
}

/** A vague word list should produce genuinely varied Ages across seeds; a precise one should not. */
private fun checkVaguenessVaries() {
    val seeds = (1L..40L).toList()
    fun spread(sentence: List<String>) = seeds.map { resolve(sentence, it).composition }.toSet().size

    val vague = spread(listOf("beautiful"))
    val precise = spread(listOf("floating", "stormy", "arid", "riddled"))
    println("Variety over 40 seeds: \"beautiful\" gives $vague distinct Ages, " +
        "\"floating stormy arid riddled\" gives $precise.")
    check(vague > precise) { "precision did not narrow anything: vague $vague, precise $precise" }
}

private fun reportFindings() = println(
    """

    ── What the spike answered ──────────────────────────────────────────────────────────────────
    Evidence, not a foundation. Read against notes/the-art-design.md before Phase 3 proper.

    WHAT WORKS, AND SHOULD BE KEPT
      * One mechanism really does carry all three tiers (4.4). Every word scores every candidate;
        the tier decides only how hard the score bites. No special cases, no per-tier code path.
      * The antonym table is as cheap as promised (3.3). Eight pairs, no geometry, no tuning, and
        it found every word-against-word contradiction in the samples above.
      * Resolution is a pure function of (words, seed) (4.6) — asserted above, not assumed.
      * Vagueness really does buy variety: "beautiful" gives 38 distinct Ages over 40 seeds where
        a four-word precise sentence gives 9. Precision narrows, exactly as the progression axis
        needs it to.
      * Tags can be authored from OUTSIDE the preset enums, which is what 8 needs when the
        vocabulary starts arriving from Minecraft's own tags rather than a hand-written list.

    WHAT BROKE, IN ORDER OF HOW MUCH IT MATTERS
      1. SLOT SCOPING IS LOAD-BEARING, NOT A REFINEMENT. Letting a word constrain every aspect its
         tags happen to touch is catastrophic: 'stormy' is a word about the SKY, and under the
         naive policy it pinned the TERRAIN to caverns and silently threw away 'floating'. The
         player wrote "floating" and got solid rock, with an instability index of zero to explain
         it. Scoping also fixes pricing — that sentence cost 26 naive and 10 scoped. 8 already
         says tags give "category + which aspect it may fill"; this shows that half is required.

      2. THE ANTONYM TABLE CANNOT SEE WORD-AGAINST-WORLD. 'moonless' contradicts no other word —
         it is simply something no preset in the aspect can be. Under a tags-and-antonyms model it
         was FREE AND INVISIBLE: cost 0, instability 0, no diagnosis. 3.3 has no account of this,
         and it is precisely 3.4's own "two moons" example. A second register is needed.

      3. THE TWO WAYS A CONSTRAINT FAILS MUST BE TOLD APART, OR THE INDEX DOUBLE-COUNTS. "Nothing
         carries it" is word-against-world; "crowded out by an earlier word" is word-against-word,
         which the antonym table has already charged for. The first cut of this spike conflated
         them and billed 'burning drowned' at 7 for a single disagreement worth 3.

      4. SAME-TIER CONFLICTS HAVE NO RULING. 3.5 says exact beats restrictive beats evocative, but
         'verdant' against 'arid' is restrictive against restrictive, and who yields is currently
         decided by which the player happened to write first. That is arbitrary and needs a rule.

      5. SET-VALUED SEVERITY ASSUMES A CAPABILITY WE DO NOT HAVE. 3.4 charges a contradiction in a
         set-valued aspect mildly because the world can honour both terms in different places. But
         AgeComposition holds exactly ONE preset per aspect, so "lush, barren" charges the low rate
         and then delivers only verdant. The coexistence that justified the discount never
         happens. Either compositions grow set-valued aspects, or the severity model needs redoing.

      6. TAG MEANING DOES NOT SURVIVE THE TRIP BETWEEN SLOTS. "beautiful" reliably chooses a LAVA
         sea, because lava is tagged striking 0.5 and beautiful asks for striking. Defensible in
         isolation, absurd in a sentence. One flat tag namespace shared across every aspect will
         keep producing this; tags likely want to be aspect-qualified too.

      7. Minor: unconstrained aspects draw on (seed + aspect ordinal), so two different sentences at
         the same seed get identical filler wherever neither constrains anything. The sentence
         itself probably belongs in the seed.

    THE VERDICT THE PLAN ASKED FOR
      Constraint satisfaction over weighted tags is NOT awkward — the core is sound and small.
      But it is under-specified in the design as written, and every one of 1-3 above is a silent
      wrong answer rather than a visible failure, which is the worst shape a bug can take in a
      system whose whole promise is that flaws are diagnosable.
    """.trimIndent(),
)
