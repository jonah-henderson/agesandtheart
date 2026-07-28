package co.voik.agesandtheart.preview

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.slot.Dressing
import co.voik.agesandtheart.age.slot.Medium
import co.voik.agesandtheart.age.slot.Share
import co.voik.agesandtheart.age.slot.Slot
import co.voik.agesandtheart.age.word.Resolution
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.Tier
import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Constraint
import co.voik.agesandtheart.age.word.grammar.Group
import co.voik.agesandtheart.age.word.grammar.Scope
import co.voik.agesandtheart.age.word.grammar.Sentence
import co.voik.agesandtheart.age.word.Word
import net.minecraft.SharedConstants
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap

/**
 * Asks whether the resolver keeps the promises the design makes on its behalf.
 *
 * Every check below is a sentence from `notes/the-art-design.md` turned into an assertion, and most of
 * them are guarding against a specific way the Phase 3 spike went wrong. It is the acceptance test for
 * Phase 3b: *same words and seed give the same Age, "lush barren" yields both biome families with a
 * diagnosable reason, and a vague sentence varies across seeds.*
 *
 * Offline, reading the shipped vocabulary through [shippedData] — so it exercises the real corpus rather
 * than a toy one, and a badly-judged tag weight shows up here rather than in a world. The corpus now
 * includes §8's words derived from the game's own registries, which is what the bootstrap is for.
 */
fun main() {
    SharedConstants.tryDetectVersion()
    Bootstrap.bootStrap()
    val vocabulary = Vocabulary.load(shippedData())
    check(vocabulary.problems.isEmpty()) { "vocabulary problems: ${vocabulary.problems}" }

    resolutionIsPure(vocabulary)
    wordOrderDecidesNothing(vocabulary)
    oneWordIsNeverIncoherent(vocabulary)
    aResolvedAgeRoundTripsThroughCompose(vocabulary)
    lushAndBarrenYieldBothFamilies(vocabulary)
    aSkyCannotDivide(vocabulary)
    anUnbackedWordIsReportedNotDropped(vocabulary)
    vaguenessVariesAndPrecisionNarrows(vocabulary)
    precisionCostsMore(vocabulary)
    anUnaskedDrawPrefersTheOrdinary(vocabulary)
    aStrongClaimTakesMoreGround(vocabulary)
    harmonyIsFreeAndTheDressingIsNowTheReluctantOne(vocabulary)
    anExactWordAdmitsNoCompany(vocabulary)
    aDerivedWordNamesItsReferent(vocabulary)
    aMaterialSteersWithoutChoosing(vocabulary)
    joiningTwoMaterialsMinglesThemAndCostsNothing(vocabulary)

    println("Resolver: all sixteen properties hold over the shipped ${vocabulary.words.size}-word vocabulary.")
}

/**
 * The same sentence at the same seed resolves identically, every time.
 *
 * Everything downstream leans on this: an Age is rebuilt from its recipe on every open, so a resolver
 * that wandered would be a world that changed under the people living in it.
 */
private fun resolutionIsPure(vocabulary: Vocabulary) {
    for (sentence in SENTENCES) {
        val once = resolve(vocabulary, sentence)
        val twice = resolve(vocabulary, sentence)
        check(once.composition == twice.composition) {
            "\"$sentence\" resolved two ways: ${once.composition} then ${twice.composition}"
        }
        check(once.instability.flaws == twice.instability.flaws) {
            "\"$sentence\" found different flaws the second time: ${twice.instability.flaws}"
        }
    }
}

/**
 * Reversing a sentence changes nothing.
 *
 * §3.5 rejected "later word wins" deliberately — page order as an override would make equal-precision
 * contradiction completely safe and largely evaporate the risk half of the precision axis. So word order
 * must not decide who yields, and it must not leak into the seed either.
 */
