package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Carvers
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.aspect.Population
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Share
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.sky.SkySpec
import co.voik.agesandtheart.age.word.grammar.Constraint
import co.voik.agesandtheart.age.word.grammar.Group
import co.voik.agesandtheart.age.word.grammar.Phrase
import co.voik.agesandtheart.age.word.grammar.Scope
import co.voik.agesandtheart.age.word.grammar.Sentence
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.resources.Identifier
import co.voik.agesandtheart.age.aspect.Biomes
import co.voik.agesandtheart.worldgen.biome.BiomePreference
import co.voik.agesandtheart.age.aspect.Surface
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Spawns

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

    /** A book, read — null being a row that forgot the `age` page, which is a fixture bug (§4.3.1). */
    fun read(pages: List<String>) = Grammar.read(vocabulary, pages) ?: error("not a book: $pages")

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
     * **Narrowing words bound an axis; evocative words bend it** (§4.4). The band a precise word set must
     * come back untouched, and the evocative word must still have done something inside it — otherwise it
     * charged ink for a climate it had no say in, which is the silent failure a soft restriction gives.
     */
    test("an evocative word bends a band without moving it") {
        val alone = climateOf(resolve(vocabulary, "parched"))
        val beside = climateOf(resolve(vocabulary, "beautiful parched"))
        check(alone.isNotEmpty()) { "'parched' bounded nothing, so there is no band to test against" }

        fun bands(spans: List<Span>) = spans.map { it.least to it.most }
        check(bands(alone) == bands(beside)) {
            "'beautiful' moved the band 'parched' set: ${bands(beside)} against ${bands(alone)}"
        }
        check(alone.all { it.bend == Span.EVEN }) { "'parched' alone bent something: $alone" }
        check(beside.any { it.bend != Span.EVEN }) { "'beautiful' bent nothing beside 'parched': $beside" }
    }

    /** And it can never divide a world, a fracture being a failure and an evocative word unable to fail. */
    test("an evocative word never fractures a climate") {
        for (seed in 1L..SEEDS_SAMPLED) {
            val resolution = resolve(vocabulary, "beautiful", seed)
            check(resolution.composition.climates.size == 1) {
                "'beautiful' alone split the climate in ${resolution.composition.climates.size} at seed $seed"
            }
            check(resolution.instability.flaws.none { it.register == Register.FRACTURE }) {
                "'beautiful' alone fractured something at seed $seed: ${resolution.instability.flaws}"
            }
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
        // Each fragment carries a whole climate of its own, and they must not be the same one. Read off the
        // fragments themselves: a climate's answer *is* its spans, so there is nothing in options to read.
        val bounds = climates.map { it.spelled() }
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
            Identifier.fromNamespaceAndPath("test", "moonless"),
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
            "  \"beautiful\" gives $vague distinct Ages over $SEEDS_FOR_A_SPREAD seeds; " +
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
     * **An Age nobody spoke to about building is built in**, at every seed, and a word is what empties it.
     *
     * This replaces "an unasked Age is rarely built in" and inverts it. Habitation used to be opt-in
     * through a readiness prior on a `vanilla` preset, and the prior went with the preset when structures
     * became a population: there is nothing to draw, so nothing for a prior to lean on. Habitability
     * (design §7.6) is expected to take the question back and make it depend on what the Age *is* rather
     * than on a coin; until it does, a fixed baseline is what everything else can be measured against.
     */
    test("an unspoken Age is built in, and a word empties it") {
        for (seed in 1L..SEEDS_SAMPLED) {
            val unspoken = builtIn(resolve(vocabulary, "floating", seed).composition)
            check(unspoken.isEmpty()) { "'floating' said something about building at seed $seed: $unspoken" }
        }
        // Nothing said means nothing written, and the world reads an unwritten population as vanilla's own.
        val untouched = builtIn(resolve(vocabulary, "untouched").composition)
        check(untouched == listOf(Structures.NOTHING)) { "'untouched' left $untouched standing" }

        val settled = Population.of(
            resolve(vocabulary, "settled").composition.optionsFor(Aspect.STRUCTURES, 0).claimsOn(Structures.BUILT),
        )
        val villages = settled.wanted.firstOrNull { it.value == "minecraft:villages" }
            ?: error("'settled' said nothing about villages: ${settled.wanted}")
        check(villages.density > Rung.ORDINARY) { "'settled' asked for ${villages.density} villages" }
        check(settled.struck.isEmpty()) { "'settled' struck something out: ${settled.struck}" }
    }

    /**
     * **A preset that opted out of being askable never arrives by chance**, which is the whole of what
     * opting out is worth. `askableInASentence` was read by `VocabularyCheck` and by nothing that resolves,
     * so `sky=spire` — reachable by no word, and carrying the Spire's own dimension type — was still in the
     * bag an unconstrained aspect drew from, and came up on about one Age in three. Unaskable made it rare
     * rather than unreachable, which is the worst of the two.
     *
     * Every sentence, because the leak was in the draw an aspect takes when *nothing* speaks to it, and
     * which aspect that is depends on what the sentence happened to be about.
     */
    test("nothing unaskable is ever drawn") {
        val unaskable = Aspect.entries
            .flatMap { aspect -> vocabulary.candidatesFor(aspect) }
            .filterNot { it.askableInASentence }
        check(unaskable.isNotEmpty()) { "nothing opts out of being askable, so this check asserts nothing" }
        for (sentence in SENTENCES) {
            for (seed in 1L..SEEDS_SAMPLED) {
                val arrived = resolve(vocabulary, sentence, seed).composition.presets.filter { it in unaskable }
                check(arrived.isEmpty()) {
                    "\"$sentence\" at seed $seed drew ${arrived.joinToString { it.key }}, " +
                        "which no sentence can ask for"
                }
            }
        }
        println("  ${unaskable.joinToString { it.key }} stayed out of ${SENTENCES.size * SEEDS_SAMPLED} draws.")
    }

    /**
     * The preset a word claims strongly takes more ground than one it claims weakly — the point of shares.
     * Checked at the recipe rather than by counting columns; [RegionShareCheck] proves a share turns into
     * ground.
     */
    test("a strong claim takes more ground") {
        // What a claim ratio means, checked apart from any sentence: whether a given Age divides unevenly
        // depends on which presets were drawn, but a third of the strongest claim must be a third of the
        // ground however that Age came out.
        check(Share.legible(0.9 / 0.9) == Share.EVEN) { "two strong claims should divide evenly" }
        check(Share.legible(0.3 / 0.9) == A_THIRD) { "a third of a claim should take a third of the ground" }
        check(Share.legible(0.06 / 0.9) < A_THIRD) { "a fifteenth of a claim should take far less than a third" }

        // And no vector of shares may leave a word effectively absent — Jonah's floor, however many
        // territories divide the ground, since raising one faint share raises the whole the next is
        // measured against. Checked past where it is used for exactly that reason.
        for (territories in 2..MOST_TERRITORIES) {
            val faintest = List(territories - 1) { Share.EVEN } + Share.legible(A_CLAIM_BARELY_MADE)
            val floored = Share.findable(faintest)
            val smallest = floored.min() / floored.sum()
            check(smallest >= Share.LEAST_SHARE_OF_A_WORLD - SLACK) {
                "$territories territories left the smallest at %.2f%% of the world".format(smallest * PERCENT)
            }
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
                check(shares[pinned] >= shares.max()) {
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
        val asMaterial = mapOf(Terrain.STONE.name to "minecraft:basalt", Surface.MATERIAL.name to "minecraft:basalt")
        check(basalt.sets == asMaterial) {
            "'basalt' sets ${basalt.sets}, which is not the material it is for"
        }
        // Asked of the terrain rather than globally, which is the honest form for a derived word: a block
        // names a *sea* outright and merely sets a material on the terrain, so the global question answers
        // "narrows" for an opinion it never had here. See [Word.constrainsPresetsIn].
        check(!basalt.constrainsPresetsIn(Aspect.TERRAIN)) {
            "a word that only sets a parameter must not narrow the terrain's presets"
        }

        for (seed in 0L..<SEEDS_SAMPLED) {
            val resolution = resolve(vocabulary, "basalt", seed)
            val composition = resolution.composition
            check(
                composition.options.of(Aspect.TERRAIN).chosen[Terrain.STONE.name] == listOf("minecraft:basalt"),
            ) {
                "'basalt' did not set the stone at seed $seed: ${composition.options.of(Aspect.TERRAIN)}"
            }
            check(composition.unknownOptions.isEmpty()) {
                "'basalt' set an option no preset understands at seed $seed: ${composition.unknownOptions}"
            }
            // Nothing can ignore a material any more, so a lone material word can never be incoherent —
            // whatever terrain and dressing were drawn, the rock is basalt and vanilla paints its skin
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
     * A page the writer laid where it could not be read is **charged, and charged once** — the price of
     * aiming being a precision lever (§4.3.1). What this guards against is silence: re-homing a word to
     * make a sentence work is only acceptable at all because the writer is told and billed for it.
     */
    test("a re-homed word is charged rather than moved in silence") {
        val moved = Constraint(
            vocabulary.word("starless") ?: error("the shipped vocabulary lost 'starless'"),
            Scope.Confined(setOf(Aspect.SKY)),
            rehomed = true,
        )
        val resolved = Resolver.resolve(vocabulary, Sentence.of(listOf(moved)), SAMPLE_SEED)
        val charged = resolved.instability.flaws.filter { it.register == Register.REHOMED }
        check(charged.size == 1) { "a re-homed word gave ${charged.size} flaws: ${resolved.instability.flaws}" }
        check(charged.single().words == listOf("starless")) { "the flaw named ${charged.single().words}" }
        check(charged.single().aspect == Aspect.SKY) { "the flaw did not say where it landed: ${charged.single()}" }

        // And a word that went where it was written is never charged, or every ordinary sentence would be.
        val stayed = resolve(vocabulary, "starless")
        check(stayed.instability.flaws.none { it.register == Register.REHOMED }) {
            "a word that stayed put was charged as moved: ${stayed.instability.flaws}"
        }
    }

    /**
     * **The two failure channels cost different things** (§4.3), which is the whole reason they are kept
     * apart at the type level: a page nobody can read makes the Age vaguer and is charged nothing, where a
     * page no sentence has room for is the dearest thing a writer can do.
     */
    test("an unreadable page and an impossible one are not the same complaint") {
        val said = listOf(Constraint(vocabulary.word("starless")!!, Scope.Confined(setOf(Aspect.SKY))))

        val garbled = Sentence(said.map { Phrase(modifiers = listOf(it)) }, unreadable = listOf("zzzznotaword"))
        val vaguer = Resolver.resolve(vocabulary, garbled, SAMPLE_SEED)
        check(vaguer.instability.isCoherent) { "an unreadable page was charged: ${vaguer.instability.flaws}" }
        check(vaguer.dropped == listOf("zzzznotaword")) { "an unreadable page went unreported: ${vaguer.dropped}" }

        val nonsense = Sentence(said.map { Phrase(modifiers = listOf(it)) }, impossible = listOf("age"))
        val charged = Resolver.resolve(vocabulary, nonsense, SAMPLE_SEED)
        val flaws = charged.instability.flaws.filter { it.register == Register.IMPOSSIBLE }
        check(flaws.single().words == listOf("age")) { "an impossible page gave ${charged.instability.flaws}" }
        check(flaws.single().severity > Register.REHOMED.charge(Tier.EXACT)) {
            "an impossibility cost ${flaws.single().severity}, no more than the cheapest way to be wrong"
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
        fun askedFor(rung: Double): Claim {
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

        for (rung in RUNGS) {
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
    /**
     * **A word that turns a numeric knob has to land where it means**, end to end: the word bounds an
     * axis, a value is drawn inside it, and the sky is built from that. Nothing else checks the middle
     * step, and a span merely *near* the end of its axis reads as a working word — `starless` written as
     * a stretch rather than a point left a sky with two hundred stars in it, and every other check passed.
     */
    test("a sky word lands where it says") {
        fun skyOf(sentence: String): SkySpec {
            val composition = resolve(vocabulary, sentence).composition
            return composition.sky.specFor(composition.optionsFor(Aspect.SKY, 0), SAMPLE_SEED)
        }

        val ordinary = skyOf("stormy")
        check(ordinary.stars.count == SkySpec.VANILLA_STAR_COUNT) {
            "a sky nobody spoke to about stars did not keep vanilla's: ${ordinary.stars.count}"
        }
        check(skyOf("starless").stars.count == 0) {
            "'starless' left ${skyOf("starless").stars.count} stars in the sky"
        }
        check(skyOf("starlit").stars.count > SkySpec.VANILLA_STAR_COUNT) {
            "'starlit' drew ${skyOf("starlit").stars.count} stars, no more than an ordinary sky"
        }

        // And a count says exactly its number, since that is the whole of what a count is.
        check(skyOf("twinned").bodies.count { it.phase == null } == TWO_SUNS) {
            "'twinned' drew ${skyOf("twinned").bodies.size} bodies in all"
        }
        check(skyOf("moonless").bodies.all { it.phase == null }) { "'moonless' left a moon overhead" }
    }

    /**
     * **A vague word reaches biomes it never names**, which is the whole of how a population answers a
     * sentence that says nothing specific (§8.2). `beautiful` names no biome; what it has is a signed tag
     * query, and the curated pool is what turns that into ground.
     *
     * Both directions, because a signed query is only half honoured if it can add and not take away.
     */
    test("a vague word weighs a population both ways") {
        val grown = preferences(resolve(vocabulary, "beautiful").composition)
        check(grown.isNotEmpty()) { "'beautiful' reached no biome at all" }

        val favoured = grown.filter { it.weight > BiomePreference.ORDINARY }.map { it.biome.path }
        val thinned = grown.filter { it.weight < BiomePreference.ORDINARY }.map { it.biome.path }
        check("cherry_grove" in favoured) { "'beautiful' did not favour the cherry groves: $grown" }
        check("basalt_deltas" in thinned) { "'beautiful' did not thin the ash flats: $grown" }

        // The floor: a word that merely *likes* things is not an instruction to delete anything (§3.3).
        check(grown.none { it.removes }) { "an evocative word struck a biome out: $grown" }
        check(grown.all { it.weight <= MOST_OF_A_WORLD }) { "one word talked a biome past the cap: $grown" }
    }

    /** A word that narrows bears down harder than one that merely likes — the tiers, in ground. */
    test("a restrictive word claims harder than an evocative one") {
        fun weightOfLushCaves(sentence: String) = preferences(resolve(vocabulary, sentence).composition)
            .firstOrNull { it.biome.path == "lush_caves" }?.weight
            ?: error("'$sentence' said nothing about the lush caves")
        check(weightOfLushCaves("verdant") > weightOfLushCaves("beautiful")) {
            "restrictive 'verdant' claimed no harder than evocative 'beautiful': " +
                "${weightOfLushCaves("verdant")} against ${weightOfLushCaves("beautiful")}"
        }
    }

    /**
     * `only` and `except` are the writer saying outright what to keep and what to strike, so they reach a
     * population **through tags** as well as by name — otherwise "only lush" would be a sentence the
     * language could say and the world could not hear.
     */
    test("only and except reach a population through tags") {
        val lush = vocabulary.word("verdant") ?: error("the shipped vocabulary lost 'verdant'")
        fun said(polarity: Polarity) = Resolver.resolve(
            vocabulary,
            Sentence.of(listOf(Constraint(lush, Scope.Confined(setOf(Aspect.BIOMES)), polarity))),
            SAMPLE_SEED,
        ).composition

        val onlyLush = said(Polarity.ONLY)
        check(Biomes.keepsOnlyNamed(onlyLush.optionsFor(Aspect.BIOMES, 0))) {
            "'only verdant' did not single anything out: ${onlyLush.optionsFor(Aspect.BIOMES, 0)}"
        }
        check(preferences(onlyLush).any { it.biome.path == "lush_caves" }) {
            "'only verdant' kept nothing lush: ${preferences(onlyLush)}"
        }

        val exceptLush = preferences(said(Polarity.EXCEPT))
        check(exceptLush.any { it.biome.path == "lush_caves" && it.removes }) {
            "'except verdant' left the lush caves standing: $exceptLush"
        }
    }

    /** Naming a biome means *more of it*, every biome being present already — see `Biomes.GROWN`. */
    test("naming a biome asks for more of it than an ordinary Age has") {
        val said = Constraint(biomeWord("cherry_grove"), Scope.Confined(setOf(Aspect.BIOMES)))
        val composition = Resolver.resolve(vocabulary, Sentence.of(listOf(said)), SAMPLE_SEED).composition
        val named = preferences(composition).firstOrNull { it.biome.path == "cherry_grove" }
            ?: error("naming the cherry groves said nothing about them")
        check(named.weight == BiomePreference.WEIGHT_OF_A_MENTION) {
            "a mention was worth ${named.weight}, not ${BiomePreference.WEIGHT_OF_A_MENTION}"
        }
    }

    /**
     * **A skin is not what the rock is made of.** `bare` lived on the biomes aspect and could only turn the
     * skin *off*, so a granite body under a blackstone skin — one block for the bulk and another for the
     * face — was a thing the language could not say. It is one sentence now, and the two materials land in
     * different aspects from the same word.
     */
    test("the ground can wear one rock over another") {
        val said = read(listOf("age", "landmass", "worn", "granite", "surface", "blackstone"))
        val composition = Resolver.resolve(vocabulary, said, SAMPLE_SEED).composition
        val body = composition.optionsFor(Aspect.TERRAIN, 0).allOf(Terrain.STONE)
        val skin = composition.optionsFor(Aspect.SURFACE, 0).allOf(Surface.MATERIAL)
        check(body == listOf("minecraft:granite")) { "the rock came out $body" }
        check(skin == listOf("minecraft:blackstone")) { "the skin came out $skin" }
    }

    /** And a skin of air is how a writer says the ground wears nothing — `open`'s idiom, for the surface. */
    test("a surface of air is no surface at all") {
        val said = read(listOf("age", "surface", "air"))
        val composition = Resolver.resolve(vocabulary, said, SAMPLE_SEED).composition
        val skin = composition.optionsFor(Aspect.SURFACE, 0).allOf(Surface.MATERIAL)
        check(skin == listOf("minecraft:air")) { "'surface air' wrote $skin" }
    }

    /**
     * **What is placed is a population like any other**, and the third one to arrive: a feature is named,
     * struck out, or singled out, and the words for it come free from the registry (§8.1).
     *
     * §7.2 is what makes this load-bearing rather than decoration — "write an Age that supplies an ink
     * farm" is a sentence about what is *in* the ground.
     */
    test("what an Age places can be written") {
        fun places(vararg said: Pair<String, Polarity>): Population {
            val constraints = said.map { (path, polarity) ->
                Constraint(featureWord(path), Scope.Confined(setOf(Aspect.FEATURES)), polarity)
            }
            val resolved = Resolver.resolve(vocabulary, Sentence.of(constraints), SAMPLE_SEED)
            return Population.of(resolved.composition.optionsFor(Aspect.FEATURES, 0).claimsOn(Features.PLACES))
        }

        val plainly = places("ore_diamond" to Polarity.ASSERTED)
        check(plainly.wanted.map { it.value } == listOf("minecraft:ore_diamond")) {
            "naming a feature gave ${plainly.wanted}"
        }
        check(!plainly.exclusive) { "a plain mention pinned what is placed, which naming must never do" }

        val struckOut = places("lake_lava_surface" to Polarity.EXCEPT)
        check(struckOut.struck == listOf("minecraft:lake_lava_surface")) { "'except' struck ${struckOut.struck}" }

        val singledOut = places("ore_diamond" to Polarity.ONLY)
        check(singledOut.exclusive) { "'only ore_diamond' did not single anything out" }
    }

    /**
     * **`and` is what a population needs the conjunction *for*.** Unjoined claims already union, so
     * without it the word would say nothing here — what it says is that everything it joins was singled
     * out together.
     *
     * So `only slime and teeming cows` keeps both and closes the door behind them, and `only slime,
     * teeming cows` is a writer who singled out the slimes and then asked for something else as well. The
     * second is charged (§3.2) rather than refused: the pen never rejects, and ignoring one of the two
     * would be exactly the silent drop §3.3 forbids.
     */
    test("only beside an unjoined mention is a contradiction") {
        fun spoken(joined: Boolean): Resolution {
            val group = if (joined) Group(1) else null
            val said = listOf(
                Constraint(spawnWord("slime"), Scope.Confined(setOf(Aspect.SPAWNS)), Polarity.ONLY, group),
                Constraint(spawnWord("cow"), Scope.Confined(setOf(Aspect.SPAWNS)), Polarity.ASSERTED, group),
            )
            return Resolver.resolve(vocabulary, Sentence.of(said), SAMPLE_SEED)
        }

        val together = spoken(joined = true)
        check(together.instability.flaws.none { it.register == Register.DISPLACED }) {
            "'only slime and cows' was charged: ${together.instability.flaws}"
        }

        val apart = spoken(joined = false)
        val crowded = apart.instability.flaws.filter { it.register == Register.DISPLACED }
        check(crowded.size == 1 && crowded.single().words.contains("cow")) {
            "'only slime, cows' was not charged for the cows: ${apart.instability.flaws}"
        }
        // And both survive regardless: the world honours what it can and reports what it cannot.
        val kept = Population.of(apart.composition.optionsFor(Aspect.SPAWNS, 0).claimsOn(Spawns.LIVES))
        check(kept.wanted.map { it.value }.containsAll(listOf("minecraft:slime", "minecraft:cow"))) {
            "a charged contradiction dropped one of its halves: ${kept.wanted}"
        }
    }

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
/** Every span an Age's one climate bounds, for the properties about bounding and bending. */
private fun climateOf(resolution: Resolution): List<Span> =
    resolution.composition.climates.single().spelled().mapNotNull { Span.read(it.substringAfter('=')) }

/** The amounts `art/grammar/`'s quantifier pages ask for, plus the one that asks for nothing. */
private val RUNGS = listOf(Rung.ORDINARY, 0.25, 2.25, 4.0)

private fun resolve(vocabulary: Vocabulary, sentence: String, seed: Long = SAMPLE_SEED): Resolution {
    val words = sentence.split(" ").filter(String::isNotBlank).map { name ->
        vocabulary.word(name) ?: error("the shipped vocabulary has no word '$name'")
    }
    // Flat, so every property below still asks what it always asked: these are sentences without
    // structure, which is what a book was before the grammar existed. Structure has its own check.
    return Resolver.resolve(vocabulary, Sentence.flat(words), seed)
}

/**
 * How many distinct Ages a sentence gives across many seeds.
 *
 * **Sampled far more widely than the counting checks**, because this one measures a *difference* between
 * two spreads rather than a rate. At forty seeds both sentences came back in the high thirties — the
 * question was being asked against a ceiling of forty, so the honest margin between them was one Age and
 * any change to the draw pool could tip it either way. The ceiling has to sit well above both.
 */
private fun spread(vocabulary: Vocabulary, sentence: String): Int =
    (1L..SEEDS_FOR_A_SPREAD).map { seed -> resolve(vocabulary, sentence, seed).composition }.toSet().size

/** A word that only sets the terrain's stone — what §3.2 calls a material. */
private fun material(name: String, block: String) = Word(
    Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
    Tier.EXACT,
    setOf(Aspect.TERRAIN),
    emptyMap(),
    null,
    mapOf(Terrain.STONE.name to block),
)

/** A word that asks for one vanilla structure set by name. */
/** A creature word as §8 derives one. */
private fun spawnWord(path: String) = Word(
    Identifier.withDefaultNamespace(path),
    Tier.EXACT,
    setOf(Aspect.SPAWNS),
    emptyMap(),
    null,
    mapOf(Spawns.LIVES.name to "minecraft:$path"),
)

/** A feature word as §8 derives one — what every placed feature in the pack gets. */
private fun featureWord(path: String) = Word(
    Identifier.withDefaultNamespace(path),
    Tier.EXACT,
    setOf(Aspect.FEATURES),
    emptyMap(),
    null,
    mapOf(Features.PLACES.name to "minecraft:$path"),
)

/** A biome word as §8 derives one — the shape `DerivedWords.biomes` gives every biome in the pack. */
private fun biomeWord(path: String) = Word(
    Identifier.withDefaultNamespace(path),
    Tier.EXACT,
    setOf(Aspect.BIOMES),
    emptyMap(),
    null,
    mapOf(Biomes.GROWN.name to "minecraft:$path"),
)

private fun structureSet(path: String) = Word(
    Identifier.withDefaultNamespace(path),
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

/** See [spread]: a difference between two counts needs headroom that a rate does not. */
private const val SEEDS_FOR_A_SPREAD = 600L

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

/** The biomes a composition was told to grow, as the world will read them. */
/** What a composition says about building, as a recipe holds it. */
private fun builtIn(composition: AgeComposition) =
    composition.optionsFor(Aspect.STRUCTURES, 0).allOf(Structures.BUILT)

private fun preferences(composition: AgeComposition) =
    Biomes.preferencesIn(composition.optionsFor(Aspect.BIOMES, 0))

/** As much of the world as one member may be talked into taking — `Resolver.MOST_OF_A_WORLD`. */
private const val MOST_OF_A_WORLD = 4.0

/** What `twinned` asks for, and the one number in this file that is a count rather than a weight. */
private const val TWO_SUNS = 2

/** A third of the strongest claim, as a share spells it. */
private const val A_THIRD = 0.33

/** A claim so faint that nothing but the floor keeps its territory on the map at all. */
private const val A_CLAIM_BARELY_MADE = 0.001

/** More territories than an aspect has ever divided into, so the floor is checked past where it is used. */
private const val MOST_TERRITORIES = 8

/** The floor lands a share exactly on the bound, so meeting it is a neighbourhood rather than a point. */
private const val SLACK = 1e-9
