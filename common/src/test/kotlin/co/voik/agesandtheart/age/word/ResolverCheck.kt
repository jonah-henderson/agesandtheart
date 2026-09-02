package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgeGeneration
import co.voik.agesandtheart.worldgen.biome.ClimateAxis
import co.voik.agesandtheart.age.AgeTemplate
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Carvers
import co.voik.agesandtheart.age.aspect.Claim
import co.voik.agesandtheart.age.aspect.Rung
import co.voik.agesandtheart.age.aspect.Polarity
import co.voik.agesandtheart.age.aspect.Skew
import co.voik.agesandtheart.age.aspect.Sea
import co.voik.agesandtheart.age.aspect.Share
import co.voik.agesandtheart.age.aspect.Sky
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.aspect.Structures
import co.voik.agesandtheart.age.aspect.Terrain
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import co.voik.ephemeris.sky.Orbit
import co.voik.ephemeris.sky.SkySpec
import co.voik.agesandtheart.age.word.grammar.Constraint
import co.voik.agesandtheart.age.word.grammar.Group
import co.voik.agesandtheart.age.word.grammar.Phrase
import co.voik.agesandtheart.age.word.grammar.Sentence
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.resources.Identifier
import co.voik.agesandtheart.age.aspect.Biomes
import co.voik.agesandtheart.worldgen.biome.BiomePreference
import co.voik.agesandtheart.age.aspect.Atmosphere
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
        Vocabulary.load(MinecraftRegistries.shippedData(), MinecraftRegistries.worldgen).also {
            check(it.problems.isEmpty()) { "vocabulary problems: ${it.problems}" }
        }
    }

    /** A book, read — null being a row that forgot the `age` page, which is a fixture bug (§4.3.1). */
    fun read(pages: List<String>) = Grammar.read(vocabulary, pages) ?: error("not a book: $pages")

    /**
     * **A ramp keeps the order it was written in, and a wall of two rocks does not.**
     *
     * This is the one place written order means anything beyond which template a book starts from, and it
     * is bought by a single flag on the parameter ([co.voik.agesandtheart.age.aspect.Parameter.keepsWrittenOrder])
     * read in one place. Both halves matter: an aurora's colours run from its crown to its hem and the
     * writer said which was which, where two rocks in one wall are a set and ranking them by tier and seed
     * is what spreads two Ages written alike.
     *
     * Driven through a real sentence rather than hand-built options, because the ordering happens in the
     * resolver and everything downstream would pass a reversed ramp without complaint.
     */
    /**
     * **A clause sited in a biome does not compete with one that is not.**
     *
     * A confined dial is a second value that wins in one corner ([Options.of]) rather than a second opinion
     * about the same thing, so the two are not rivals and neither displaces the other. Contending across
     * them charged a sentence for a contradiction it had not made and quietly dropped half of what the
     * writer had paid for (Jonah, 2026-08-30, walked).
     */
    test("a colour everywhere and a colour in one biome are both kept") {
        val pages = listOf("age", "blue", "grass", "purple", "grass", "in", "swamp")
        val resolved = Resolver.resolve(vocabulary, read(pages), SAMPLE_SEED)
        val options = resolved.composition.optionsFor(Aspect.GRASS, 0)
        val swamp = Identifier.fromNamespaceAndPath("minecraft", "swamp")
        check(options.of(Atmosphere.GRASSCOLOUR) == "blue") {
            "the unsited colour did not hold everywhere: ${options.allSpelled(Atmosphere.GRASSCOLOUR.name)}"
        }
        check(options.of(Atmosphere.GRASSCOLOUR, swamp) == "purple") {
            "the sited colour did not win in its corner: ${options.allSpelled(Atmosphere.GRASSCOLOUR.name)}"
        }
        check(resolved.instability.flaws.none { it.register == Register.DISPLACED }) {
            "the sentence was charged for displacing something: ${resolved.instability.flaws}"
        }
    }

    /** And two sited in the *same* biome still contend, because they do both apply in one place. */
    test("two colours in one biome are still rivals") {
        val pages = listOf("age", "blue", "grass", "in", "swamp", "purple", "grass", "in", "swamp")
        val resolved = Resolver.resolve(vocabulary, read(pages), SAMPLE_SEED)
        check(resolved.instability.flaws.any { it.register == Register.DISPLACED }) {
            "two colours claiming one swamp cost the sentence nothing: ${resolved.instability.flaws}"
        }
    }

    test("an aurora's colours keep the order the writer wrote them in") {
        fun ramp(vararg colours: String): List<String> {
            val pages = listOf("age") + colours.toList().flatMap { listOf(it, "and") }.dropLast(1) + "aurora"
            val resolved = Resolver.resolve(vocabulary, read(pages), SAMPLE_SEED)
            return resolved.composition.optionsFor(Aspect.AURORA, 0).allOf(Sky.AURORACOLOUR)
        }

        check(ramp("red", "green", "blue") == listOf("red", "green", "blue")) {
            "'red and green and blue aurora' gave ${ramp("red", "green", "blue")}"
        }
        check(ramp("blue", "green", "red") == listOf("blue", "green", "red")) {
            "the same three the other way round gave ${ramp("blue", "green", "red")}"
        }
        check(ramp("red", "green") != ramp("green", "red")) {
            "two colours either way round gave one ramp, so the writer's order is being thrown away"
        }
    }

    /**
     * And the flag reaches exactly one parameter, so nothing else changed shape underneath it.
     *
     * A mingling parameter that is a *set* must go on being ranked by tier and then by seed — that is what
     * spreads two Ages written alike, and quietly making every mingling ordered would have taken it away
     * everywhere at once.
     */
    test("only a band keeps its written order") {
        // Two parameters hold a sequence where every other mingling parameter holds a set, and both are bands read
        // end to end: a curtain from its crown to its hem, a bow from its outside in. A third arriving here
        // is a parameter that has been given an order it has no way to mean.
        val ordered = Aspect.entries.flatMap { it.parameters }.filter { it.keepsWrittenOrder }
        check(ordered == listOf(Sky.AURORACOLOUR, Sky.RAINBOWCOLOUR)) {
            "written order is kept by ${ordered.map { it.name }}, which is not the two that mean it"
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
            restricts = mapOf(Aspect.SKY to mapOf("moonless" to 1.0)),
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
     * **A book starts from a world** (`the-world-model.md` §4), and `An Age` is a complete book because of
     * it — silence is not neutral, it is overworld-shaped.
     */
    test("a book that names no template starts from the overworld") {
        val plain = Resolver.resolve(vocabulary, read(listOf("age")), SAMPLE_SEED)
        check(plain.template == AgeTemplate.OVERWORLD) { "an unnamed world started from ${plain.template}" }
        check(plain.composition.seas == listOf(Sea.WATER)) {
            "the overworld came up with ${plain.composition.seas} for a sea"
        }
    }

    /**
     * **`infernal` replaces the world, and the sentence is laid over it.** Naming a landform takes the
     * landform and keeps everything the writer did not speak to — the lava, the seal, the heat — which is
     * the answer to "hills with the nether's roof, or hills plain".
     */
    test("a template is what the sentence is written over") {
        val nether = Resolver.resolve(vocabulary, read(listOf("infernal", "age")), SAMPLE_SEED)
        check(nether.template == AgeTemplate.INFERNAL) { "'infernal' started from ${nether.template}" }
        check(nether.composition.seas == listOf(Sea.LAVA)) { "the nether's sea is ${nether.composition.seas}" }

        val hills = Resolver.resolve(vocabulary, read(listOf("infernal", "age", "hills", "landmass")), SAMPLE_SEED)
        check(hills.composition.terrains == listOf(Terrain.HILLS)) {
            "the writer's landform lost to the template's: ${hills.composition.terrains}"
        }
        check(hills.composition.seas == listOf(Sea.LAVA)) {
            "naming a landform took the nether's sea away as well: ${hills.composition.seas}"
        }
        check(hills.composition.optionsFor(Aspect.SKY, 0).isTrue(Sky.SEALED)) {
            "naming a landform unsealed the world, which no word asked for"
        }
    }

    /**
     * **A template's cast comes with it, and minting a body is what takes it away** (world model §4).
     *
     * The nether and the void are dark because nothing shines on them, which is a fact about their bodies
     * rather than about their air. The merge skipped every population outright, so both arrived with an
     * ordinary shining sun and the void arrived with skylight — and nothing caught it, because the checks
     * asked which template was chosen and never what the merge left.
     */
    test("a template's cast is inherited until the book mints one") {
        fun composed(vararg pages: String) =
            Resolver.resolve(vocabulary, read(listOf(*pages)), SAMPLE_SEED).composition

        fun typeOf(composition: AgeComposition) =
            Sky.dimensionType(composition.optionsFor(Aspect.SKY, 0), composition.optionsFor(Aspect.SUN, 0))

        for (template in listOf(AgeTemplate.INFERNAL, AgeTemplate.DARK_VOID)) {
            val silent = composed(template.key, "age")
            check(silent.optionsFor(Aspect.SUN, 0).isTrue(Sky.ABSENT)) {
                "${template.key} came out with a sun that shines"
            }
            check(typeOf(silent) == typeOf(template.world())) {
                "${template.key} merged to ${typeOf(silent)} rather than its own ${typeOf(template.world())}"
            }
        }

        // And the other half: a book that writes a sun of its own gets that sun and not the world's.
        val litVoid = composed("dark_void", "age", "sun")
        check(litVoid.membersIn(Aspect.SUN) == 1) { "the book minted ${litVoid.membersIn(Aspect.SUN)} suns" }
        check(!litVoid.optionsFor(Aspect.SUN, 0).isTrue(Sky.ABSENT)) {
            "a sun written into the void kept the void's dark"
        }
        check(typeOf(litVoid) == AgeGeneration.AGE_DIMENSION_TYPE) {
            "a void with a sun in it is still ${typeOf(litVoid)}"
        }
    }

    /**
     * **A claim aimed at one body stays on it** (world model §2) — the whole of what a cast is for.
     *
     * Two mechanisms had to agree and neither did. `AspectOptions.of` answered a lone stored entry for
     * *every* member, which is what an aspect whose territories agree wants and the opposite of what a roll
     * wants; and the ranged pass wrote to member 0 unless the aspect was spatial, which a cast is not. So
     * `a colossal sun` beside any second sun made both of them colossal.
     *
     * The check that should have caught it asked about two clauses setting the **same** parameter, where the
     * second write happens to overwrite the first's leak. These set different ones.
     */
    test("a claim about one body stays on it") {
        fun sizes(vararg pages: String): List<Set<String>> {
            val composition = Resolver.resolve(vocabulary, read(listOf("age", *pages)), SAMPLE_SEED).composition
            return (0..<composition.membersIn(Aspect.SUN)).map {
                composition.optionsFor(Aspect.SUN, it).allSpelled(Sky.SUNSIZE.name)
            }
        }
        // A clause that demands nothing still mints a body, and the size belongs to the other one — which
        // is why the member is read off the clause rather than off where its group sorted.
        val sized = sizes("sun", "colossal", "sun")
        check(sized.size == 2) { "two clauses minted ${sized.size} suns" }
        check(sized[0].isEmpty() && sized[1].isNotEmpty()) { "'a sun. a colossal sun.' sized $sized" }

        // And the other way about, with a different parameter on the body that was not sized.
        val andBack = sizes("colossal", "sun", "rising_east", "sun")
        check(andBack[0].isNotEmpty() && andBack[1].isEmpty()) {
            "'a colossal sun. an east-rising sun.' sized $andBack"
        }
    }

    /**
     * **Two climates asked for apart cost ink, not instability** (world model §2).
     *
     * A climate is a population now, so a clause closing on `climate` mints one of them — and two clauses
     * are a writer asking for two regions, which is `and`'s rule arriving by another route: "keep both, and
     * keep them apart". What is charged is a world that had to come apart to hold what *one* clause said,
     * and both halves are here because the free one is worthless if the charged one went free with it.
     */
    test("a climate described twice divides for nothing, and one that argues pays") {
        val asked = Resolver.resolve(
            vocabulary,
            read(listOf("age", "frozen", "climate", "scorching", "climate")),
            SAMPLE_SEED,
        )
        check(asked.composition.membersIn(Aspect.CLIMATE) == 2) {
            "two clauses made ${asked.composition.membersIn(Aspect.CLIMATE)} climates"
        }
        check(asked.instability.flaws.none { it.register == Register.FRACTURE }) {
            "a division the writer asked for was charged: ${asked.instability.flaws}"
        }
        // Each region keeps only what its own clause said, which is what makes them two regions at all.
        val temperatures = (0..<2).map { asked.composition.optionsFor(Aspect.CLIMATE, it).of(ClimateAxis.TEMPERATURE.parameter) }
        check(temperatures.distinct().size == 2) { "both climates came out at $temperatures" }

        // And one clause holding both words is a world coming apart to satisfy it, which is charged.
        val argued = Resolver.resolve(
            vocabulary,
            read(listOf("age", "frozen", "scorching", "climate")),
            SAMPLE_SEED,
        )
        check(argued.instability.flaws.any { it.register == Register.FRACTURE }) {
            "'frozen scorching climate' fractured for free: ${argued.instability.flaws}"
        }
    }

    /**
     * **A rung reaches a member a tag chose, not only one a word named** — `teeming herds` against
     * `scarce herds`, where `herds` names no creature and picks every grazing one.
     *
     * This was a silent drop, which is the failure §3.3 forbids above all others. A quantifier travels on
     * the claim, and the claim a *named* member makes is written somewhere the amount was already read; a
     * member chosen by a query went through a different path where nothing had ever looked. So `frequent
     * plants` lost its `frequent` and said so nowhere.
     */
    test("a rung reaches a member a tag chose") {
        fun grazingWeight(sentence: List<String>): Double {
            val resolved = Resolver.resolve(vocabulary, read(sentence), SAMPLE_SEED).composition
            val claims = Skew.of(resolved.optionsFor(Aspect.SPAWNS, 0).claimsOn(Spawns.LIVES))
            val cow = claims.wanted.firstOrNull { it.value == "minecraft:cow" }
            return cow?.density ?: error("'${sentence.joinToString(" ")}' asked for no cows at all")
        }
        val plain = grazingWeight(listOf("age", "herds", "spawns"))
        val many = grazingWeight(listOf("age", "teeming", "herds", "spawns"))
        val few = grazingWeight(listOf("age", "scarce", "herds", "spawns"))

        check(many > plain) { "'teeming herds' asked for $many where plain herds asked $plain" }
        // And the other way, which is the half that adding rather than scaling would have got wrong: a
        // quarter of an ordinary claim is less than the world would have had, where a quarter *added* to
        // one is still more.
        check(few < plain) { "'scarce herds' asked for $few where plain herds asked $plain" }
    }

    /**
     * **A template brings vanilla's rock, and naming a landform takes it away** — the either/or the whole
     * hybrid rests on (`the-art-implementation-plan.md`, "Vanilla's own terrain under an Age").
     *
     * `landmass=vanilla` says the rock is not ours; the recipe's template says which vanilla. A writer who
     * names any shape of ours replaces it and the field tree answers instead — aquifers and preliminary
     * surface included, because vanilla's router answers for all three together or none of them.
     */
    test("a template brings vanilla's rock, and a landform takes it away") {
        val nether = Resolver.resolve(vocabulary, read(listOf("infernal", "age")), SAMPLE_SEED)
        check(nether.composition.terrains == listOf(Terrain.VANILLA)) {
            "an unshaped infernal Age came out on ${nether.composition.terrains}"
        }
        check(nether.template.rock == NoiseGeneratorSettings.NETHER) {
            "the infernal template points at ${nether.template.rock}"
        }

        val shaped = Resolver.resolve(vocabulary, read(listOf("infernal", "age", "hills", "landmass")), SAMPLE_SEED)
        check(shaped.composition.terrains == listOf(Terrain.HILLS)) {
            "naming a landform did not take the template's rock away: ${shaped.composition.terrains}"
        }
        // And what the writer said about everything else is still laid over the template underneath.
        check(shaped.composition.seas == listOf(Sea.LAVA)) {
            "shaping the land took the nether's sea with it: ${shaped.composition.seas}"
        }
    }

    /**
     * **The sentence the whole pass was built for** (world model §2): *a large, red, east-rising sun. A
     * small, blue, southwest-rising sun.* Two clauses, two suns, each wearing only what its own clause said.
     *
     * Every part of it is something that could not be written before. The count is gone, so the number of
     * bodies is the number of clauses; size and colour were one value over the whole sky; and where a body
     * rose could not be said at all.
     */
    test("two suns are described apart") {
        // Colour still has no word for a sun; size does, and it is `colossal` — see below.
        // Only the horizons here, because — the parameters are reachable
        // and nothing in the corpus turns them, which is the hand-tuned vocabulary pass's to fix. What this
        // holds is the machinery: two clauses, two bodies, each steered on its own.
        val read = read(listOf("age", "rising_east", "sun", "rising_southwest", "sun"))
        check(read.dropped.isEmpty()) { "the two-sun book lost pages: ${read.dropped}" }
        val composition = Resolver.resolve(vocabulary, read, SAMPLE_SEED).composition
        check(composition.membersIn(Aspect.SUN) == 2) {
            "two clauses minted ${composition.membersIn(Aspect.SUN)} suns"
        }
        val first = composition.optionsFor(Aspect.SUN, 0)
        val second = composition.optionsFor(Aspect.SUN, 1)
        check(first.of(Sky.RISING) == "east") { "the first sun rises ${first.of(Sky.RISING)}" }
        check(second.of(Sky.RISING) == "southwest") { "the second sun rises ${second.of(Sky.RISING)}" }

        // And it reaches the sky the renderer is handed, which is the half a writer actually sees.
        val drawn = composition.sky.specFor(composition, SAMPLE_SEED)
        val suns = drawn.bodies.filter { it.phase == null }
        check(suns.size == 2) { "the spec drew ${suns.size} suns" }
        val horizons = suns.map { (it.path as Orbit).ascendingNodeDegrees }
        check(horizons.distinct().size == 2) { "both suns came up over the same horizon: $horizons" }
    }

    /**
     * **One word, two parts of the world, and the clause decides which** — the attachment rule doing the
     * job it exists for. `colossal` picks monumental landforms by tag and fills the sky by parameter, and a
     * writer who says it about a sun means the sun.
     */
    test("colossal is a colossal landform and a colossal sun") {
        val overhead = read(listOf("age", "colossal", "sun"))
        val sky = Resolver.resolve(vocabulary, overhead, SAMPLE_SEED).composition
        val sun = sky.sky.specFor(sky, SAMPLE_SEED).bodies.first { it.phase == null }
        check(sun.appearance.angularSize > SkySpec.VANILLA_SUN_SIZE) {
            "'colossal sun' drew a sun of ${sun.appearance.angularSize}, no larger than vanilla's"
        }

        // And laid on the land it is the rock that is colossal, the sun keeping whatever was drawn.
        val ground = read(listOf("age", "colossal", "landmass"))
        val rock = Resolver.resolve(vocabulary, ground, SAMPLE_SEED).composition
        val itsSun = rock.sky.specFor(rock, SAMPLE_SEED).bodies.first { it.phase == null }
        check(itsSun.appearance.angularSize == SkySpec.VANILLA_SUN_SIZE) {
            "'colossal landmass' reached the sun as well, at ${itsSun.appearance.angularSize}"
        }
    }

    /**
     * **Attachment filters a description to what the thing owns** (`the-world-model.md` §3), which is the
     * central rule of the whole model and had nothing asserting it.
     *
     * `frozen` advertises a temperature it always sets and a pool it may draw a pale vault, a small sun and
     * a thick haze from. Laid on the climate only the temperature can land, because that is all a climate
     * holds; laid on the sky, the temperature cannot. One word means the right thing in both places, and no
     * rule anywhere says so — the word carries its whole meaning and the subject decides how much applies.
     */
    test("attachment lands only what the thing it was laid on owns") {
        // The climate resolves into bands rather than options, so it is read off the composition's own
        // spans. A band narrower than the whole axis is a temperature somebody bounded.
        fun boundsTheTemperature(page: String): Boolean {
            val resolved = Resolver.resolve(vocabulary, read(listOf("age", "frozen", page)), SAMPLE_SEED)
            return climateOf(resolved).any { it.width < Span.NATURAL.width }
        }
        check(boundsTheTemperature("climate")) {
            "'frozen climate' did not reach the temperature, which is the one thing it always sets"
        }
        check(!boundsTheTemperature("sky")) {
            "'frozen sky' bounded the temperature, so attachment confined nothing"
        }
    }

    /**
     * A vague word is the cheapest thing a writer can lay — §1's ladder, which was inverted until the
     * pricing was split by tier (§4.4). An evocative word was charged per aspect it found purchase in, so
     * `beautiful` reaching eight of them cost twice what the exact `murky` did, making the vaguest word in
     * the corpus the dearest. Nothing noticed, because the only cost check compared two narrowing words.
     */
    test("vagueness is the floor, not the ceiling") {
        val vague = resolve(vocabulary, "beautiful").cost
        val precise = resolve(vocabulary, "murky").cost
        check(vague < precise) {
            "'beautiful' cost $vague and 'murky' cost $precise, so vagueness is not the cheap end"
        }
    }

    /**
     * A narrowing word at home in two parts of the world costs more than one at home in a single part —
     * §4.4's versatility charge, which prices how good a page is to own rather than what it did here.
     * `clear` is a clear sky and clear water alike where `murky` is only ever about the water, and both are
     * exact, so the tier cannot be what separates them.
     */
    test("a word at home in more places costs more") {
        val versatile = resolve(vocabulary, "clear").cost
        val narrow = resolve(vocabulary, "murky").cost
        check(versatile > narrow) {
            "'clear' cost $versatile and 'murky' cost $narrow, so being usable in two places is free"
        }
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

        val settled = Skew.of(
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
        // **What a template supplies is not a draw.** `landmass=vanilla` arrives in every Age whose writer
        // named no landform, which is the template answering rather than the resolver reaching for
        // something no page can name. What this forbids is the *drawing* of one.
        val fromATemplate = AgeTemplate.entries.flatMap { it.world().presets }
        val unaskable = Aspect.entries
            .flatMap { aspect -> vocabulary.candidatesFor(aspect) }
            .filterNot { it.askableInASentence || it in fromATemplate }
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
            val shares = composition.spreadOf(Aspect.CARVERS).shares
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
        var seasDoubled = 0
        var landformsDoubled = 0
        for (seed in 1L..HARMONY_SEEDS) {
            val resolution = resolve(vocabulary, "beautiful", seed)
            val composition = resolution.composition
            // **The sea plays the other side, not the carving.** The carving is the most companionable
            // aspect on paper (0.25 against the terrain's 0.12), but `beautiful` finds no purchase in it —
            // no carver carries `lovely`, `lush` or `bright` — so it never reaches the aspect and the
            // template answers for it instead. What that measured was an unconstrained draw rather than
            // harmony, which only showed once a template started replacing those draws.
            if (composition.seas.size > 1) seasDoubled++
            if (composition.terrains.size > 1) landformsDoubled++
            check(resolution.instability.isCoherent) {
                "\"beautiful\" at seed $seed was charged ${resolution.instability.index}: " +
                    "${resolution.instability.flaws} — liking two things is not a contradiction"
            }
        }
        check(seasDoubled >= landformsDoubled) {
            "seas doubled up $seasDoubled times against terrains' $landformsDoubled, but nothing " +
                "interpolates between two landforms and a seam is the costliest thing an Age can have"
        }
        println(
            "  \"beautiful\" over $HARMONY_SEEDS seeds: two seas $seasDoubled times, " +
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
    test("a derived word means its referent") {
        val lava = vocabulary.word("lava") ?: error("no derived word 'lava' — is derivation running?")
        check(lava.tier == Tier.EXACT) { "a derived word must be exact, not ${lava.tier.key}" }
        val meant = lava.choiceIn(Aspect.SEA)
        check(meant == Sea.LAVA) { "'lava' means $meant in the sea, not ${Sea.LAVA.key}" }
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
        val land = Constraint(hollow, setOf(Aspect.TERRAIN))
        val first = material("firststone", "minecraft:blackstone")
        val second = material("secondstone", "minecraft:tuff")
        fun sentence(group: Group?) = Sentence.of(
            listOf(
                land,
                Constraint(first, setOf(Aspect.TERRAIN), group = group),
                Constraint(second, setOf(Aspect.TERRAIN), group = group),
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
        fun asked(vararg said: Pair<String, Polarity>): Skew {
            val constraints = said.map { (path, polarity) ->
                Constraint(structureSet(path), setOf(Aspect.STRUCTURES), polarity)
            }
            val resolved = Resolver.resolve(vocabulary, Sentence.of(constraints), SAMPLE_SEED)
            return Skew.of(resolved.composition.optionsFor(Aspect.STRUCTURES, 0).claimsOn(Structures.BUILT))
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
            setOf(Aspect.SKY),
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
        val said = listOf(Constraint(vocabulary.word("starless")!!, setOf(Aspect.SKY)))

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
     * **Every way of speaking to a population reaches it**, which three of the five did not.
     *
     * A population's members are stored on its aspect's own weighted parameter — `spawns.lives`,
     * `structures.built` — and `weighed` compiles what a sentence says into it. Three of the steps were
     * never getting that far: a narrowing word's lean was dropped where an evocative one's counted, the
     * pool was taken from curation alone so nothing admitted could be reached, and a word that chose a
     * member was skipped outright on the grounds it had already been written elsewhere.
     */
    test("a population hears every step a word takes") {
        val zombie = "minecraft:zombie"
        fun living(word: Word): List<String> = Resolver
            .resolve(vocabulary, Sentence.flat(listOf(word)), SAMPLE_SEED)
            .composition.optionsFor(Aspect.SPAWNS, 0).allOf(Spawns.LIVES)

        val leaning = Word(
            Identifier.fromNamespaceAndPath("test", "leaning"),
            Tier.EXACT,
            setOf(Aspect.SPAWNS),
            biases = mapOf(Aspect.SPAWNS to mapOf(zombie to 1.0)),
        )
        check(living(leaning).isNotEmpty()) { "a narrowing word's lean reached the spawns not at all" }

        val choosing = Word(
            Identifier.fromNamespaceAndPath("test", "choosing"),
            Tier.EXACT,
            setOf(Aspect.SPAWNS),
            chooses = mapOf(Aspect.SPAWNS to zombie),
        )
        check(living(choosing) == listOf(zombie)) { "choosing gave ${living(choosing)}" }
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
                setOf(Aspect.STRUCTURES),
                density = rung,
            )
            val resolved = Resolver.resolve(vocabulary, Sentence.of(listOf(said)), SAMPLE_SEED)
            val population = Skew.of(
                resolved.composition.optionsFor(Aspect.STRUCTURES, 0).claimsOn(Structures.BUILT),
            )
            return population.wanted.singleOrNull() ?: error("'villages' at $rung gave ${population.wanted}")
        }

        // **It scales the mention rather than replacing it.** Naming a member is already a claim on the
        // world, so `teeming villages` is that claim four times over — where reading the rung *as* the
        // claim would have made `teeming` ask for less than the bare mention it was written on.
        val unquantified = askedFor(Rung.ORDINARY).density
        for (rung in RUNGS) {
            val claim = askedFor(rung)
            check(claim.value == "minecraft:villages") { "the rung ate the value: ${claim.value}" }
            check(claim.density == Rung.legible(unquantified * rung)) {
                "asking for $rung villages gave ${claim.density}, against $unquantified for a bare mention"
            }
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
     * **A word that turns a numeric parameter has to land where it means**, end to end: the word bounds an
     * axis, a value is drawn inside it, and the sky is built from that. Nothing else checks the middle
     * step, and a span merely *near* the end of its axis reads as a working word — `starless` written as
     * a stretch rather than a point left a sky with two hundred stars in it, and every other check passed.
     */
    test("a sky word lands where it says") {
        fun skyOf(sentence: String): SkySpec {
            val composition = resolve(vocabulary, sentence).composition
            return composition.sky.specFor(composition, SAMPLE_SEED)
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

        // Brilliance is the field's other axis, and the two must not be one word between them: a sky
        // burns brighter without gaining a star, and gains stars without burning brighter.
        check(ordinary.stars.glow == SkySpec.ORDINARY_STAR_GLOW) {
            "a sky nobody spoke to about stars burns at ${ordinary.stars.glow} times vanilla's"
        }
        check(skyOf("glimmering").stars.glow > SkySpec.ORDINARY_STAR_GLOW) {
            "'glimmering' burns at ${skyOf("glimmering").stars.glow} times vanilla's, which is no brighter"
        }
        check(skyOf("glimmering").stars.count == SkySpec.VANILLA_STAR_COUNT) {
            "'glimmering' also added stars (${skyOf("glimmering").stars.count}), so it says two things"
        }
        check(skyOf("starlit").stars.glow == SkySpec.ORDINARY_STAR_GLOW) {
            "'starlit' also brightened them, so the two axes are one word between them"
        }

        // And a body is minted by the clause that describes it, so two clauses are two suns — there is no
        // count to write, which is what stops `two suns` and `a sun, a sun` being two spellings for one
        // thing (world model §2).
        val twoSuns = read(listOf("age", "sun", "sun"))
        val minted = Resolver.resolve(vocabulary, twoSuns, SAMPLE_SEED).composition
        val drawn = minted.sky.specFor(minted, SAMPLE_SEED)
        check(drawn.bodies.count { it.phase == null } == TWO_SUNS) {
            "two `sun` clauses drew ${drawn.bodies.count { it.phase == null }} suns"
        }
        check(skyOf("sunless").bodies.none { it.phase == null }) { "'sunless' left a sun overhead" }
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
            Sentence.of(listOf(Constraint(lush, setOf(Aspect.BIOMES), polarity))),
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
        val said = Constraint(biomeWord("cherry_grove"), setOf(Aspect.BIOMES))
        val composition = Resolver.resolve(vocabulary, Sentence.of(listOf(said)), SAMPLE_SEED).composition
        val named = preferences(composition).firstOrNull { it.biome.path == "cherry_grove" }
            ?: error("naming the cherry groves said nothing about them")
        // **Worth more than ordinary, without the pool naming a number.** A mention used to be scaled by a
        // constant the biome pool carried for itself; a chosen member is now worth what its word's tier is
        // worth, like every other claim on a population.
        check(named.weight > Rung.ORDINARY) { "a mention was worth ${named.weight}, no more than ordinary" }
    }

    /**
     * **A skin is not what the rock is made of.** `bare` lived on the biomes aspect and could only turn the
     * skin *off*, so a granite body under a blackstone skin — one block for the bulk and another for the
     * face — was a thing the language could not say. It is one sentence now, and the two materials land in
     * different aspects from the same word.
     */
    test("the ground can wear one rock over another") {
        val said = read(listOf("age", "worn", "granite", "landmass", "blackstone", "surface"))
        val composition = Resolver.resolve(vocabulary, said, SAMPLE_SEED).composition
        val body = composition.optionsFor(Aspect.TERRAIN, 0).allOf(Terrain.STONE)
        val skin = composition.optionsFor(Aspect.SURFACE, 0).allOf(Surface.MATERIAL)
        check(body == listOf("minecraft:granite")) { "the rock came out $body" }
        check(skin == listOf("minecraft:blackstone")) { "the skin came out $skin" }
    }

    /**
     * And a skin of air is how a writer says the ground wears nothing — `open`'s idiom, for the surface.
     *
     * **Said by its full id**, because `air` is an aiming page now and a page beats a block: the aspect is
     * the thing a writer overwhelmingly means by the word, and `minecraft:air` still reaches the block.
     */
    test("a surface of air is no surface at all") {
        val said = read(listOf("age", "minecraft:air", "surface"))
        val composition = Resolver.resolve(vocabulary, said, SAMPLE_SEED).composition
        val skin = composition.optionsFor(Aspect.SURFACE, 0).allOf(Surface.MATERIAL)
        check(skin == listOf("minecraft:air")) { "'surface minecraft:air' wrote $skin" }
    }

    /**
     * **What is placed is a population like any other**, and the third one to arrive: a feature is named,
     * struck out, or singled out, and the words for it come free from the registry (§8.1).
     *
     * §7.2 is what makes this load-bearing rather than decoration — "write an Age that supplies an ink
     * farm" is a sentence about what is *in* the ground.
     */
    test("what an Age places can be written") {
        fun places(vararg said: Pair<String, Polarity>): Skew {
            val constraints = said.map { (path, polarity) ->
                Constraint(featureWord(path), setOf(Aspect.FEATURES), polarity)
            }
            val resolved = Resolver.resolve(vocabulary, Sentence.of(constraints), SAMPLE_SEED)
            return Skew.of(resolved.composition.optionsFor(Aspect.FEATURES, 0).claimsOn(Features.PLACES))
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
                Constraint(spawnWord("slime"), setOf(Aspect.SPAWNS), Polarity.ONLY, group),
                Constraint(spawnWord("cow"), setOf(Aspect.SPAWNS), Polarity.ASSERTED, group),
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
        val kept = Skew.of(apart.composition.optionsFor(Aspect.SPAWNS, 0).claimsOn(Spawns.LIVES))
        check(kept.wanted.map { it.value }.containsAll(listOf("minecraft:slime", "minecraft:cow"))) {
            "a charged contradiction dropped one of its halves: ${kept.wanted}"
        }
    }

    test("a fracture obeys its guards") {
        fun aimedAtTheLand(word: Word) = Constraint(word, setOf(Aspect.TERRAIN))
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
    id = Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
    tier = Tier.EXACT,
    aspects = setOf(Aspect.TERRAIN),
    sets = mapOf(Terrain.STONE.name to block),
)

/**
 * A word that names one member of a population outright, which is the shape `DerivedWords.choosing` gives
 * every biome, feature, creature and structure set in the pack.
 */
private fun choosingWord(path: String, aspect: Aspect) = Word(
    id = Identifier.withDefaultNamespace(path),
    tier = Tier.EXACT,
    aspects = setOf(aspect),
    chooses = mapOf(aspect to "minecraft:$path"),
)

private fun spawnWord(path: String) = choosingWord(path, Aspect.SPAWNS)

private fun featureWord(path: String) = choosingWord(path, Aspect.FEATURES)

private fun biomeWord(path: String) = choosingWord(path, Aspect.BIOMES)

private fun structureSet(path: String) = choosingWord(path, Aspect.STRUCTURES)

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
