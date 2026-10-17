package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.age.aspect.Spawns
import co.voik.agesandtheart.age.consequence.WrittenCreatures
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.util.RandomSource

/** A size word sizes the creatures it is said of, and only when it is said of them. */
@Tags(NEEDS_REGISTRIES)
class CreatureSizeCheck : FunSpec({

    fun bare(word: String, seed: Long): AgeComposition =
        Resolver.resolve(ShippedCorpus.vocabulary, ShippedCorpus.read(listOf(word, "age")), seed).composition

    test("colossal monsters are sized, and the zombie among them") {
        val spawns = ShippedCorpus.resolved(SEED, "colossal", "monsters").composition.optionsFor(Aspect.SPAWNS)
        check(spawns.steer(Spawns.SIZE, SEED, member = "minecraft:zombie") == 1.0) { "a colossal zombie was not sized" }
        check(spawns.steer(Spawns.SIZE, SEED, member = "minecraft:cow") == null) { "a cow was sized among the monsters" }
    }

    test("a size word laid bare leaves the creatures alone") {
        for (word in listOf("colossal", "large", "small", "minuscule")) for (seed in 0L..<40L) {
            val spawns = bare(word, seed).optionsFor(Aspect.SPAWNS)
            check(spawns.steer(Spawns.SIZE, seed, member = "minecraft:zombie") == null) { "seed $seed: bare '$word' sized a zombie" }
        }
    }

    test("a size is two fifths to two and a half times, and drops follow it") {
        check(WrittenCreatures.sizeAt(1.0) == 2.5 && WrittenCreatures.sizeAt(-1.0) == 0.4) { "the ends moved" }
        val random = RandomSource.create(SEED)
        val many = WrittenCreatures.stacksFor(2, LARGEST, 2.5, random.nextDouble()).sum()
        check(many in 5..6) { "two bones at two and a half times came to $many" }
        val split = WrittenCreatures.stacksFor(40, LARGEST, 2.5, random.nextDouble())
        check(split == listOf(64, 36)) { "a scaled stack was not split: $split" }
        val few = (0..<400).sumOf { WrittenCreatures.stacksFor(1, LARGEST, 0.4, random.nextDouble()).sum() }
        check(few in 120..200) { "one bone at two fifths came to $few in 400" }
    }
})

private const val SEED = 4242L
private const val LARGEST = 64

/** A size said of some features is theirs alone: `colossal trees` leaves the boulders as they were. */
@Tags(NEEDS_REGISTRIES)
class FeatureSizeCheck : FunSpec({

    fun featuresOf(vararg pages: String) = ShippedCorpus.resolved(SEED, *pages).composition.optionsFor(Aspect.FEATURES)

    test("colossal trees are the trees' size, and not the boulders'") {
        val features = featuresOf("colossal", "trees")
        check(features.steer(Features.SIZE, SEED, member = TREE) == 1.0) { "a tree was not made colossal" }
        check(features.steer(Features.SIZE, SEED, member = BOULDER) == null) { "the boulders were made colossal too" }
    }

    test("colossal features are every feature's size") {
        val features = featuresOf("colossal", "features")
        check(features.steer(Features.SIZE, SEED, member = BOULDER) == 1.0) { "'colossal features' left the boulders" }
    }
})

private const val TREE = "minecraft:trees_plains"
private const val BOULDER = "minecraft:forest_rock"
