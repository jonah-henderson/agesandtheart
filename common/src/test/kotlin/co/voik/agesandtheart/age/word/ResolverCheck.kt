package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Carvers
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Density
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.aspect.Population
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Share
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.word.grammar.Constraint
import co.voik.agesandtheart.age.word.grammar.Group
import co.voik.agesandtheart.age.word.grammar.Scope
import co.voik.agesandtheart.age.word.grammar.Sentence
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.resources.ResourceLocation

/**
 * Asks whether the resolver keeps the promises `notes/the-art-design.md` makes on its behalf — each check
 * below is one of them turned into an assertion.
 *
 * Offline, reading the **shipped** vocabulary through [MinecraftRegistries.shippedData], so a
 * badly-judged tag weight shows up here rather than in a world. The corpus includes §8's derived words,
 * which is what the registry bootstrap is for.
 */
@Tags(NEEDS_REGISTRIES)
class ResolverCheck : FunSpec({

    val vocabulary by lazy {
        Vocabulary.load(MinecraftRegistries.shippedData()).also {
            check(it.problems.isEmpty()) { "vocabulary problems: ${it.problems}" }
        }
    }

    /**
     * The same sentence at the same seed resolves identically. Everything downstream leans on it: an Age
     * is rebuilt from its recipe on every open, so a wandering resolver is a world that changes under the
     * people living in it.
     */
    test("resolution is pure") {
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
     * Reversing a sentence changes nothing. §3.5 rejected "later word wins", which would make
     * equal-precision contradiction safe and evaporate the risk half of the precision axis — so word order
     * must not decide who yields, and must not leak into the seed either.
     */
    test("word order decides nothing") {
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
     * A single word can never make an incoherent Age. Nothing for it to disagree with, and
     * [VocabularyCheck] has established something can satisfy it — so a flaw here is one the resolver
     * invented.
     */
    test("one word is never incoherent") {
        // UNBACKED is the exception, and it is not the resolver inventing a disagreement: a word that only
        // sets a material can land on a dressing that cannot wear one (`overworld` has no stone to
        // substitute), and charging that is the design — a sentence the world could not honour is reported
        // rather than dropped. Every *other* register needs two words to be possible at all, so a lone word
        // triggering one would be a real fault. This used to assert coherence outright and passed by luck,
        // back when the only material words were the two written by hand; deriving one per block sampled
        // enough draws to find the case.
        val needsTwoWords = Register.entries.filter { it != Register.UNBACKED }
        for (word in vocabulary.words) {
            val resolution = Resolver.resolve(vocabulary, Sentence.flat(listOf(word)), SAMPLE_SEED)
            val invented = resolution.instability.flaws.filter { it.register in needsTwoWords }
            check(invented.isEmpty()) {
                "'${word.name}' alone was charged for disagreeing with nothing: $invented"
            }
        }
    }

    /**
     * What the resolver produced can be written down and read back by `/age compose` —
     * `AgeComposition.toString` being exactly what `parse` reads.
     */
    test("a resolved Age round-trips through compose") {
        for (sentence in SENTENCES) {
            val composition = resolve(vocabulary, sentence).composition
            val spelling = composition.toString()
            val read = AgeComposition.parse(spelling).getOrThrow()
            check(read == composition) { "\"$sentence\" resolved to '$spelling', which reads back as '$read'" }
        }
    }

    /**
     * **The acceptance test.** "Lush barren" yields both biome families, and says why: `verdant` and
     * `lifeless` bound temperature and humidity to stretches that cannot overlap, so the climate fractures
     * into a warm wet region and a cold dry one, and the instability index names the pair.
     */
    test("lush and barren yield both families") {
        val resolution = resolve(vocabulary, "verdant lifeless")
        val climates = resolution.composition.climates
        check(climates.size == 2) { "\"verdant lifeless\" gave ${climates.size} climate(s): $climates" }
        // Each fragment carries a whole climate of its own, and they must not be the same one.
        val bounds = (0..1).map { resolution.composition.optionsFor(Aspect.CLIMATE, it).chosen }
        check(bounds[0] != bounds[1]) { "both climate fragments came out identical: $bounds" }

        val division = resolution.instability.flaws.firstOrNull { it.register == Register.FRACTURE }
        checkNotNull(division) {
            "the world divided but nothing was charged for it: ${resolution.instability.flaws}"
        }
        check(division.words.containsAll(listOf("verdant", "lifeless"))) {
            "the division does not name the pair that caused it: ${division.words}"
        }
        check(division.aspect == Aspect.CLIMATE) {
            "the division was sited in ${division.aspect}, not the climate"
        }
        // The fracture still names *which tags* disagreed, which is the provenance §5.1 needs — the antonym
        // table earning its keep even though overlap is what decided to fracture.
        check(division.tags == listOf("barren", "lush") || division.tags == listOf("lush", "barren")) {
            "the fracture does not name the tags that disagreed: ${division.tags}"
        }
        // **A TENSION is no longer charged here, and that is structural rather than a regression.** §3.3
        // wants "you wrote opposites" and "the world tore in two to do it" charged as separate facts — but
        // `tensions` is asked of the *outcome*, and detects a tension by finding both words satisfied by
        // presets actually chosen. Climate's meaning lives in spans now, not in preset tags, so its single
        // preset carries neither `lush` nor `barren` and there is nothing for that test to see. The register
        // still fires for every preset-based aspect; it simply cannot for a span-based one, and the fracture
        // above carries the diagnosis instead.
        check(resolution.instability.flaws.none { it.register == Register.TENSION }) {
            "a span-based aspect charged a tension, which its presets cannot carry the tags for: " +
                "${resolution.instability.flaws}"
        }
    }

    /**
     * The sky cannot divide, so one of two sky words is displaced — the harsher register, and why it
     * exists. A world has one sky over it and no second place to put another (§3.4), which makes this the
     * one aspect where a contradiction genuinely cannot be honoured.
     */
    test("a sky cannot divide") {
        val resolution = resolve(vocabulary, "stormy clear")
        // That the sky is one sky needs no assertion — `AgeComposition.sky` is a single field where every
        // positional aspect is a list, which is §3.4's "technically impossible" made structural. What wants
        // checking is that the word which lost is *said out loud* rather than quietly absent.
        val displaced = resolution.instability.flaws.firstOrNull { it.register == Register.DISPLACED }
        checkNotNull(displaced) { "a word lost the sky and nothing said so: ${resolution.instability.flaws}" }
        check(displaced.aspect == Aspect.SKY) { "the loss was sited in ${displaced.aspect}, not the sky" }
        check(displaced.words.containsAll(listOf("stormy", "clear"))) {
            "the loss does not name both words: ${displaced.words}"
        }
        // Two exact words against each other is the design's dangerous case, so it must not come cheap.
        check(resolution.instability.index >= Register.DISPLACED.charge(Tier.EXACT)) {
            "exact against exact in a singular aspect cost only ${resolution.instability.index}"
        }
    }

    /**
     * A word the world cannot satisfy resolves as vacuous but is **reported** — §3.3's one hard
     * requirement. The word here is invented rather than shipped: [VocabularyCheck] stops one like it
     * shipping, and this checks what happens if one ever does.
     */
    test("an unbacked word is reported, not dropped") {
        val moonless = Word(
            ResourceLocation.fromNamespaceAndPath("test", "moonless"),
            Tier.EXACT,
            setOf(Aspect.SKY),
            mapOf("moonless" to 1.0),
        )
        val resolution = Resolver.resolve(vocabulary, Sentence.flat(listOf(moonless)), SAMPLE_SEED)
        val unbacked = resolution.instability.flaws.firstOrNull { it.register == Register.UNBACKED }
        checkNotNull(unbacked) { "an impossible word passed in silence, which is the one thing forbidden" }
        check(unbacked.words == listOf("moonless")) { "the report does not name the word: ${unbacked.words}" }
        check(resolution.instability.index > 0) { "an impossible word cost nothing at all" }
    }

    /**
     * Vagueness buys variety; precision takes it away — the progression axis, measured. If a vague
     * sentence did not vary, imprecision would be strictly worse than precision and the cheap half of the
     * design would be dead content.
     */
    test("vagueness varies and precision narrows") {
        val vague = spread(vocabulary, "beautiful")
        val precise = spread(vocabulary, "floating stormy arid riddled")
        check(vague > precise) { "precision narrowed nothing: vague $vague, precise $precise" }
        check(precise > 0) { "a precise sentence resolved to nothing at all" }
        println(
            "  \"beautiful\" gives $vague distinct Ages over $SEEDS_SAMPLED seeds; " +
                "four precise words give $precise.",
        )
    }

    /**
     * The exact word costs more than its restrictive synonym. `burning` and `molten` name the same tag at
     * different rungs; if they priced the same, the ladder would be decoration.
     */
    test("precision costs more") {
        val vague = resolve(vocabulary, "burning").cost
        val exact = resolve(vocabulary, "molten").cost
        check(exact > vague) { "'molten' cost $exact and 'burning' cost $vague, so precision is free" }
    }

    /**
     * A sentence that said nothing about the sea is rarely handed one of lava — what the readiness prior
     * is for. Drawn uniformly an unconstrained aspect gives lava one time in three, and nothing but this
     * would notice the prior's absence. Precision still reaches it.
     */
    test("an unasked draw prefers the ordinary") {
        val molten = (1L..SEEDS_SAMPLED).count { seed ->
            Sea.LAVA in resolve(vocabulary, "homely", seed).composition.seas
        }
        check(molten <= SEEDS_SAMPLED / MOST_UNASKED_LAVA) {
            "\"homely\" gave a sea of lava $molten times in $SEEDS_SAMPLED — readiness is not biting"
        }
        println("  \"homely\" draws a sea of lava $molten times in $SEEDS_SAMPLED seeds.")
    }

    /**
     * An Age nobody asked to be built in rarely is, and one that asked always is. Now that
     * [Structures.VANILLA] is a preset a sentence can draw, "opt-in per Age" is kept by the readiness
     * prior — the same lever as the sea of lava above.
     *
     * `floating` says nothing about habitation, so this measures the *unasked* draw. The effective rate in
     * a world is lower again, a set only being placed where the biome source can produce its biomes.
     */
    test("an unasked Age is rarely built in") {
        val built = (1L..SEEDS_SAMPLED).count { seed ->
            resolve(vocabulary, "floating", seed).composition.structures == Structures.VANILLA
        }
        check(built <= SEEDS_SAMPLED / MOST_UNASKED_STRUCTURES) {
            "\"floating\" was built in $built times in $SEEDS_SAMPLED, so structures are no longer opt-in"
        }
        // The other half, because a rare unasked draw is only defensible if asking works: `settled` narrows
        // the aspect to one candidate, so it is not a rate but a certainty, at every seed.
        for (seed in 1L..SEEDS_SAMPLED) {
            val asked = resolve(vocabulary, "settled", seed).composition.structures
            check(asked == Structures.VANILLA) { "'settled' resolved structures to $asked at seed $seed" }
        }
        println("  \"floating\" is built in $built times in $SEEDS_SAMPLED seeds; \"settled\" at every seed.")
    }

    /**
     * The preset a word claims strongly takes more ground than one it claims weakly — the point of shares.
     * Checked at the recipe rather than by counting columns; [RegionShareCheck] proves a share turns into
     * ground.
     */
    test("a strong claim takes more ground") {
        // The ladder itself, which is where "strongly associated" turns into ground. Deliberately checked
        // apart from any sentence: whether a given Age divides unevenly depends on which presets were drawn,
        // but what a claim ratio *means* must not.
        check(Share.nearest(ratio = 1.0) == Share.DOMINANT) { "an equal claim should take an equal share" }
        check(Share.nearest(ratio = 0.9 / 0.9) == Share.DOMINANT) { "two strong claims should divide evenly" }
        check(Share.nearest(ratio = 0.3 / 0.9) == Share.COMMON) { "a third of a claim should be a large minority" }
        check(Share.nearest(ratio = 0.06 / 0.9) == Share.SCATTERED) { "a fifteenth of a claim should be islands" }
        check(Share.nearest(ratio = 0.01 / 0.9) == Share.RARE) {
            "a hundredth of a claim should be somewhere out there"
        }

        // And no vector of shares may leave a word effectively absent — Jonah's floor, deliberately low so
        // that rare means rare and finding it is the reward.
        val floored = Share.findable(listOf(Share.DOMINANT, Share.DOMINANT, Share.RARE))
        val smallest = floored.minOf { it.weight } / floored.sumOf { it.weight }
        check(smallest >= Share.LEAST_SHARE_OF_A_WORLD) {
            "three territories left the smallest at %.2f%% of the world".format(smallest * PERCENT)
        }

        // Then the behaviour, over seeds, because which preset is drawn varies: whenever bare rock (pinned
        // exactly, and the strongest claim there is) shares a world, it must never be the lesser territory —
        // and an uneven division has to actually happen sometimes, or shares would be decoration.
        var uneven = 0
        for (seed in 1L..HARMONY_SEEDS) {
            // The **carving** stands in for the dressing this used to use, and for a specific reason:
            // unevenness needs an aspect whose presets carry the asked-for tag at *different* strengths, and
            // carvers do — `caves` is thoroughly cavernous where `porous` is barely so. Two terrain words
            // came out even at every seed because their carriers all answer about equally, which is a fact
            // about the tag data rather than about shares, and it made the property vacuous.
            val composition = resolve(vocabulary, "riddled unbroken", seed).composition
            val shares = composition.sharesOf(Aspect.CARVERS)
            val pinned = composition.carvers.indexOf(Carvers.SOLID)
            if (pinned >= 0) {
                check(shares[pinned].weight >= shares.maxOf { it.weight }) {
                    "the exactly-pinned carving took less ground than its neighbour: " +
                        "${composition.carvers} $shares"
                }
            }
            if (shares.toSet().size > 1) {
                uneven++
                // The spelling of an uneven division has to survive the trip, which is what keeps `/age list`
                // output pasteable into `/age compose`.
                val spelling = composition.toString()
                // `@`, not `:` — this asked for a colon until the sea aspect opened, and then passed for free
                // on `sea=minecraft:water` while asserting nothing about shares at all.
                check("@" in spelling) { "an uneven division should say so: '$spelling'" }
                check(AgeComposition.parse(spelling).getOrThrow() == composition) {
                    "'$spelling' does not read back as what wrote it"
                }
            }
        }
        check(uneven > 0) { "no seed divided \"riddled unbroken\" unevenly, so shares never bite" }
        println("  \"riddled unbroken\" divides unevenly at $uneven of $HARMONY_SEEDS seeds.")
    }

    /**
     * A sentence that merely *likes* several things may get several, is charged nothing, and **rarely gets
     * a seam for its trouble**. Harmony is free: "beautiful" reaching a beach and a field of flowers is
     * the word doing its job.
     *
     * Kept as a comparison rather than a hard count, because the numbers are taste and the *ordering* is
     * the design claim (§3.4).
     */
    test("harmony is free and the terrain is the reluctant one") {
        var carvingsDoubled = 0
        var landformsDoubled = 0
        for (seed in 1L..HARMONY_SEEDS) {
            val resolution = resolve(vocabulary, "beautiful", seed)
            val composition = resolution.composition
            // The dressing used to be the reluctant one this compared against; it is deleted, so the carving
            // — the *most* companionable aspect (0.25 against the terrain's 0.12) — plays the other side.
            if (composition.carvers.size > 1) carvingsDoubled++
            if (composition.terrains.size > 1) landformsDoubled++
            check(resolution.instability.isCoherent) {
                "\"beautiful\" at seed $seed was charged ${resolution.instability.index}: " +
                    "${resolution.instability.flaws} — liking two things is not a contradiction"
            }
        }
        check(carvingsDoubled >= landformsDoubled) {
            "dressings doubled up $carvingsDoubled times against terrains' $landformsDoubled, but a dressing " +
                "seam is now the rarest thing in an Age — variety belongs to biomes (design §3.4)"
        }
        println(
            "  \"beautiful\" over $HARMONY_SEEDS seeds: two carvings $carvingsDoubled times, " +
                "two terrains $landformsDoubled times, no instability either way.",
        )
    }

    /**
     * An exact word admits no company at all — it *pins one value* (§4.4). The line between generosity and
     * disobedience: precision gets that thing and nothing beside it.
     */
    test("an exact word admits no company") {
        for (seed in 1L..HARMONY_SEEDS) {
            // `flat` is exact about the terrain; the dressing this used to test is deleted.
            val composition = resolve(vocabulary, "beautiful flat", seed).composition
            check(composition.terrains.size == 1) {
                "an exact terrain admitted company: ${composition.terrains} at seed $seed"
            }
        }
    }

    /**
     * A word derived from the registry gives exactly the thing it names — §8's promise end to end. Three
     * properties that only mean anything together: the word **exists**, naming it **gets it** at every
     * seed, and naming it **beside a vaguer word takes the world** — the part that needed [Word.pullOn],
     * since a derived word carries no tags and would otherwise claim nothing.
     *
     * `lava` rather than `water`, being the derived word most likely to collide with the curated pool's
     * opinions: `beautiful` pushes hard against `hostile`.
     */
    test("a derived word names its referent") {
        val lava = vocabulary.word("lava") ?: error("no derived word 'lava' — is derivation running?")
        check(lava.tier == Tier.EXACT) { "a derived word must be exact, not ${lava.tier.key}" }
        check(lava.names == Sea.LAVA.key) { "'lava' names ${lava.names}, not ${Sea.LAVA.key}" }
        check(vocabulary.word("minecraft:lava") == lava) { "a derived word must also answer to its full id" }

        for (seed in 0L..<SEEDS_SAMPLED) {
            val alone = resolve(vocabulary, "lava", seed).composition
            check(alone.seas == listOf(Sea.LAVA)) {
                "'lava' gave ${alone.seas} at seed $seed, and an exact word pins one value"
            }
            val contested = resolve(vocabulary, "beautiful lava", seed).composition
            check(contested.seas.first() == Sea.LAVA) {
                "'beautiful lava' let ${contested.seas.first()} take the widest share at seed $seed, " +
                    "so naming a thing outright is claiming it less hard than merely liking one"
            }
        }
    }

    /**
     * A **material** steers the preset that was chosen without choosing it (§3.2) — and the three ways that
     * could go quietly wrong. `basalt` sets `terrain.stone` and nothing else, so it must **not narrow** the
     * terrain (an empty carrier set naively reads as "unbacked"), must **not suppress harmony** despite
     * being exact, and must **always be honoured** whatever else the Age is.
     */
    test("a material steers without choosing") {
        val basalt = vocabulary.word("basalt") ?: error("no word 'basalt' — is the material hook wired?")
        check(basalt.sets == mapOf(Terrain.STONE.name to "minecraft:blackstone")) {
            "'basalt' sets ${basalt.sets}, which is not the material it is for"
        }
        check(!basalt.constrainsPresets) { "a word that only sets a parameter must not narrow presets" }

        for (seed in 0L..<SEEDS_SAMPLED) {
            val resolution = resolve(vocabulary, "basalt", seed)
            val composition = resolution.composition
            check(
                composition.options.of(Aspect.TERRAIN).chosen[Terrain.STONE.name] == listOf("minecraft:blackstone"),
            ) {
                "'basalt' did not set the stone at seed $seed: ${composition.options.of(Aspect.TERRAIN)}"
            }
            check(composition.unknownOptions.isEmpty()) {
                "'basalt' set an option no preset understands at seed $seed: ${composition.unknownOptions}"
            }
            // Nothing can ignore a material any more, so a lone material word can never be incoherent —
            // whatever terrain and dressing were drawn, the rock is blackstone and vanilla paints its skin
            // over it.
            check(resolution.instability.isCoherent) {
                "'basalt' alone was charged at seed $seed: ${resolution.instability.flaws}"
            }
        }
    }

    /**
     * **The conjunction**: joining two claims on one parameter keeps both and charges nothing. The
     * asymmetry is what matters, so both halves are asserted together — unjoined, `blackstone tuff`
     * **fractures** and each takes a territory, charged, because the writer did not ask for two places;
     * joined, it is one rock made of both throughout.
     *
     * **If this ever passes with juxtaposition also mingling, `and` has stopped meaning anything** — that
     * is the failure to watch for, not a crash.
     */
    test("joining two materials mingles them and costs nothing") {
        val hollow = vocabulary.word("hollow") ?: error("the shipped vocabulary lost 'hollow'")
        val land = Constraint(hollow, Scope.Confined(setOf(Aspect.TERRAIN)))
        val first = material("firststone", "minecraft:blackstone")
        val second = material("secondstone", "minecraft:tuff")
        fun sentence(group: Group?) = Sentence.of(
            listOf(
                land,
                Constraint(first, Scope.Confined(setOf(Aspect.TERRAIN)), group = group),
                Constraint(second, Scope.Confined(setOf(Aspect.TERRAIN)), group = group),
            ),
        )

        val apart = Resolver.resolve(vocabulary, sentence(group = null), SAMPLE_SEED)
        check(apart.composition.terrains.size == 2) {
            "unjoined materials did not fracture the terrain: ${apart.composition.terrains}"
        }
        for (member in 0..1) {
            val held = apart.composition.optionsFor(Aspect.TERRAIN, member).allOf(Terrain.STONE)
            check(held.size == 1) { "fractured territory $member holds $held, not one material" }
        }
        val eachStone = (0..1).map {
            apart.composition.optionsFor(Aspect.TERRAIN, it).allOf(Terrain.STONE).single()
        }
        check(eachStone.toSet().size == 2) { "both fragments were given the same material: $eachStone" }
        check(apart.instability.flaws.any { it.register == Register.FRACTURE }) {
            "the world broke in two and was not charged for it: ${apart.instability.flaws}"
        }

        val joined = Resolver.resolve(vocabulary, sentence(group = Group(0)), SAMPLE_SEED)
        val mingled = joined.composition.optionsFor(Aspect.TERRAIN, 0).allOf(Terrain.STONE)
        check(mingled.size == 2) { "joined materials did not mingle: they gave $mingled" }
        check(joined.composition.terrains.size == 1) {
            "the conjunction fractured instead of mingling: ${joined.composition.terrains}"
        }
        check(joined.instability.flaws.none { it.register == Register.FRACTURE }) {
            "the conjunction charged for harmony: ${joined.instability.flaws}"
        }
        check(joined.instability.isCoherent) { "joining two materials made an incoherent Age" }
    }

    /**
     * **`only` and `except` reach a population.** The parser attached [Polarity] while the resolver dropped
     * it, so `except pillager outposts` parsed perfectly and did nothing — a sentence read correctly,
     * charged for, and silently without effect. **Nothing but this notices if the wire comes loose again**,
     * the parse looking right either way.
     *
     * All three verbs together, because it is their *difference* that matters.
     */
    test("only and except reach a population") {
        fun asked(vararg said: Pair<String, Polarity>): Population {
            val constraints = said.map { (path, polarity) ->
                Constraint(structureSet(path), Scope.Confined(setOf(Aspect.STRUCTURES)), polarity)
            }
            val resolved = Resolver.resolve(vocabulary, Sentence.of(constraints), SAMPLE_SEED)
            return Population.of(resolved.composition.optionsFor(Aspect.STRUCTURES, 0).claimsOn(Structures.BUILT))
        }

        val plainly = asked("woodland_mansions" to Polarity.ASSERTED)
        check(plainly.wanted.map { it.value } == listOf("minecraft:woodland_mansions")) {
            "a plain mention gave ${plainly.wanted}"
        }
        check(!plainly.exclusive) { "a plain mention pinned the population, which naming must never do" }
        check(plainly.struck.isEmpty()) { "a plain mention struck something out: ${plainly.struck}" }

        val singledOut = asked("woodland_mansions" to Polarity.ONLY)
        check(singledOut.exclusive) { "'only woodland_mansions' did not make the population exclusive" }
        check(singledOut.wanted.map { it.value } == listOf("minecraft:woodland_mansions")) {
            "'only woodland_mansions' wanted ${singledOut.wanted}"
        }

        val struckOut = asked("pillager_outposts" to Polarity.EXCEPT)
        check(struckOut.struck == listOf("minecraft:pillager_outposts")) { "'except' struck ${struckOut.struck}" }
        check(struckOut.wanted.isEmpty()) { "'except' also asked for something: ${struckOut.wanted}" }

        // And the two together, since a sentence may carry both and they must not collapse into each other.
        val both = asked("woodland_mansions" to Polarity.ONLY, "pillager_outposts" to Polarity.EXCEPT)
        check(both.exclusive && both.wanted.map { it.value } == listOf("minecraft:woodland_mansions")) {
            "'only' lost its footing beside 'except'"
        }
        check(both.struck == listOf("minecraft:pillager_outposts")) { "'except' lost its footing beside 'only'" }
    }

    /**
     * A word aimed where it says nothing is **charged, and charged once** — the price of aiming being a
     * precision lever (§4.3.1). The failure this guards against is silence: the word reaches no aspect, so
     * every other register is out of earshot, and an uncharged misaim is a page spent for nothing with
     * nothing said about it.
     */
    test("a misaimed word is charged rather than ignored") {
        val skyWordAtTheLand = Constraint(
            vocabulary.word("starless") ?: error("the shipped vocabulary lost 'starless'"),
            Scope.Confined(emptySet()),
        )
        val resolved = Resolver.resolve(vocabulary, Sentence.of(listOf(skyWordAtTheLand)), SAMPLE_SEED)
        val misaimed = resolved.instability.flaws.filter { it.register == Register.MISAIMED }
        check(misaimed.size == 1) { "a misaimed word gave ${misaimed.size} flaws: ${resolved.instability.flaws}" }
        check(misaimed.single().words == listOf("starless")) { "the flaw named ${misaimed.single().words}" }

        // And a word that landed somewhere is never charged for it, or every ordinary sentence would be.
        val landed = resolve(vocabulary, "starless")
        check(landed.instability.flaws.none { it.register == Register.MISAIMED }) {
            "a word that found its aspect was charged as misaimed: ${landed.instability.flaws}"
        }
    }

    /**
     * **A rung reaches a population**, which is the other half of the quantifier: the parser binds it to a
     * term and this is what carries it into the recipe. Exactly the wire [Polarity] was missing above —
     * `teeming villages` would parse perfectly, cost ink and place vanilla's own number of villages.
     *
     * Asserted through [Claim] rather than the spelling, because the mark is a recipe detail where the rung
     * is the claim.
     */
    test("a rung reaches a population") {
        fun askedFor(rung: Density): Claim {
            val said = Constraint(
                structureSet("villages"),
                Scope.Confined(setOf(Aspect.STRUCTURES)),
                density = rung,
            )
            val resolved = Resolver.resolve(vocabulary, Sentence.of(listOf(said)), SAMPLE_SEED)
            val population = Population.of(
                resolved.composition.optionsFor(Aspect.STRUCTURES, 0).claimsOn(Structures.BUILT),
            )
            return population.wanted.singleOrNull() ?: error("'villages' at $rung gave ${population.wanted}")
        }

        for (rung in Density.entries) {
            val claim = askedFor(rung)
            check(claim.value == "minecraft:villages") { "the rung ate the value: ${claim.value}" }
            check(claim.density == rung) { "asking for $rung villages gave ${claim.density}" }
        }
    }

    /**
     * The three guards on a fracture, each asserted rather than trusted:
     *
     * - **one claim never fractures** — nothing to reconcile, so the aspect stays whole;
     * - **word order still decides nothing** (§3.5), including which fragment got which material;
     * - **an aspect that already divided on presets contends instead**, two divisions in one aspect
     *   multiplying, and *which* fragment a word was aimed at being a question the grammar cannot answer.
     */
    test("a fracture obeys its guards") {
        fun aimedAtTheLand(word: Word) = Constraint(word, Scope.Confined(setOf(Aspect.TERRAIN)))
        val hollow = aimedAtTheLand(vocabulary.word("hollow") ?: error("the shipped vocabulary lost 'hollow'"))
        // Two terrain words with disjoint carriers, so the *presets* divide and the guard below has something
        // real to bite on.
        val flat = aimedAtTheLand(vocabulary.word("flat") ?: error("the shipped vocabulary lost 'flat'"))
        val towering =
            aimedAtTheLand(vocabulary.word("towering") ?: error("the shipped vocabulary lost 'towering'"))
        val copper = aimedAtTheLand(material("firststone", "minecraft:copper_block"))
        val andesite = aimedAtTheLand(material("secondstone", "minecraft:andesite"))

        val alone = Resolver.resolve(vocabulary, Sentence.of(listOf(hollow, copper)), SAMPLE_SEED)
        check(alone.composition.terrains.size == 1) {
            "one material fractured the terrain: ${alone.composition.terrains}"
        }
        check(alone.instability.flaws.none { it.register == Register.FRACTURE }) {
            "one material was charged for a fracture: ${alone.instability.flaws}"
        }

        val forwards = Resolver.resolve(vocabulary, Sentence.of(listOf(hollow, copper, andesite)), SAMPLE_SEED)
        val backwards = Resolver.resolve(vocabulary, Sentence.of(listOf(andesite, copper, hollow)), SAMPLE_SEED)
        check(forwards.composition == backwards.composition) {
            "reversing a fractured sentence moved the fragments: " +
                "${forwards.composition} then ${backwards.composition}"
        }
        // And it survives being written down, since a fracture is the first thing the resolver produces that
        // seats one preset twice — a shape `toString` and `parse` had only ever seen from a hand-written
        // composition.
        val spelling = forwards.composition.toString()
        check(AgeComposition.parse(spelling).getOrThrow() == forwards.composition) {
            "a fractured composition does not read back as itself: '$spelling'"
        }

        // `flat towering` already splits the terrain in two, so the materials have nowhere of their own to go.
        val alreadyDivided =
            Resolver.resolve(vocabulary, Sentence.of(listOf(flat, towering, copper, andesite)), SAMPLE_SEED)
        check(alreadyDivided.composition.terrains.size == 2) {
            "the preset division was lost: ${alreadyDivided.composition.terrains}"
        }
        check(alreadyDivided.instability.flaws.any { it.register == Register.DISPLACED }) {
            "a material lost the argument in an already-divided aspect and was not charged: " +
                "${alreadyDivided.instability.flaws}"
        }
    }
})

/** The sentence spelled the way a writer would say it, resolved. */
private fun resolve(vocabulary: Vocabulary, sentence: String, seed: Long = SAMPLE_SEED): Resolution {
    val words = sentence.split(" ").filter(String::isNotBlank).map { name ->
        vocabulary.word(name) ?: error("the shipped vocabulary has no word '$name'")
    }
    // Flat, so every property below still asks what it always asked: these are sentences without
    // structure, which is what a book was before the grammar existed. Structure has its own check.
    return Resolver.resolve(vocabulary, Sentence.flat(words), seed)
}

/** How many distinct Ages a sentence gives across many seeds. */
private fun spread(vocabulary: Vocabulary, sentence: String): Int =
    (1L..SEEDS_SAMPLED).map { seed -> resolve(vocabulary, sentence, seed).composition }.toSet().size

/** A word that only sets the terrain's stone — what §3.2 calls a material. */
private fun material(name: String, block: String) = Word(
    ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name),
    Tier.EXACT,
    setOf(Aspect.TERRAIN),
    emptyMap(),
    null,
    mapOf(Terrain.STONE.name to block),
)

/** A word that asks for one vanilla structure set by name. */
private fun structureSet(path: String) = Word(
    ResourceLocation.withDefaultNamespace(path),
    Tier.EXACT,
    setOf(Aspect.STRUCTURES),
    emptyMap(),
    null,
    mapOf(Structures.BUILT.name to "minecraft:$path"),
)

/**
 * Sentences chosen to cover the shapes a sentence can take: coherent, contradictory in an aspect that can
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

// Enough seeds that an aspect's appetite for company shows up as a rate rather than as an accident.
private const val HARMONY_SEEDS = 60L

private const val PERCENT = 100.0

// One in five is generous; uniform would be one in three, and something under a tenth is what the tuning
// actually gives. The point of the bound is to catch readiness being ignored, not to pin a number.
private const val MOST_UNASKED_LAVA = 5

// Structures carry a readiness of a quarter, so a fifth of unasked draws is the arithmetic and a third is
// the bound — loose for the same reason as the lava one, which is to catch the prior being ignored rather
// than to freeze a tuning number. **The number itself wants Jonah's eyes**: it decides how often an Age
// nobody asked to be inhabited turns out to be.
private const val MOST_UNASKED_STRUCTURES = 3