private fun wordOrderDecidesNothing(vocabulary: Vocabulary) {
    for (sentence in SENTENCES) {
        val forwards = resolve(vocabulary, sentence)
        val backwards = resolve(vocabulary, sentence.split(" ").reversed().joinToString(" "))
        check(forwards.composition == backwards.composition) {
            "\"$sentence\" reversed gave ${backwards.composition}, not ${forwards.composition}"
        }
        check(forwards.instability.index == backwards.instability.index) {
            "\"$sentence\" reversed cost a different instability: ${backwards.instability.index}"
        }
    }
}

/**
 * A single word can never make an incoherent Age.
 *
 * There is nothing for it to disagree with, and `:common:vocabularycheck` has already established that
 * something in the world can satisfy it — so any flaw here would be the resolver inventing one.
 */
private fun oneWordIsNeverIncoherent(vocabulary: Vocabulary) {
    for (word in vocabulary.words) {
        val resolution = Resolver.resolve(vocabulary, Sentence.flat(listOf(word)), SAMPLE_SEED)
        check(resolution.instability.isCoherent) {
            "'${word.name}' alone was charged ${resolution.instability.index}: ${resolution.instability.flaws}"
        }
    }
}

/**
 * What the resolver produced can be written down and read back by `/age compose`.
 *
 * A free harness, and the reason it is free is worth keeping: `AgeComposition.toString` is exactly what
 * `parse` reads, so a resolved Age can be pasted into the composer and diffed against the written one.
 */
private fun aResolvedAgeRoundTripsThroughCompose(vocabulary: Vocabulary) {
    for (sentence in SENTENCES) {
        val composition = resolve(vocabulary, sentence).composition
        val spelling = composition.toString()
        val read = AgeComposition.parse(spelling).getOrThrow()
        check(read == composition) { "\"$sentence\" resolved to '$spelling', which reads back as '$read'" }
    }
}

/**
 * **The phase's acceptance test.** "Lush barren" yields both biome families, and says why.
 *
 * This is the sentence that could not pass before 3a: a slot holding one preset had to pick a side, so a
 * writer got one of the two words they wrote and silence about the other. Now the dressing slot divides,
 * both terms are in the world, and the instability index names the pair that made it happen.
 */
private fun lushAndBarrenYieldBothFamilies(vocabulary: Vocabulary) {
    val resolution = resolve(vocabulary, "verdant lifeless")
    val dressings = resolution.composition.dressings
    check(dressings.size == 2) { "\"verdant lifeless\" gave ${dressings.size} dressing(s): $dressings" }
    check(Dressing.BARE_ROCK in dressings) { "the barren half is missing: $dressings" }
    check(dressings.any { it == Dressing.VERDANT || it == Dressing.OVERWORLD }) {
        "the lush half is missing: $dressings"
    }

    val division = resolution.instability.flaws.firstOrNull { it.register == Register.DIVISION }
    checkNotNull(division) { "the world divided but nothing was charged for it: ${resolution.instability.flaws}" }
    check(division.words.containsAll(listOf("verdant", "lifeless"))) {
        "the division does not name the pair that caused it: ${division.words}"
    }
    check(division.slot == Slot.DRESSING) { "the division was sited in ${division.slot}, not the dressing" }
    check(division.tags == listOf("barren", "lush") || division.tags == listOf("lush", "barren")) {
        "the division does not name the tags that disagreed: ${division.tags}"
    }
    // "You wrote opposites" and "the world tore in two to do it" are different facts about the sentence
    // (§3.3), so both are charged — gently, because punishment for incoherence is deliberately not harsh.
    check(resolution.instability.flaws.any { it.register == Register.TENSION }) {
        "a tension the world honoured went unremarked: ${resolution.instability.flaws}"
    }
}

/**
 * The sky cannot divide, so one of two sky words is displaced — the harsher register, and the reason it
 * exists.
 *
 * A sky is a *dimension type*, registered once for the dimension, so two of them in one world is not a
 * design preference but a technical impossibility (§3.4). This is the one slot where a contradiction
 * genuinely cannot be honoured.
 */
