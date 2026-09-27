package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus.resolved
import co.voik.agesandtheart.ShippedCorpus.vocabulary
import co.voik.agesandtheart.age.Flaw
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.Word
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.worldgen.feature.FeatureDensity
import co.voik.agesandtheart.worldgen.feature.FeatureShape
import co.voik.agesandtheart.worldgen.feature.OreVein
import net.minecraft.core.Holder
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.OreFeature
import net.minecraft.world.level.levelgen.feature.SpringFeature
import net.minecraft.world.level.levelgen.feature.IcebergFeature
import net.minecraft.world.level.material.Fluids
import co.voik.agesandtheart.worldgen.feature.SpilledSpring
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import java.io.File
import co.voik.agesandtheart.worldgen.feature.Heap
import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.minecraft.world.level.levelgen.placement.PlacementModifier
import net.minecraft.world.level.levelgen.placement.OffsetPlacement
import net.minecraft.world.level.levelgen.placement.CountPlacement

/**
 * **A pattern made of something it is never made of** (world model §2) — `ink springs`, `gold block
 * veins`. Two halves that meet in a claim, and both are checked here because either alone is silent: the
 * sentence has to reach a claim carrying a substance, and the claim has to reach a feature that is made of
 * it.
 *
 * Vanilla materials throughout, because the offline registries hold vanilla's blocks and not ours. What is
 * being asked is whether the substance travels, and lava travels the same way ink does.
 */
