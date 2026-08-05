package co.voik.agesandtheart.age.aspect

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.util.random.Weighted
import net.minecraft.util.random.WeightedList
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.biome.MobSpawnSettings

/**
 * What a sentence does to the creatures a biome offers.
 *
 * `Spawns.livingIn` is a pure function of the claims and the list vanilla resolved, which is the whole
 * reason it can be checked here at all — the seam itself is an override on the generator, and only a
 * running world could exercise that.
 */
@Tags(NEEDS_REGISTRIES)
class SpawnsCheck : FunSpec({

    test("silence leaves the biome's own creatures alone") {
        val offered = aMeadow()
        check(narrowedBy("", offered) === offered) { "an Age that said nothing rebuilt the list anyway" }
    }

    test("except strikes one out and leaves the rest") {
        val narrowed = narrowedBy("-minecraft:zombie")
        check(kinds(narrowed) == listOf("cow", "sheep", "spider")) { "'except zombie' left ${kinds(narrowed)}" }
    }

    test("only keeps what was named and nothing else") {
        val narrowed = narrowedBy("!minecraft:zombie")
        check(kinds(narrowed) == listOf("zombie")) { "'only zombie' left ${kinds(narrowed)}" }
    }

    /** A rung is how *often*, which for a creature is the weight it is drawn at. */
    test("a rung weighs a creature more heavily") {
        val narrowed = narrowedBy("minecraft:cow@4")
        val cow = narrowed.unwrap().first { it.value().type() == EntityType.COW }
        val ordinary = aMeadow().unwrap().first { it.value().type() == EntityType.COW }
        check(cow.weight() == ordinary.weight() * FOUR_TIMES) {
            "asking for four times the cows gave weight ${cow.weight()} against ${ordinary.weight()}"
        }
        check(kinds(narrowed).size == kinds(aMeadow()).size) { "weighing one creature dropped another" }
    }

    /** And it never weighs one to nothing, since an entry at zero would simply never be drawn. */
    test("a faint rung still leaves a creature drawable") {
        val narrowed = narrowedBy("minecraft:cow@0.01")
        val cow = narrowed.unwrap().first { it.value().type() == EntityType.COW }
        check(cow.weight() >= 1) { "a scarce cow came out at weight ${cow.weight()}, which is never drawn" }
    }
})

private const val FOUR_TIMES = 4

/** A biome's worth of creatures, weighted as vanilla weights them. */
private fun aMeadow(): WeightedList<MobSpawnSettings.SpawnerData> {
    MinecraftRegistries.ensureStoodUp()
    return WeightedList.of(
        Weighted(MobSpawnSettings.SpawnerData(EntityType.COW, 4, 4), 8),
        Weighted(MobSpawnSettings.SpawnerData(EntityType.SHEEP, 4, 4), 12),
        Weighted(MobSpawnSettings.SpawnerData(EntityType.ZOMBIE, 4, 4), 95),
        Weighted(MobSpawnSettings.SpawnerData(EntityType.SPIDER, 4, 4), 100),
    )
}

/** The list [claims] leaves, where a claim is spelled as a recipe holds it. */
private fun narrowedBy(
    claims: String,
    offered: WeightedList<MobSpawnSettings.SpawnerData> = aMeadow(),
): WeightedList<MobSpawnSettings.SpawnerData> {
    val options = Options(mapOf(Spawns.LIVES.name to claims.split(",").filter(String::isNotBlank)))
    // Null for the biome: nothing scopes a claim to one until `in <biome>` lands (§4.3.1).
    return Spawns.livingIn(options).invoke(null, offered)
}

private fun kinds(list: WeightedList<MobSpawnSettings.SpawnerData>): List<String> =
    list.unwrap().map { it.value().type().builtInRegistryHolder().key().identifier().path }.sorted()
