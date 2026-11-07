package co.voik.agesandtheart.generation

import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.util.random.Weighted
import net.minecraft.util.random.WeightedList
import net.minecraft.util.valueproviders.UniformInt
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.biome.MobSpawnSettings

@Tags(NEEDS_REGISTRIES)
class IncongruousSpawnsCheck : FunSpec({

    fun offered(type: EntityType<*>, weight: Int) = Weighted(MobSpawnSettings.SpawnerData(type, UniformInt.of(1, 1)), weight)
    fun weightOf(menu: WeightedList<MobSpawnSettings.SpawnerData>, type: EntityType<*>) =
        menu.unwrap().filter { it.value().type() === type }.sumOf { it.weight() }

    test("every kind is as likely as the next, whatever weight its biomes gave it, and comes once") {
        val menu = IncongruousSpawns.everyKindAlike(
            listOf(offered(EntityTypes.SKELETON, 20), offered(EntityTypes.STRAY, 80), offered(EntityTypes.SKELETON, 100)),
        )
        check(menu.unwrap().size == 2) { "the menu held ${menu.unwrap().map { it.value().type() }}" }
        check(weightOf(menu, EntityTypes.SKELETON) == weightOf(menu, EntityTypes.STRAY)) { "skeletons and strays weighed ${menu.unwrap()}" }
    }

    test("a fresh chunk is made three parts in four of creatures, however many monsters there are") {
        val creatures = IncongruousSpawns.everyKindAlike(listOf(offered(EntityTypes.COW, 8), offered(EntityTypes.PIG, 10)))
        val monsters = IncongruousSpawns.everyKindAlike(
            listOf(offered(EntityTypes.ZOMBIE, 100), offered(EntityTypes.SPIDER, 100), offered(EntityTypes.STRAY, 80)),
        )
        val fish = IncongruousSpawns.everyKindAlike(listOf(offered(EntityTypes.COD, 15)))
        val made = IncongruousSpawns.madeAnywhere(
            mapOf(MobCategory.CREATURE to creatures, MobCategory.MONSTER to monsters, MobCategory.WATER_AMBIENT to fish),
        )
        val creatureWeight = weightOf(made, EntityTypes.COW) + weightOf(made, EntityTypes.PIG)
        val whole = made.unwrap().sumOf { it.weight() }
        check(creatureWeight * 4 == whole * 3) { "creatures weighed $creatureWeight of $whole" }
        check(weightOf(made, EntityTypes.ZOMBIE) == weightOf(made, EntityTypes.COD)) { "the rest were not alike: ${made.unwrap()}" }
    }
})