private fun aSkyCannotDivide(vocabulary: Vocabulary) {
    val resolution = resolve(vocabulary, "stormy clear")
    // That the sky is one sky needs no assertion — `AgeComposition.sky` is a single field where every
    // positional slot is a list, which is §3.4's "technically impossible" made structural. What wants
    // checking is that the word which lost is *said out loud* rather than quietly absent.
    val displaced = resolution.instability.flaws.firstOrNull { it.register == Register.DISPLACED }
    checkNotNull(displaced) { "a word lost the sky and nothing said so: ${resolution.instability.flaws}" }
    check(displaced.slot == Slot.SKY) { "the loss was sited in ${displaced.slot}, not the sky" }
    check(displaced.words.containsAll(listOf("stormy", "clear"))) {
        "the loss does not name both words: ${displaced.words}"
    }
    // Two exact words against each other is the design's dangerous case, so it must not come cheap.
    check(resolution.instability.index >= Register.DISPLACED.charge(Tier.EXACT)) {
        "exact against exact in a singular slot cost only ${resolution.instability.index}"
    }
}

/**
 * A word the world cannot satisfy resolves as vacuous but is **reported**.
 *
 * §3.3's one hard requirement, and the spike's second-worst finding: "moonless" contradicted no other
 * word, so an antonym table saw nothing, and the word came out free, silent and undiagnosed. The word
 * used here is invented rather than shipped — `:common:vocabularycheck` exists to stop one like it
 * shipping, and this checks what happens if one ever does.
 */
private fun anUnbackedWordIsReportedNotDropped(vocabulary: Vocabulary) {
    val moonless = Word(
        ResourceLocation.fromNamespaceAndPath("test", "moonless"),
        Tier.EXACT,
        setOf(Slot.SKY),
        mapOf("moonless" to 1.0),
    )
    val resolution = Resolver.resolve(vocabulary, Sentence.flat(listOf(moonless)), SAMPLE_SEED)
    val unbacked = resolution.instability.flaws.firstOrNull { it.register == Register.UNBACKED }
    checkNotNull(unbacked) { "an impossible word passed in silence, which is the one thing forbidden" }
    check(unbacked.words == listOf("moonless")) { "the report does not name the word: ${unbacked.words}" }
    check(resolution.instability.index > 0) { "an impossible word cost nothing at all" }
}

/**
 * Vagueness buys variety; precision takes it away.
 *
 * The progression axis, measured rather than assumed: if a vague sentence did not vary, imprecision would
 * be strictly worse than precision and the cheap half of the design would be dead content.
 */
private fun vaguenessVariesAndPrecisionNarrows(vocabulary: Vocabulary) {
    val vague = spread(vocabulary, "beautiful")
    val precise = spread(vocabulary, "floating stormy arid riddled")
    check(vague > precise) { "precision narrowed nothing: vague $vague, precise $precise" }
    check(precise > 0) { "a precise sentence resolved to nothing at all" }
    println("  \"beautiful\" gives $vague distinct Ages over $SEEDS_SAMPLED seeds; four precise words give $precise.")
}

/** How many distinct Ages a sentence gives across many seeds. */
private fun spread(vocabulary: Vocabulary, sentence: String): Int =
    (1L..SEEDS_SAMPLED).map { seed -> resolve(vocabulary, sentence, seed).composition }.toSet().size

/**
 * The exact word costs more than its restrictive synonym.
 *
 * `burning` and `molten` name the same tag at different rungs of the ladder, which is §3.3's synonyms-as-a-
 * lever and §4.4's value-derived cost in one pair of files. If they priced the same, the ladder would be
 * decoration.
 */
private fun precisionCostsMore(vocabulary: Vocabulary) {
    val vague = resolve(vocabulary, "burning").cost
    val exact = resolve(vocabulary, "molten").cost
    check(exact > vague) { "'molten' cost $exact and 'burning' cost $vague, so precision is free" }
}

