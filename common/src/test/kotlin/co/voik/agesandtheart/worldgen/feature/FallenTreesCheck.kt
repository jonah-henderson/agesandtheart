package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.feature.FallenTreeFeature
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.RandomSelectorFeature
import net.minecraft.world.level.levelgen.feature.TreeFeature
import net.minecraft.world.level.levelgen.feature.stateproviders.SimpleStateProvider
import net.minecraft.world.level.levelgen.placement.PlacedFeature

/** `fallen trees`: every tree a feature grows chosen between standing and lying fallen, of its own log. */
@Tags(NEEDS_REGISTRIES)
class FallenTreesCheck : FunSpec({

    fun placed(id: String): Holder<PlacedFeature> = MinecraftRegistries.worldgen.lookupOrThrow(Registries.PLACED_FEATURE)
        .getOrThrow(ResourceKey.create(Registries.PLACED_FEATURE, Identifier.withDefaultNamespace(id)))

    fun felled(id: String) = FallenTrees.felled(placed(id), MinecraftRegistries.worldgen)

    /** Every tree [feature] places, standing or fallen, wherever it sits among what it chooses between. */
    fun allPlacedBy(feature: Holder<PlacedFeature>): List<Feature> = feature.value().getFeatures().map { it.value() }.toList()

    fun logOf(fallen: FallenTreeFeature): Block? = (fallen.trunkProvider().value() as? SimpleStateProvider)?.state()?.block

    // RandomSelectorFeature is deprecated and vanilla's own features are still built of it.
    @Suppress("DEPRECATION")
    fun fellingOf(feature: Holder<PlacedFeature>): RandomSelectorFeature? {
        val held = feature.value().feature().value() as? RandomSelectorFeature ?: return null
        return held.takeIf { it.defaultFeature().value().feature().value() is TreeFeature }
    }

    test("a tree with a fallen tree of its own log falls as vanilla's, mushrooms and all") {
        val birch = felled("birch_checked")
        val felling = checkNotNull(fellingOf(birch)) { "the birch was not made a choice between standing and fallen" }
        val fallen = felling.features().single()
        check(fallen.chance() == FallenTrees.SHARE_FALLEN) { "the birch falls ${fallen.chance()} of the time" }
        val laid = fallen.feature().value().feature().value() as FallenTreeFeature
        check(logOf(laid) == Blocks.BIRCH_LOG) { "the birch falls as ${logOf(laid)}" }
        check(laid.logDecorators().isNotEmpty()) { "the fallen birch lost vanilla's decorations" }
    }

    test("a tree vanilla never fells falls as a fallen oak of its own trunk") {
        val cherry = felled("cherry_checked")
        val fallen = allPlacedBy(cherry).filterIsInstance<FallenTreeFeature>()
        check(fallen.map(::logOf) == listOf(Blocks.CHERRY_LOG)) { "the cherry falls as ${fallen.map(::logOf)}" }
    }

    test("every tree in a forest's choice falls, and the placement is kept") {
        val plains = placed("trees_plains")
        val felledPlains = FallenTrees.felled(plains, MinecraftRegistries.worldgen)
        val standing = allPlacedBy(plains).count { it is TreeFeature }
        val fallenAfter = allPlacedBy(felledPlains).count { it is FallenTreeFeature }
        val fallenBefore = allPlacedBy(plains).count { it is FallenTreeFeature }
        check(fallenAfter == fallenBefore + standing) { "$standing trees, and $fallenBefore fallen became $fallenAfter" }
        check(felledPlains.value().placement() == plains.value().placement()) { "the forest moved" }
    }

    test("a kind of tree falls by its log among a forest's others, and they stand") {
        val forest = placed("trees_birch_and_oak_leaf_litter")
        val oaks = TreeKinds.logsOf(placed("oak"))
        check(oaks == setOf(Blocks.OAK_LOG)) { "vanilla's oak grows of $oaks" }
        val felled = FallenTrees.felled(forest, MinecraftRegistries.worldgen, oaks)
        fun fallenOf(feature: Holder<PlacedFeature>) = allPlacedBy(feature).filterIsInstance<FallenTreeFeature>().map(::logOf)
        fun countOf(log: Block, feature: Holder<PlacedFeature>) = fallenOf(feature).count { it == log }
        check(countOf(Blocks.OAK_LOG, felled) > countOf(Blocks.OAK_LOG, forest)) { "none of the forest's oaks fell" }
        check(countOf(Blocks.BIRCH_LOG, felled) == countOf(Blocks.BIRCH_LOG, forest)) { "a birch fell beside the oaks" }
    }

    test("what grows no tree is handed back as it was") {
        val flowers = placed("flower_default")
        check(FallenTrees.felled(flowers, MinecraftRegistries.worldgen) === flowers) { "flowers were felled" }
    }
})
