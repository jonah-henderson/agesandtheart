package co.voik.agesandtheart.generation

import net.minecraft.core.Holder
import net.minecraft.resources.Identifier
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.MobSpawnSettings
import java.util.concurrent.ConcurrentHashMap

/**
 * Every creature the game spawns in a pass, for a `varied inhabitants` Age: from every biome there is, the nether's and
 * the End's included, each once. Worked out once per pass.
 */
object VariedInhabitants {

    fun joiningIn(biomes: Collection<Holder<Biome>>): (Identifier?, MobCategory) -> List<MobSpawnSettings.SpawnerData> {
        val workedOut = ConcurrentHashMap<MobCategory, List<MobSpawnSettings.SpawnerData>>()
        return { _, category -> workedOut.computeIfAbsent(category) { everyoneIn(biomes, it) } }
    }

    private fun everyoneIn(biomes: Collection<Holder<Biome>>, category: MobCategory): List<MobSpawnSettings.SpawnerData> =
        biomes.flatMap { biome ->
            biome.value().attributes.applyModifier(EnvironmentAttributes.NATURAL_MOB_SPAWNS, MobSpawnSettings.EMPTY)
                .getMobsToSpawn(category).unwrap()
        }.map { it.value() }.distinctBy { it.type() }
}