/**
 * A sentence that said nothing about the sea is rarely handed one of lava.
 *
 * This is what the readiness prior is for, and the check is here because without it nothing would notice
 * its absence: an unconstrained slot drawn uniformly gives lava one time in three, which reads as the
 * generator ignoring a plain reading of the sentence rather than as the generator being wild. Precision
 * still reaches it — `molten` and `burning` both do, and [precisionCostsMore] just proved they resolve.
 */
private fun anUnaskedDrawPrefersTheOrdinary(vocabulary: Vocabulary) {
    val molten = (1L..SEEDS_SAMPLED).count { seed ->
        Medium.LAVA in resolve(vocabulary, "homely", seed).composition.mediums
    }
    check(molten <= SEEDS_SAMPLED / MOST_UNASKED_LAVA) {
        "\"homely\" gave a sea of lava $molten times in $SEEDS_SAMPLED — readiness is not biting"
    }
    println("  \"homely\" draws a sea of lava $molten times in $SEEDS_SAMPLED seeds.")
}

/**
 * The preset a word claims strongly takes more ground than one it claims weakly.
 *
 * Jonah's requirement, and the point of shares: "a word strongly associated with overworld paired with a
 * word weakly associated with pillars" should give mostly overworld with scarce pillars, not half of each.
 * Checked at the recipe rather than by counting columns — `:common:regionsharecheck` is what proves a share
 * turns into ground.
 */
private fun aStrongClaimTakesMoreGround(vocabulary: Vocabulary) {
    // The ladder itself, which is where "strongly associated" turns into ground. Deliberately checked apart
    // from any sentence: whether a given Age divides unevenly depends on which presets were drawn, but what
    // a claim ratio *means* must not.
    check(Share.nearest(ratio = 1.0) == Share.DOMINANT) { "an equal claim should take an equal share" }
    check(Share.nearest(ratio = 0.9 / 0.9) == Share.DOMINANT) { "two strong claims should divide evenly" }
    check(Share.nearest(ratio = 0.3 / 0.9) == Share.COMMON) { "a third of a claim should be a large minority" }
    check(Share.nearest(ratio = 0.06 / 0.9) == Share.SCATTERED) { "a fifteenth of a claim should be islands" }
    check(Share.nearest(ratio = 0.01 / 0.9) == Share.RARE) { "a hundredth of a claim should be somewhere out there" }

    // And no vector of shares may leave a word effectively absent — Jonah's floor, deliberately low so that
    // rare means rare and finding it is the reward.
    val floored = Share.findable(listOf(Share.DOMINANT, Share.DOMINANT, Share.RARE))
    val smallest = floored.minOf { it.weight } / floored.sumOf { it.weight }
    check(smallest >= Share.LEAST_SHARE_OF_A_WORLD) {
        "three territories left the smallest at %.2f%% of the world".format(smallest * PERCENT)
    }

    // Then the behaviour, over seeds, because which preset is drawn varies: whenever bare rock (pinned
    // exactly, and the strongest claim there is) shares a world, it must never be the lesser territory — and
    // an uneven division has to actually happen sometimes, or shares would be decoration.
    var uneven = 0
    for (seed in 1L..HARMONY_SEEDS) {
        val composition = resolve(vocabulary, "verdant lifeless", seed).composition
        val shares = composition.sharesOf(Slot.DRESSING)
        val pinned = composition.dressings.indexOf(Dressing.BARE_ROCK)
        if (pinned >= 0) {
            check(shares[pinned].weight >= shares.maxOf { it.weight }) {
                "the exactly-pinned dressing took less ground than its neighbour: ${composition.dressings} $shares"
            }
        }
        if (shares.toSet().size > 1) {
            uneven++
            // The spelling of an uneven division has to survive the trip, which is what keeps `/age list`
            // output pasteable into `/age compose`.
            val spelling = composition.toString()
            check(":" in spelling) { "an uneven division should say so: '$spelling'" }
            check(AgeComposition.parse(spelling).getOrThrow() == composition) {
                "'$spelling' does not read back as what wrote it"
            }
        }
    }
    check(uneven > 0) { "no seed divided \"verdant lifeless\" unevenly, so shares never bite" }
    println("  \"verdant lifeless\" divides unevenly at $uneven of $HARMONY_SEEDS seeds.")
}

