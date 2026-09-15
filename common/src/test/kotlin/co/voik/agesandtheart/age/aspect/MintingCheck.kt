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
import co.voik.agesandtheart.worldgen.feature.FeatureShape
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature
import net.minecraft.world.level.material.Fluids
import co.voik.agesandtheart.worldgen.feature.SpilledSpring
import net.minecraft.world.level.levelgen.feature.configurations.BlockStateConfiguration
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration
import net.minecraft.world.level.levelgen.feature.configurations.SpringConfiguration
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import java.io.File

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

    /** And the pattern is really rebuilt, rather than the claim merely spelling what was asked for. */
    test("a minted lake is filled with what the clause named") {
        val pattern = MinecraftRegistries.worldgen.lookupOrThrow(Registries.PLACED_FEATURE)
            .getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse("minecraft:lake_lava_surface")))
        val obsidian = FeatureShape.mintedFrom(pattern, "minecraft:obsidian")
        check(obsidian !== pattern) { "the lake came back unminted" }
        // Through the codec, because a `BlockStateProvider`'s own `toString` is its identity and says
        // nothing about the block — which is what made the first version of this pass over a lava lake.
        val spelled = ConfiguredFeature.DIRECT_CODEC
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
        val spilled = FeatureShape.mintedFrom(spring, "minecraft:gold_block")

        check(spilled !== spring) { "a solid spring came back as the untouched pattern" }
        check(spilled.value().placement() == spring.value().placement()) {
            "the spill does not stand where a spring would: ${spilled.value().placement()}"
        }
        val made = spilled.value().feature().value()
        check(made.feature() === SpilledSpring) { "a solid spring was rebuilt as ${made.feature()}" }
        val substance = (made.config() as BlockStateConfiguration).state
        check(substance == Blocks.GOLD_BLOCK.defaultBlockState()) { "the spill is made of $substance" }
    }

    /** And a fluid still runs, which is the half that must not have moved. */
    test("a spring given a fluid still runs with it") {
        val spring = placedFeature("minecraft:spring_water")
        val running = FeatureShape.mintedFrom(spring, "minecraft:lava").value().feature().value().config()
        check(running is SpringConfiguration) { "a lava spring stopped being a spring: $running" }
        check((running as SpringConfiguration).state.type === Fluids.LAVA) {
            "a lava spring runs with ${running.state.type}"
        }
    }

    /** And a pattern that never asked for a fluid takes a solid happily — `veins` is the other minting. */
    test("a vein is made of a solid and charges nothing") {
        val displaced = flawsOf("gold_block", "veins").filter { it.register == Register.DISPLACED }
        check(displaced.isEmpty()) { "'gold_block veins' was charged $displaced, and a vein wants a solid" }
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
        val grown = placed("colossal", "gold_block", "obelisks", "tiny", "rings")
        val obelisks = grown.single { it.startsWith("agesandtheart:obelisks") }
        val rings = grown.single { it.startsWith("agesandtheart:rings") }

        check("of=minecraft:gold_block" in obelisks) { "the obelisks lost their substance: $obelisks" }
        check("size=1" in obelisks) { "the obelisks were not colossal: $obelisks" }
        check("size=-1" in rings) { "the rings were not tiny: $rings" }
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
     * `springs`, `lakes` and `veins` all declare `mints` with no `unstated`, so a clause naming one without
     * a material mints nothing at all. Charging it for the size anyway took the word away and gave nothing
     * back: `tiny springs` cost two pages and left the Age neither a spring nor a smaller one.
     */
    test("a size is not spent by a clause that mints nothing") {
        check(sizeOf("tiny", "springs") == sizeOf("tiny", "trees", "features")) {
            "'tiny springs' minted nothing and spent 'tiny' anyway: ${sizeOf("tiny", "springs")}"
        }
    }

    /**
     * The other half: a claim carrying a substance becomes a feature made of it, keeping everything else
     * the pattern had. A spring that stopped wanting rock around it, or a vein that changed size, would be
     * a new feature wearing the pattern's name.
     */
    test("a minted spring runs with the substance and keeps its shape") {
        val pattern = placedFeature("minecraft:spring_water")
        val was = pattern.value().feature().value().config() as SpringConfiguration
        val minted = FeatureShape.mintedFrom(pattern, "minecraft:lava")
        val now = minted.value().feature().value().config() as SpringConfiguration

        check(now.state.type == Blocks.LAVA.defaultBlockState().fluidState.type) { "the spring still ran with ${now.state.type}" }
        check(now.rockCount == was.rockCount && now.holeCount == was.holeCount) { "the spring changed shape" }
        check(now.validBlocks == was.validBlocks) { "the spring changed the rock it wants around it" }
        check(minted.value().placement() == pattern.value().placement()) { "the spring moved" }
    }

    test("a minted vein is made of the substance and cuts the same stone") {
        val pattern = placedFeature("minecraft:ore_gold")
        val was = pattern.value().feature().value().config() as OreConfiguration
        val minted = FeatureShape.mintedFrom(pattern, "minecraft:gold_block")
        val now = minted.value().feature().value().config() as OreConfiguration

        check(now.targetStates.all { it.state == Blocks.GOLD_BLOCK.defaultBlockState() }) {
            "the vein was made of ${now.targetStates.map { it.state }}"
        }
        check(now.targetStates.map { it.target } == was.targetStates.map { it.target }) {
            "the vein changed the stone it cuts into"
        }
        check(now.size == was.size) { "the vein changed size" }
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

    test("a substance nothing answers to leaves the pattern alone") {
        val pattern = placedFeature("minecraft:spring_water")
        check(FeatureShape.mintedFrom(pattern, "agesandtheart:no_such_block") === pattern) {
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
