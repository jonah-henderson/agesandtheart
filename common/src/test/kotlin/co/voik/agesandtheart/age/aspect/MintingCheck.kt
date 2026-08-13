package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.worldgen.feature.FeatureShape
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
