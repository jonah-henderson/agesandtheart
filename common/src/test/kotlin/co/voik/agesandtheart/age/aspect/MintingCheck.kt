package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.Flaw
import co.voik.agesandtheart.age.Register
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.worldgen.feature.FeatureShape
import com.mojang.serialization.JsonOps
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature
import net.minecraft.world.level.levelgen.feature.configurations.BlockColumnConfiguration
import net.minecraft.world.level.material.Fluids
import net.minecraft.core.Direction
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration
import net.minecraft.world.level.levelgen.feature.configurations.SpringConfiguration
import net.minecraft.world.level.levelgen.placement.PlacedFeature

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

    val vocabulary by lazy {
        Vocabulary.load(MinecraftRegistries.shippedData(), MinecraftRegistries.worldgen).also {
            check(it.problems.isEmpty()) { "vocabulary problems: ${it.problems}" }
        }
    }

    /** What a book asking for one minted feature leaves in [Features.PLACES]. */
    fun placed(vararg pages: String): Set<String> {
        val sentence = Grammar.read(vocabulary, listOf("age", *pages)) ?: error("not a book: ${pages.toList()}")
        val composition = Resolver.resolve(vocabulary, sentence, SAMPLE_SEED).composition
        return composition.optionsFor(Aspect.FEATURES, 0).allSpelled(Features.PLACES.name)
    }

    test("a material and a pattern said together mint one") {
        val grown = placed("lava", "springs")
        check(grown.any { it.startsWith("minecraft:spring_water") && "of=minecraft:lava" in it }) {
            "'lava springs' should have minted a lava-carrying spring, and left $grown"
        }
    }

    /** What a book resolved to as instability, so a charge can be checked rather than only a claim. */
    fun flawsOf(vararg pages: String): List<Flaw> {
        val sentence = Grammar.read(vocabulary, listOf("age", *pages)) ?: error("not a book: ${pages.toList()}")
        return Resolver.resolve(vocabulary, sentence, SAMPLE_SEED).instability.flaws
    }

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
        val column = spilled.value().feature().value().config() as BlockColumnConfiguration
        check(column.direction() == Direction.DOWN) { "the spill runs ${column.direction()}" }
        // Through the codec: a `BlockStateProvider`'s own `toString` is its identity, and this
        // configuration holds no `HolderSet`, so plain ops can write it where a spring's cannot.
        val spelled = BlockColumnConfiguration.CODEC.encodeStart(JsonOps.INSTANCE, column).getOrThrow().toString()
        check("minecraft:gold_block" in spelled) { "the spill is not made of what was asked for: $spelled" }
        // Truncating to nothing where the run cannot start is what keeps these out of solid rock, so
        // what the run is allowed into is the load-bearing half of the configuration.
        check("replaceable" in spelled) {
            "the spill would set inside rock rather than only where there was somewhere to run: $spelled"
        }
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

    test("a pattern named alone mints nothing") {
        check(placed("springs").none { "of=" in it }) { "'springs' minted something out of nothing" }
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


    test("a substance nothing answers to leaves the pattern alone") {
        val pattern = placedFeature("minecraft:spring_water")
        check(FeatureShape.mintedFrom(pattern, "agesandtheart:no_such_block") === pattern) {
            "an unknown substance built a half-made feature instead of standing aside"
        }
    }
})

private const val SAMPLE_SEED = 0x5EEDL

private fun placedFeature(id: String) = MinecraftRegistries.worldgen
    .lookupOrThrow(Registries.PLACED_FEATURE)
    .getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE, Identifier.parse(id)))