/**
 * A sentence that merely *likes* several things may get several of them, is charged nothing for it, and
 * **rarely gets a seam for its trouble**.
 *
 * Two halves. Harmony is free: "beautiful" reaching a beach and a field of flowers is not incoherent, it is
 * the word doing its job, so no flaw is charged.
 *
 * The second half **inverted on 2026-07-27** and is the more interesting one. This used to assert that
 * dressings double up *more* than landforms, on the reasoning that spreading several kinds of place across
 * a map is what biomes do anyway. That reasoning was an argument for biomes doing it, borrowed to justify
 * the dressing slot dividing — see design §3.4, "what regions are for". Now that variety belongs to biomes,
 * **a dressing seam is reserved for two policies that cannot share a climate table**, so dressing should be
 * the *rarest* slot to double up, not the commonest.
 *
 * Kept as a comparison rather than a hard count because the numbers are taste, and the ordering is the
 * design claim: whatever the tuning, a second dressing must be rarer than a second landform, and a second
 * landform is already meant to be a thing you remember.
 */
private fun harmonyIsFreeAndTheDressingIsNowTheReluctantOne(vocabulary: Vocabulary) {
    var dressingsDoubled = 0
    var landformsDoubled = 0
    for (seed in 1L..HARMONY_SEEDS) {
        val resolution = resolve(vocabulary, "beautiful", seed)
        val composition = resolution.composition
        if (composition.dressings.size > 1) dressingsDoubled++
        if (composition.landforms.size > 1) landformsDoubled++
        check(resolution.instability.isCoherent) {
            "\"beautiful\" at seed $seed was charged ${resolution.instability.index}: " +
                "${resolution.instability.flaws} — liking two things is not a contradiction"
        }
    }
    check(dressingsDoubled < landformsDoubled) {
        "dressings doubled up $dressingsDoubled times against landforms' $landformsDoubled, but a dressing " +
            "seam is now the rarest thing in an Age — variety belongs to biomes (design §3.4)"
    }
    println(
        "  \"beautiful\" over $HARMONY_SEEDS seeds: two dressings $dressingsDoubled times, " +
            "two landforms $landformsDoubled times, no instability either way.",
    )
}

/**
 * An exact word admits no company at all — it *pins one value* (§4.4).
 *
 * The line between generosity and disobedience: a writer who said something precisely gets that thing and
 * nothing beside it, however much the rest of the sentence might have liked a neighbour.
 */
private fun anExactWordAdmitsNoCompany(vocabulary: Vocabulary) {
    for (seed in 1L..HARMONY_SEEDS) {
        val composition = resolve(vocabulary, "beautiful lifeless", seed).composition
        check(composition.dressings == listOf(Dressing.BARE_ROCK)) {
            "an exact dressing came out as ${composition.dressings} at seed $seed"
        }
    }
}

/**
 * A word derived from the registry gives you exactly the thing it names — §8's whole promise, end to end.
 *
 * Three properties in one, because they only mean anything together. The word **exists** at all, which is
 * derivation running. Naming it **gets it**, at every seed, which is what "precision can reach anything"
 * cashes out as. And naming it **beside a vaguer word takes the world**, which is the part that needed
 * [Word.pullOn]: a derived word carries no tags, so scored on tag weights alone it would have claimed
 * nothing and been given the *scarce* territory while `beautiful` took the ground it was named against.
 *
 * `lava` is the one to test on rather than `water`, since it is the derived word most likely to collide
 * with the curated pool's opinions — `beautiful` pushes hard against `hostile`, which is exactly the
 * tension a named word has to win.
 */