@Tags(NEEDS_REGISTRIES)
class MintingCheck : FunSpec({

    /** What a book asking for one minted feature leaves in [Features.PLACES]. */
    fun placed(vararg pages: String): Set<String> {
        val composition = resolved(SAMPLE_SEED, *pages).composition
        return composition.optionsFor(Aspect.FEATURES, 0).allSpelled(Features.PLACES.name)
    }

    /** Where a book left the features aspect's own size dial. */
    fun sizeOf(vararg pages: String): String {
        val composition = resolved(SAMPLE_SEED, *pages).composition
        return composition.optionsFor(Aspect.FEATURES, 0).of(Features.SIZE)
    }

    test("a material and a pattern said together mint one") {
        val grown = placed("lava", "springs")
        check(grown.any { it.startsWith("minecraft:spring_water") && "of=minecraft:lava" in it }) {
            "'lava springs' should have minted a lava-carrying spring, and left $grown"
        }
    }

    /** What a book resolved to as instability, so a charge can be checked rather than only a claim. */
    fun flawsOf(vararg pages: String): List<Flaw> = resolved(SAMPLE_SEED, *pages).instability.flaws

    /**
     * **A spring cannot run with a solid, and the writer is told what it cost.**
     *
     * Generation leaves the pattern alone — a spring rebuilt around `Fluids.EMPTY` places nothing at all,
     * which is worse than ordinary water — so the material is dropped, and until this it was dropped in
     * silence with the page paid for either way (Jonah, 2026-08-25, walked).
     */
    test("a spring asked to run with a solid charges for the material it lost") {
        val displaced = flawsOf("gold_block", "springs").filter { it.register == Register.DISPLACED }
        check(displaced.size == 1) { "'gold_block springs' charged ${flawsOf("gold_block", "springs")}" }

        val flaw = displaced.single()
        check(flaw.words.first() == "gold_block") {
            "the flaw names ${flaw.words} — the material is the word that lost and belongs first"
        }
        check(flaw.severity > 0) { "'gold_block springs' was charged nothing" }
        check("gold_block" in flaw.describe() && "springs" in flaw.describe()) {
            "the reading does not name both words: ${flaw.describe()}"
        }
    }

    /**
     * The control, and it is the whole of what keeps the charge honest: the same shape with a fluid in it
     * must cost nothing at all, or every minted spring in the game is paying for this.
     */
    test("and a spring that can run with what it was given is charged nothing") {
        for (fluid in listOf("lava", "water")) {
            val displaced = flawsOf(fluid, "springs").filter { it.register == Register.DISPLACED }
            check(displaced.isEmpty()) { "'$fluid springs' was charged $displaced for a substance that flows" }
        }
    }

    /**
     * **A lake fills with whatever it is given**, which is the contrast that makes the spring's refusal a
     * rule about the pattern rather than about minting: a bowl of obsidian is a thing a writer may want,
     * where a spring of it is a spring that runs with nothing.
     */
    test("a lake takes a fluid or a solid, and charges for neither") {
        // No ink here, and it is not an omission: our own fluids are registered by a running mod, so
        // the offline corpus has no word for one. `ink lakes` is the walk's to see.
        for (filling in listOf("water", "obsidian", "gold_block")) {
            val grown = placed(filling, "lakes")
            check(grown.any { it.startsWith("minecraft:lake_lava_surface") && "of=minecraft:$filling" in it }) {
                "'$filling lakes' left $grown"
            }
            val displaced = flawsOf(filling, "lakes").filter { it.register == Register.DISPLACED }
            check(displaced.isEmpty()) { "'$filling lakes' was charged $displaced, and a bowl holds anything" }
        }
    }

    /** The pits a book leaves in [Features.PLACES], read back as claims. */
    fun pitsIn(vararg pages: String): List<Claim> =
        placed(*pages).map(Claim::read).filter { it.value == "agesandtheart:pits" }

    /** **Materials in a minting read as a landmass reads them**: joined with `and`, one pit of both. */
    test("materials joined in a minting mingle into one feature, and cost nothing") {
        val pits = pitsIn("mud", "and", "sand", "pits")
        check(pits.size == 1) { "'mud and sand pits' left ${pits.size} pits: $pits" }
        check(pits.single().substances == listOf("minecraft:mud", "minecraft:sand")) {
            "'mud and sand pits' is made of ${pits.single().substances}"
        }
        val displaced = flawsOf("mud", "and", "sand", "pits").filter { it.register == Register.DISPLACED }
        check(displaced.isEmpty()) { "'mud and sand pits' was charged $displaced for asking for both" }
    }

    /** Side by side without `and`, they contend: one wins and the other is charged, never silently dropped. */
    test("materials laid side by side in a minting contend") {
        val pits = pitsIn("mud", "sand", "pits")
        check(pits.size == 1 && pits.single().substances.size == 1) { "'mud sand pits' left $pits" }
        val displaced = flawsOf("mud", "sand", "pits").filter { it.register == Register.DISPLACED }
        check(displaced.size == 1) { "'mud sand pits' charged ${flawsOf("mud", "sand", "pits")}" }
    }

    /** And two kinds of pit apart are two clauses. */
    test("two minting clauses of one pattern are two features") {
        val pits = pitsIn("mud", "pits", "sand", "pits")
        check(pits.map { it.substances }.toSet() == setOf(listOf("minecraft:mud"), listOf("minecraft:sand"))) {
            "'mud pits sand pits' left $pits"
        }
    }

    /** And the pattern is really rebuilt, rather than the claim merely spelling what was asked for. */
    test("a minted lake is filled with what the clause named") {
        val pattern = MinecraftRegistries.worldgen.lookupOrThrow(Registries.PLACED_FEATURE)
            .getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse("minecraft:lake_lava_surface")))
        val obsidian = FeatureShape.mintedFrom(pattern, listOf("minecraft:obsidian"))
        check(obsidian !== pattern) { "the lake came back unminted" }
        // Through the codec, because a `BlockStateProvider`'s own `toString` is its identity and says
        // nothing about the block — which is what made the first version of this pass over a lava lake.
        val spelled = Feature.DIRECT_CODEC
            .encodeStart(JsonOps.INSTANCE, obsidian.value().feature().value())
            .getOrThrow().toString()
        check("minecraft:obsidian" in spelled) { "the minted lake is not made of obsidian: $spelled" }
    }

    /**
     * **And the world shows what was asked for**, rather than handing back the ordinary spring the writer
     * did not write: the substance seeps from the wall and sets a block or two down.
     *
     * Vanilla's `BLOCK_COLUMN` walking down from the origin, so a spill with nowhere to run places nothing
     * and one at an opening stops where the floor is. The spring's own placement is kept, so it comes as
     * often and stands where a spring would.
     */
    test("a spring given a solid spills it instead of running") {
        val spring = placedFeature("minecraft:spring_water")
        val spilled = FeatureShape.mintedFrom(spring, listOf("minecraft:gold_block"))

        check(spilled !== spring) { "a solid spring came back as the untouched pattern" }
        check(spilled.value().placement() == spring.value().placement()) {
            "the spill does not stand where a spring would: ${spilled.value().placement()}"
        }
        val made = spilled.value().feature().value()
        check(made is SpilledSpring) { "a solid spring was rebuilt as $made" }
        val substance = (made as SpilledSpring).substance
        check(substance == Blocks.GOLD_BLOCK.defaultBlockState()) { "the spill is made of $substance" }
    }

    /** And a fluid still runs, which is the half that must not have moved. */
    test("a spring given a fluid still runs with it") {
        val spring = placedFeature("minecraft:spring_water")
        val running = FeatureShape.mintedFrom(spring, listOf("minecraft:lava")).value().feature().value()
        check(running is SpringFeature) { "a lava spring stopped being a spring: $running" }
        check((running as SpringFeature).state().type === Fluids.LAVA) {
            "a lava spring runs with ${running.state().type}"
        }
    }

    /** And a pattern that never asked for a fluid takes a solid happily — `deposits` is the other minting. */
    test("a deposit is made of a solid and charges nothing") {
        val displaced = flawsOf("gold_block", "deposits").filter { it.register == Register.DISPLACED }
        check(displaced.isEmpty()) { "'gold_block deposits' was charged $displaced, and a deposit wants a solid" }
    }

    /** `veins` alone is vanilla's iron vein, and naming an ore makes it of that ore. */
    test("veins mint an ore vein, of iron unless the clause names an ore") {
        val alone = placed("veins").filter { it.startsWith("agesandtheart:veins") }
        check(alone.any { "of=minecraft:deepslate_iron_ore" in it }) { "'veins' left $alone" }
        val golden = placed("gold_ore", "veins").filter { it.startsWith("agesandtheart:veins") }
        check(golden.any { "of=minecraft:gold_ore" in it }) { "'gold_ore veins' left $golden" }
    }

    /** **`shallow veins` says where the veins are**, and leaves the Age's other ores at the height it chose. */
    test("a height in a minting clause is the clause's own") {
        val veins = placed("shallow", "veins").map(Claim::read).single { it.value == "agesandtheart:veins" }
        check((veins.height ?: 0.0) > 0.0) { "'shallow veins' carries height ${veins.height}" }
        val ageHeight = resolved(SAMPLE_SEED, "shallow", "veins").composition
            .optionsFor(Aspect.FEATURES, 0).of(Features.HEIGHT)
        val untouched = resolved(SAMPLE_SEED, "veins").composition.optionsFor(Aspect.FEATURES, 0).of(Features.HEIGHT)
        check(ageHeight == untouched) { "'shallow veins' moved the Age's own height to $ageHeight from $untouched" }
    }

    fun shippedVein(): OreVein {
        val shipped = JsonParser.parseString(File("src/main/resources/data/agesandtheart/worldgen/feature/veins.json").readText())
        return OreVein.CODEC.codec().parse(JsonOps.INSTANCE, shipped).getOrThrow()
    }

    fun veinOf(pattern: OreVein, height: Double?, density: Double = 1.0): OreVein {
        val placed = Holder.direct(PlacedFeature(Holder.direct<Feature>(pattern), emptyList()))
        val shaped = FeatureShape.reshaped(placed, null, null, height, emptyList())
        return FeatureDensity.applied(shaped, density).value().feature().value() as OreVein
    }

    /** Above mid-column a vein is vanilla's copper one: its band, its granite, and stone ores not deepslate. */
    test("a shallow vein takes the copper shape, and a deep one keeps the iron shape") {
        val shallow = veinOf(shippedVein(), height = 0.75)
        check(shallow.minY == 0 && shallow.maxY == 50) { "a shallow vein runs ${shallow.minY}..${shallow.maxY}" }
        check(shallow.filler == Blocks.GRANITE.defaultBlockState()) { "a shallow vein is strung through ${shallow.filler}" }
        check(shallow.ore == Blocks.IRON_ORE.defaultBlockState()) { "a shallow iron vein is of ${shallow.ore}" }

        val deep = veinOf(shippedVein(), height = -0.75)
        check(deep.minY == -60 && deep.filler == Blocks.TUFF.defaultBlockState()) { "a deep vein became $deep" }
    }

    /** A quantifier qualifies the page after it, so in `teeming gold_ore veins` it sits on the material. */
    test("a quantifier anywhere in a minting clause counts what it mints") {
        val veins = placed("teeming", "gold_ore", "veins").map(Claim::read).single { it.value == "agesandtheart:veins" }
        check(veins.density > Rung.ORDINARY) { "'teeming gold_ore veins' minted veins at ${veins.density}" }
    }

    /** **More veins is more of the ground they run through**, never the same pass laid twice. */
    test("an amount widens where veins run rather than repeating the pass") {
        val teeming = veinOf(shippedVein(), height = null, density = 4.0)
        check(teeming.abundance == 4.0) { "'teeming veins' has abundance ${teeming.abundance}" }
        check(OreVein.thresholdAdmitting(0.218) in 0.39..0.41) { "vanilla's share reads back as ${OreVein.thresholdAdmitting(0.218)}" }
        check(OreVein.thresholdAdmitting(0.218 * 4) < OreVein.thresholdAdmitting(0.218)) {
            "four times the ground did not lower the threshold"
        }
    }

    /** A minted vein is flecked with the raw block of its own ore, and keeps the pattern's filler and band. */
    test("a minted vein swaps its ore and raw ore, and keeps the rest") {
        // Ours, so not in the offline registries: read from the shipped file, which checks that it parses.
        val shipped = JsonParser.parseString(File("src/main/resources/data/agesandtheart/worldgen/feature/veins.json").readText())
        val was = OreVein.CODEC.codec().parse(JsonOps.INSTANCE, shipped).getOrThrow()
        val pattern = Holder.direct(PlacedFeature(Holder.direct<Feature>(was), emptyList()))
        val now = FeatureShape.mintedFrom(pattern, listOf("minecraft:gold_ore")).value().feature().value() as OreVein
        check(now.ore == Blocks.GOLD_ORE.defaultBlockState()) { "the vein is of ${now.ore}" }
        check(now.rawOre == Blocks.RAW_GOLD_BLOCK.defaultBlockState()) { "a gold vein is flecked with ${now.rawOre}" }
        check(now.filler == was.filler && now.minY == was.minY && now.maxY == was.maxY) {
            "minting moved the vein's filler or band: $now where it was $was"
        }
    }

    test("the material does not also become the rock") {
        // The whole point of the clause: `lava` is qualifying the springs, not saying what the world is
        // made of. Attachment is what keeps it there, and this is the case that would prove it lost.
        val composition = Grammar.read(vocabulary, listOf("age", "lava", "springs"))
            ?.let { Resolver.resolve(vocabulary, it, SAMPLE_SEED).composition }
            ?: error("'lava springs' is not a book")
        val rock = composition.optionsFor(Aspect.TERRAIN, 0).allSpelled(Terrain.STONE.name)
        check(rock.none { "lava" in it }) { "'lava springs' paved the world with lava: $rock" }
    }

    /**
     * A pattern says what it is made of when the clause does not ([Word.unstated]), and one that says
     * nothing still mints nothing — `springs` is the pattern with no fallback, so this is the half of the
     * rule that did not move.
     */
    test("a pattern with nothing to fall back on mints nothing") {
        check(placed("springs").none { "of=" in it }) { "'springs' minted something out of nothing" }
    }

    /**
     * **`obelisks` alone used to put nothing in the ground.** A writer laid the page, paid for it, and got
     * a world with no obelisks in it, which reads as the word being broken rather than as the sentence
     * being incomplete.
     */
    test("a pattern named alone is made of what it says it is made of") {
        val grown = placed("obelisks")
        check(grown.any { it.startsWith("agesandtheart:obelisks") }) {
            "'obelisks' grew nothing at all, and left $grown"
        }
        check(grown.single().contains("of=#agesandtheart:formation_substance")) {
            "'obelisks' should be made of the pool the word names, and was $grown"
        }
    }

    /**
     * **The size belongs to the clause, not to the aspect** — two sizes in one book contended before this,
     * and colossal won for the rings as well as for the obelisks.
     */
    test("two clauses ask for two sizes and both get them") {
        val grown = placed("colossal", "gold_block", "obelisks", "minuscule", "rings")
        val obelisks = grown.single { it.startsWith("agesandtheart:obelisks") }
        val rings = grown.single { it.startsWith("agesandtheart:rings") }

        check("of=minecraft:gold_block" in obelisks) { "the obelisks lost their substance: $obelisks" }
        check("size=1" in obelisks) { "the obelisks were not colossal: $obelisks" }
        check("size=-1" in rings) { "the rings were not minuscule: $rings" }
        check("of=#agesandtheart:formation_substance" in rings) {
            "the rings should fall back to the pool, and were $rings"
        }
    }

    /**
     * The other half of that: a size a minting clause spent is **spent**. `colossal gold_block obelisks`
     * enlarging every tree in the Age is the same surprise as `lava springs` paving the world.
     */
    test("a size spent on a minting never reaches the aspect") {
        check(sizeOf("colossal", "gold_block", "obelisks") == sizeOf()) {
            "'colossal gold_block obelisks' also resized the Age's own features"
        }
    }

    /** And a size nothing minted still steers the aspect, which is what makes the spending a scoping. */
    test("a size outside a minting still reaches the aspect") {
        check(sizeOf("colossal", "trees", "features") != sizeOf()) {
            "'colossal trees features' left the features aspect at its default size"
        }
    }

    /**
     * **Spending the size spends the size and nothing else.**
     *
     * The whole `Constraint` used to be dropped, so a word carrying a size *and* something else lost both.
     * Asked of [Word.withoutItsSize] rather than of a resolved Age, and that is deliberate: no sentence
     * could be found where the difference shows. A word written inside a minting clause is aimed at that
     * clause, so `colossal`'s restriction of the landmass is inert there whether it survives or not, and
     * `rich` — which would show it — is evocative and never attaches as a modifier at all.
     *
     * So this guards the mechanism rather than an outcome, and says so instead of dressing up a check that
     * passes either way. What it prevents is the next word to carry a size beside a live claim.
     */
    test("taking a word's size away leaves everything else it says") {
        val colossal = vocabulary.word("colossal") ?: error("no colossal in the corpus")
        val stripped = colossal.withoutItsSize()
        check(stripped.sizeAsked == null) { "the size survived: ${stripped.sizeAsked}" }
        check(stripped.restricts == colossal.restricts) {
            "colossal's restrictions went with its size: ${stripped.restricts}"
        }
        check(stripped.copy(sets = colossal.sets) == colossal) {
            "something other than the size changed"
        }
    }

    /**
     * **A clause that mints nothing spends nothing.**
     *
     * `springs`, `lakes` and `deposits` all declare `mints` with no `unstated`, so a clause naming one without
     * a material mints nothing at all. Charging it for the size anyway took the word away and gave nothing
     * back: `minuscule springs` cost two pages and left the Age neither a spring nor a smaller one.
     */
    test("a size is not spent by a clause that mints nothing") {
        check(sizeOf("minuscule", "springs") == sizeOf("minuscule", "trees", "features")) {
            "'minuscule springs' minted nothing and spent 'minuscule' anyway: ${sizeOf("minuscule", "springs")}"
        }
    }

    /**
     * The other half: a claim carrying a substance becomes a feature made of it, keeping everything else
     * the pattern had. A spring that stopped wanting rock around it, or a vein that changed size, would be
     * a new feature wearing the pattern's name.
     */
    test("a minted spring runs with the substance and keeps its shape") {
        val pattern = placedFeature("minecraft:spring_water")
        val was = pattern.value().feature().value() as SpringFeature
        val minted = FeatureShape.mintedFrom(pattern, listOf("minecraft:lava"))
        val now = minted.value().feature().value() as SpringFeature

        check(now.state().type == Blocks.LAVA.defaultBlockState().fluidState.type) {
            "the spring ran with ${now.state().type} rather than lava"
        }
        check(now.rockCount() == was.rockCount()) {
            "the spring wants ${now.rockCount()} blocks of rock around it where the pattern wanted ${was.rockCount()}"
        }
        check(now.holeCount() == was.holeCount()) {
            "the spring punches ${now.holeCount()} holes where the pattern punched ${was.holeCount()}"
        }
        check(now.validBlocks() == was.validBlocks()) {
            "the spring wants ${now.validBlocks()} around it where the pattern wanted ${was.validBlocks()}"
        }
        check(minted.value().placement() == pattern.value().placement()) {
            "the spring is placed by ${minted.value().placement()} where the pattern used ${pattern.value().placement()}"
        }
    }

    test("a minted vein is made of the substance and cuts the same stone") {
        val pattern = placedFeature("minecraft:ore_gold")
        val was = pattern.value().feature().value() as OreFeature
        val minted = FeatureShape.mintedFrom(pattern, listOf("minecraft:gold_block"))
        val now = minted.value().feature().value() as OreFeature

        check(now.targetStates().all { it.state() == Blocks.GOLD_BLOCK.defaultBlockState() }) {
            "the vein was made of ${now.targetStates().map { it.state() }}"
        }
        check(now.targetStates().map { it.target() } == was.targetStates().map { it.target() }) {
            "the vein cuts into ${now.targetStates().map { it.target() }} where the pattern cut " +
                "${was.targetStates().map { it.target() }}"
        }
        check(now.size() == was.size()) {
            "the vein is ${now.size()} blocks where the pattern was ${was.size()}"
        }
    }


    /**
     * **A pool nobody can draw from is a pattern made of nothing.** The tag is bound by a server where the
     * words are read from files, so a stale id in either half never fails anything in play: generation says
     * so once in the log and ships the shape's own stone, and the Age looks merely dull.
     */
    test("every pattern's unstated substance names something real") {
        val patterns = vocabulary.words.filter { it.mints != null && it.unstated != null }
        check(patterns.isNotEmpty()) { "no pattern says what it is made of, so this is checking nothing" }

        for (word in patterns) {
            val named = word.unstated ?: continue
            val pool = if (named.startsWith("#")) blocksTagged(named.drop(1)) else listOf(named)
            check(pool.isNotEmpty()) { "'${word.name}' falls back to '$named', which nothing carries" }
            val missing = pool.filterNot { block ->
                Identifier.tryParse(block)?.let { BuiltInRegistries.BLOCK.getOptional(it).isPresent } == true
            }
            check(missing.isEmpty()) { "'${word.name}' would be made of blocks nothing has: $missing" }
        }
    }

    /**
     * Ours, so not in the offline registries: read from the shipped files, which checks that they parse. A
     * feature type of our own is not registered offline either, so a heap is read through its own codec.
     */
    fun shippedPattern(name: String): Holder<PlacedFeature> {
        val file = File("src/main/resources/data/agesandtheart/worldgen/feature/$name.json")
        val json = JsonParser.parseString(file.readText())
        val isOurs = json.asJsonObject.get("type").asString == "agesandtheart:heap"
        val feature: Feature = if (isOurs) {
            Heap.CODEC.codec().parse(JsonOps.INSTANCE, json).getOrThrow()
        } else {
            Feature.DIRECT_CODEC.parse(JsonOps.INSTANCE, json).getOrThrow()
        }
        return Holder.direct(PlacedFeature(Holder.direct(feature), emptyList()))
    }

    /** What a minted feature is made of, through the codec — a provider's `toString` names nothing. */
    fun spelled(minted: Holder<PlacedFeature>): String {
        val feature = minted.value().feature().value()
        val encoded = if (feature is Heap) {
            Heap.CODEC.codec().encodeStart(JsonOps.INSTANCE, feature)
        } else {
            Feature.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, feature)
        }
        return encoded.getOrThrow().toString()
    }

    /** The umbrella words: each named alone is made of its own fallback, and each takes any block. */
    test("patches, piles and icebergs named alone fall back to what they say they are made of") {
        val fallbacks = mapOf(
            "patches" to "of=#agesandtheart:patch_substance",
            "piles" to "of=#agesandtheart:pile_substance",
            "icebergs" to "of=minecraft:packed_ice",
        )
        for ((word, fallback) in fallbacks) {
            val grown = placed(word)
            check(grown.any { fallback in it }) { "'$word' alone left $grown" }
        }
        for (word in fallbacks.keys) {
            val displaced = flawsOf("gold_block", word).filter { it.register == Register.DISPLACED }
            check(displaced.isEmpty()) { "'gold_block $word' was charged $displaced, and it takes any block" }
        }
    }

    test("a patch is made of what the clause names, and several mingle in one") {
        val pumpkins = spelled(FeatureShape.mintedFrom(shippedPattern("patches"), listOf("minecraft:pumpkin")))
        check("minecraft:pumpkin" in pumpkins && "short_grass" !in pumpkins) { "'pumpkin patches' is $pumpkins" }
        val flowers = FeatureShape.mintedFrom(shippedPattern("patches"), listOf("minecraft:poppy", "minecraft:dandelion"))
        val both = spelled(flowers)
        check("minecraft:poppy" in both && "minecraft:dandelion" in both) { "'poppy and dandelion patches' is $both" }
    }

    test("a pile is made of what the clause names, and several mingle in one") {
        val piled = spelled(FeatureShape.mintedFrom(shippedPattern("piles"), listOf("minecraft:pumpkin", "minecraft:melon")))
        check("minecraft:pumpkin" in piled && "minecraft:melon" in piled && "hay_block" !in piled) {
            "'pumpkin and melon piles' is $piled"
        }
    }

    /** The minted claim of [pattern] a book leaves, read back. */
    fun mintedIn(pattern: String, vararg pages: String): Claim =
        placed(*pages).map(Claim::read).single { it.value == pattern }

    /** **The amount and the size in a minting clause are the minted thing's own**, for the umbrellas too. */
    test("teeming colossal tnt piles and scarce minuscule wither_rose patches keep both on the claim") {
        val piles = mintedIn("agesandtheart:piles", "teeming", "colossal", "tnt", "piles")
        check(piles.substances == listOf("minecraft:tnt")) { "the piles are of ${piles.substances}" }
        check(piles.density > Rung.ORDINARY && (piles.size ?: 0.0) > 0.0) {
            "'teeming colossal tnt piles' is $piles"
        }

        val patches = mintedIn("agesandtheart:patches", "scarce", "minuscule", "wither_rose", "patches")
        check(patches.substances == listOf("minecraft:wither_rose")) { "the patches are of ${patches.substances}" }
        check(patches.density < Rung.ORDINARY && (patches.size ?: 0.0) < 0.0) {
            "'scarce minuscule wither_rose patches' is $patches"
        }
    }

    test("a colossal pile is a heap four times the scale") {
        val colossal = FeatureShape.reshaped(shippedPattern("piles"), 1.0, null, null, emptyList())
        val heap = colossal.value().feature().value()
        check(heap is Heap && heap.scale == 4.0) { "a colossal pile became $heap" }
    }

    /** A patch's size is its spread and its tries, read off the placement the pack ships. */
    test("a colossal patch spreads four times as far with sixteen times the tries, and a minuscule one shrinks") {
        val file = File("src/main/resources/data/agesandtheart/worldgen/placed_feature/patches.json")
        val placement = PlacementModifier.CODEC.listOf()
            .parse(JsonOps.INSTANCE, JsonParser.parseString(file.readText()).asJsonObject.get("placement"))
            .getOrThrow()
        val pattern = Holder.direct(PlacedFeature(shippedPattern("patches").value().feature(), placement))

        fun spreadAndTries(size: Double?): Pair<Int, Int> {
            val reshaped = FeatureShape.reshaped(pattern, size, null, null, emptyList()).value().placement()
            val offset = reshaped.filterIsInstance<OffsetPlacement>().single()
            val atOffset = reshaped.indexOf(offset)
            val tries = reshaped.take(atOffset).takeLastWhile { it is CountPlacement }
                .fold(1) { total, count -> total * (count as CountPlacement).count().maxInclusive() }
            return offset.x().maxInclusive() to tries
        }
        check(spreadAndTries(null) == (7 to 64)) { "an ordinary patch is ${spreadAndTries(null)}" }
        check(spreadAndTries(1.0) == (24 to 1024)) { "a colossal patch is ${spreadAndTries(1.0)}" }
        val minuscule = spreadAndTries(-1.0)
        check(minuscule.first < 7 && minuscule.second < 64) { "a minuscule patch is $minuscule" }
    }

    /** On flat ground: an ordinary heap is vanilla's, a colossal one a mound, a minuscule one a block or two. */
    test("a heap grows from vanilla's pile into a mound with its scale") {
        fun heapOn(scale: Double): Pair<Int, Int> {
            val laid = mutableSetOf<BlockPos>()
            Heap.heap(
                origin = BlockPos.ZERO,
                scale = scale,
                random = RandomSource.create(SAMPLE_SEED),
                isOpen = { it.y >= 0 && it !in laid },
                holdsUp = { it.y == -1 || it in laid },
            ) { laid += it }
            return laid.size to (laid.maxOfOrNull { it.y + 1 } ?: 0)
        }
        val (ordinary, ordinaryHeight) = heapOn(1.0)
        check(ordinary in 5..50 && ordinaryHeight <= 2) { "an ordinary heap is $ordinary blocks, $ordinaryHeight high" }
        val (colossal, colossalHeight) = heapOn(4.0)
        check(colossal > ordinary * 10 && colossalHeight in 5..8) {
            "a colossal heap is $colossal blocks, $colossalHeight high"
        }
        val (minuscule, _) = heapOn(0.25)
        check(minuscule in 1..5) { "a minuscule heap is $minuscule blocks" }
    }

    test("a minted iceberg is made of the substance and stands where vanilla's would") {
        val pattern = placedFeature("minecraft:iceberg_packed")
        val minted = FeatureShape.mintedFrom(pattern, listOf("minecraft:obsidian"))
        val now = minted.value().feature().value()
        check(now is IcebergFeature && now.state() == Blocks.OBSIDIAN.defaultBlockState()) {
            "'obsidian icebergs' built $now"
        }
        check(minted.value().placement() == pattern.value().placement()) {
            "the iceberg is placed by ${minted.value().placement()} where vanilla's used ${pattern.value().placement()}"
        }
    }

    test("a substance nothing answers to leaves the pattern alone") {
        val pattern = placedFeature("minecraft:spring_water")
        check(FeatureShape.mintedFrom(pattern, listOf("agesandtheart:no_such_block")) === pattern) {
            "an unknown substance built a half-made feature instead of standing aside"
        }
    }
})

private const val SAMPLE_SEED = 0x5EEDL

/** What the pack's own block tag holds, read off the file — nothing binds a tag without a server. */
private fun blocksTagged(id: String): List<String> {
    val named = Identifier.tryParse(id) ?: return emptyList()
    val file = File("src/main/resources/data/${named.namespace}/tags/block/${named.path}.json")
    if (!file.isFile) return emptyList()
    return JsonParser.parseString(file.readText()).asJsonObject
        .getAsJsonArray("values").map { it.asString }
}

private fun placedFeature(id: String) = MinecraftRegistries.worldgen
    .lookupOrThrow(Registries.PLACED_FEATURE)
    .getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse(id)))
