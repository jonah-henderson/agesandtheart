package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import com.mojang.serialization.JsonOps
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.levelgen.feature.BlockColumnFeature
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.TreeFeature
import net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacer

/**
 * **Every tree vanilla grows comes out bigger when asked.** A tree is resized by rewriting its trunk and crown
 * through their codecs, and one that does not read back is kept as it was, which is silent.
 */
@Tags(NEEDS_REGISTRIES)
class ResizedFeaturesCheck : FunSpec({

    val registries by lazy { MinecraftRegistries.worldgen }

    fun treesIn(feature: Feature): Sequence<TreeFeature> = sequence {
        if (feature is TreeFeature) yield(feature)
        for (sub in feature.getSubFeatures()) yieldAll(treesIn(sub.value()))
    }

    val trees by lazy {
        registries.lookupOrThrow(Registries.PLACED_FEATURE).listElements()
            .map { it.value().feature().value() }.toList()
            .flatMap { treesIn(it).toList() }.distinct()
    }

    fun trunkOf(tree: TreeFeature): String {
        val ops = registries.createSerializationContext(JsonOps.INSTANCE)
        return TrunkPlacer.CODEC.encodeStart(ops, tree.trunkPlacer()).result().orElseThrow().toString()
    }

    test("vanilla grows trees for this to resize") {
        check(trees.size > 10) { "found only ${trees.size} trees among vanilla's placed features" }
    }

    test("a doubled tree has a different trunk from the tree it was") {
        val unchanged = trees.filter { tree ->
            val resized = ResizedFeatures.of(tree, 2.0, { it }, registries) as ThickTrunk
            trunkOf(resized.tree.value() as TreeFeature) == trunkOf(tree)
        }
        check(unchanged.isEmpty()) {
            "these trunks did not grow: ${unchanged.map(::trunkOf).distinct().joinToString()}"
        }
    }

    test("a large tree's trunk is two logs across, a colossal one's three, and an ordinary one's one") {
        val oak = trees.first()
        check((ResizedFeatures.of(oak, 2.0, { it }, registries) as? ThickTrunk)?.girth == 2) { "a large tree was not two across" }
        check((ResizedFeatures.of(oak, 4.0, { it }, registries) as? ThickTrunk)?.girth == 3) { "a colossal tree was not three across" }
        check(ResizedFeatures.of(oak, 1.0, { it }, registries) is TreeFeature) { "an ordinary tree was thickened" }
    }

    test("sugar cane grows taller rather than wider") {
        val cane = registries.lookupOrThrow(Registries.PLACED_FEATURE).listElements().toList()
            .map { it.value().feature().value() }
            .filterIsInstance<BlockColumnFeature>()
            .first { column -> column.layers().any { it.height().maxInclusive() > 1 } }
        val tallest = cane.layers().maxOf { it.height().maxInclusive() }
        val resized = ResizedFeatures.of(cane, 4.0, { it }, registries) as BlockColumnFeature
        check(resized.layers().maxOf { it.height().maxInclusive() } == tallest * 4) { "a colossal column was not four times as tall" }
    }

    test("a chooser among trees resizes every tree it chooses between") {
        val chooser = registries.lookupOrThrow(Registries.PLACED_FEATURE).listElements().toList()
            .first { placed -> placed.value().feature().value().getSubFeatures().count() > 1 &&
                treesIn(placed.value().feature().value()).any() }
        var resizedChoices = 0
        ResizedFeatures.of(chooser.value().feature().value(), 2.0, { chosen -> resizedChoices++; chosen }, registries)
        check(resizedChoices > 1) { "${chooser.key()} handed $resizedChoices of its choices on to be resized" }
    }
})