private fun aDerivedWordNamesItsReferent(vocabulary: Vocabulary) {
    val lava = vocabulary.word("lava") ?: error("no derived word 'lava' — is derivation running?")
    check(lava.tier == Tier.EXACT) { "a derived word must be exact, not ${lava.tier.key}" }
    check(lava.names == Medium.LAVA.key) { "'lava' names ${lava.names}, not ${Medium.LAVA.key}" }
    check(vocabulary.word("minecraft:lava") == lava) { "a derived word must also answer to its full id" }

    for (seed in 0L..<SEEDS_SAMPLED) {
        val alone = resolve(vocabulary, "lava", seed).composition
        check(alone.mediums == listOf(Medium.LAVA)) {
            "'lava' gave ${alone.mediums} at seed $seed, and an exact word pins one value"
        }
        val contested = resolve(vocabulary, "beautiful lava", seed).composition
        check(contested.mediums.first() == Medium.LAVA) {
            "'beautiful lava' let ${contested.mediums.first()} take the widest share at seed $seed, " +
                "so naming a thing outright is claiming it less hard than merely liking one"
        }
    }
}

/**
 * A **material** steers the preset that was chosen without choosing it — design §3.2, and the three ways
 * that could go quietly wrong.
 *
 * `basalt` sets `dressing.stone` and says nothing else. So: it must **not narrow** the dressing (it has no
 * carriers, and the naive reading of an empty carrier set is "unbacked", which would report a perfectly
 * good word as a content bug); it must **not suppress harmony** despite being exact, since it expressed no
 * view on how many kinds of place the Age holds; and it must **not go silent** when the dressing it landed
 * on cannot wear it, which is the `overworld` case.
 *
 * The last is the one worth a check rather than an argument: vanilla's overworld palette is a rule tree we
 * do not own, so there is nothing to substitute a stone into. That has to charge rather than no-op.
 */
private fun aMaterialSteersWithoutChoosing(vocabulary: Vocabulary) {
    val basalt = vocabulary.word("basalt") ?: error("no word 'basalt' — is the material hook wired?")
    check(basalt.sets == mapOf(Dressing.STONE.name to "minecraft:blackstone")) {
        "'basalt' sets ${basalt.sets}, which is not the material it is for"
    }
    check(!basalt.constrainsPresets) { "a word that only sets a parameter must not narrow presets" }

    var sawADressingThatIgnoresMaterials = false
    var sawADressingThatAppliesMaterials = false
    for (seed in 0L..<SEEDS_SAMPLED) {
        val resolution = resolve(vocabulary, "basalt", seed)
        val composition = resolution.composition
        check(composition.options.of(Slot.DRESSING).chosen[Dressing.STONE.name] == listOf("minecraft:blackstone")) {
            "'basalt' did not set the stone at seed $seed: ${composition.options.of(Slot.DRESSING)}"
        }
        check(composition.unknownOptions.isEmpty()) {
            "'basalt' set an option no preset understands at seed $seed: ${composition.unknownOptions}"
        }
        // Every dressing ignores it, and the word had nothing to show for itself: that must be charged.
        if (composition.dressings.all { it.ignoresMaterial }) {
            sawADressingThatIgnoresMaterials = true
            check(resolution.instability.flaws.any { it.register == Register.UNBACKED }) {
                "'basalt' went unhonoured on ${composition.dressings} at seed $seed and was charged nothing"
            }
        } else {
            sawADressingThatAppliesMaterials = true
            check(resolution.instability.isCoherent) {
                "'basalt' was charged for landing somewhere that can wear it, at seed $seed"
            }
        }
    }
    // The tilt toward a preset that can honour what was asked (`Resolver.capabilityFactor`) is *meant* to
    // make the second case rare — it went from half of all seeds to a handful once the bonus became a
    // factor — so requiring both to appear would fail on an improvement. What must hold is that the common
    // case is the working one; the charge is asserted above wherever the rare case does turn up.
    check(sawADressingThatAppliesMaterials) {
        "over $SEEDS_SAMPLED seeds 'basalt' never landed on a dressing that can wear a material at all"
    }
}

