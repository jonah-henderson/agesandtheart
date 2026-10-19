package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import com.mojang.serialization.JsonOps
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.levelgen.feature.BlockColumnFeature
import net.minecraft.world.level.levelgen.feature.DiskFeature
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.GeodeFeature
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
            // Short enough that four times as tall stays under the ceiling a resized column is held to.
            .first { column -> column.layers().maxOf { it.height().maxInclusive() } in 2..SHORT_COLUMN }
        val tallest = cane.layers().maxOf { it.height().maxInclusive() }
        val resized = ResizedFeatures.of(cane, 4.0, { it }, registries) as BlockColumnFeature
        check(resized.layers().maxOf { it.height().maxInclusive() } == tallest * 4) { "a colossal column was not four times as tall" }
    }

    fun vanillaFeatures(): List<Feature> =
        registries.lookupOrThrow(Registries.PLACED_FEATURE).listElements().toList().map { it.value().feature().value() }

    test("a colossal cactus is taller and still wears one flower at most") {
        val cactus = vanillaFeatures().filterIsInstance<BlockColumnFeature>()
            .first { column -> column.layers().any { it.height().maxInclusive() == 1 } }
        val resized = ResizedFeatures.of(cactus, 4.0, { it }, registries) as BlockColumnFeature
        val caps = resized.layers().filter { it.height().maxInclusive() <= 1 }
        check(caps.size == cactus.layers().count { it.height().maxInclusive() <= 1 }) { "a cap grew past one block" }
        check(resized.layers().maxOf { it.height().maxInclusive() } > cactus.layers().maxOf { it.height().maxInclusive() }) {
            "the column did not grow"
        }
    }

    test("a colossal geode grows its shells in proportion, held to what fits the box vanilla builds it in") {
        val geode = vanillaFeatures().filterIsInstance<GeodeFeature>().first()
        val resized = ResizedFeatures.of(geode, 4.0, { it }, registries) as GeodeFeature
        val shellGrowth = resized.layerSettings().outerLayer / geode.layerSettings().outerLayer
        check(shellGrowth > 1.0 && shellGrowth <= MOST_GEODE_SHELL_GROWTH) { "the outer shell grew $shellGrowth times" }
        check(resized.outerWallDistance().maxInclusive() <= geode.outerWallDistance().maxInclusive()) {
            "a grown geode's points moved farther from its origin"
        }
    }

    test("a colossal disk spreads by the root of its size and digs no deeper") {
        val disk = vanillaFeatures().filterIsInstance<DiskFeature>().first()
        val resized = ResizedFeatures.of(disk, 4.0, { it }, registries) as DiskFeature
        check(resized.halfHeight() == disk.halfHeight()) { "a disk dug ${resized.halfHeight()} deep, not ${disk.halfHeight()}" }
        check(resized.radius().maxInclusive() <= disk.radius().maxInclusive() * 2) { "a disk spread past twice as wide" }
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

/** A geode grows at most 1.8 times, and its shells are squared distances. */
private const val MOST_GEODE_SHELL_GROWTH = 1.8 * 1.8 + 0.001

/** Sugar cane's height and less: four times it stays under the forty-eight blocks a column may grow. */
private const val SHORT_COLUMN = 12