/** The sentence spelled the way a writer would say it, resolved. */
private fun resolve(vocabulary: Vocabulary, sentence: String, seed: Long = SAMPLE_SEED): Resolution {
    val words = sentence.split(" ").filter(String::isNotBlank).map { name ->
        vocabulary.word(name) ?: error("the shipped vocabulary has no word '$name'")
    }
    // Flat, so every property below still asks what it always asked: these are sentences without
    // structure, which is what a book was before the grammar existed. Structure has its own check.
    return Resolver.resolve(vocabulary, Sentence.flat(words), seed)
}

/**
 * Sentences chosen to cover the shapes a sentence can take: coherent, contradictory in a slot that can
 * divide, contradictory in one that cannot, precise, vague, and a mixture of tiers.
 */
private val SENTENCES = listOf(
    "beautiful floating",
    "verdant lifeless",
    "flat towering",
    "burning drowned",
    "riddled foreboding ancient",
    "beautiful",
    "floating stormy arid riddled",
    "savage wondrous colossal",
    "homely open",
    "worn arid clear",
)

private const val SAMPLE_SEED = 20260727L
private const val SEEDS_SAMPLED = 40L

// Enough seeds that a slot's appetite for company shows up as a rate rather than as an accident.
private const val HARMONY_SEEDS = 60L

private const val PERCENT = 100.0

// One in five is generous; uniform would be one in three, and something under a tenth is what the tuning
// actually gives. The point of the bound is to catch readiness being ignored, not to pin a number.
private const val MOST_UNASKED_LAVA = 5

/**
 * **The conjunction**, and the thing 3c left waiting: joining two claims on one parameter keeps both, and
 * charges nothing.
 *
 * The asymmetry is what matters, so both halves are asserted together. Unjoined, `blackstone tuff` is two
 * answers to a question that has room for one — the seed picks and the loser is charged as displaced.
 * Joined, it is one rock made of both, which `Options` has been able to hold since 3a and `Palette.mingled`
 * has been able to paint for just as long; the conjunction is only the wire between them.
 *
 * If this ever passes with juxtaposition also mingling, `and` has stopped meaning anything — that is the
 * failure to watch for, not a crash.
 */
private fun joiningTwoMaterialsMinglesThemAndCostsNothing(vocabulary: Vocabulary) {
    val verdant = vocabulary.word("verdant") ?: error("the shipped vocabulary lost 'verdant'")
    fun material(name: String, block: String) = Word(
        ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name),
        Tier.EXACT,
        setOf(Slot.DRESSING),
        emptyMap(),
        null,
        mapOf(Dressing.STONE.name to block),
    )

    val dressing = Constraint(verdant, Scope.Confined(setOf(Slot.DRESSING)))
    val first = material("firststone", "minecraft:blackstone")
    val second = material("secondstone", "minecraft:tuff")
    fun sentence(group: Group?) = Sentence(
        listOf(
            dressing,
            Constraint(first, Scope.Confined(setOf(Slot.DRESSING)), group = group),
            Constraint(second, Scope.Confined(setOf(Slot.DRESSING)), group = group),
        ),
    )

    val apart = Resolver.resolve(vocabulary, sentence(group = null), SAMPLE_SEED)
    val heldApart = apart.composition.optionsFor(Slot.DRESSING, 0).allOf(Dressing.STONE)
    check(heldApart.size == 1) { "unjoined materials did not contend: they gave $heldApart" }
    check(apart.instability.flaws.any { it.register == Register.DISPLACED }) {
        "a material lost the argument and was not charged for it: ${apart.instability.flaws}"
    }

    val joined = Resolver.resolve(vocabulary, sentence(group = Group(0)), SAMPLE_SEED)
    val mingled = joined.composition.optionsFor(Slot.DRESSING, 0).allOf(Dressing.STONE)
    check(mingled.size == 2) { "joined materials did not mingle: they gave $mingled" }
    check(joined.instability.flaws.none { it.register == Register.DISPLACED }) {
        "the conjunction charged for harmony: ${joined.instability.flaws}"
    }
    check(joined.instability.isCoherent) { "joining two materials made an incoherent Age" }
}
