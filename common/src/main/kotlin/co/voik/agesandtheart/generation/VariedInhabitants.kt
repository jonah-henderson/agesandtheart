package co.voik.agesandtheart.generation

import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.MobSpawnSettings
import java.util.concurrent.ConcurrentHashMap

/**
 * The creatures that may join a biome's list in a `varied inhabitants` Age: those of the Age's biomes with a climate
 * like its own ([ClimateNeighbours]), commonest across them first. Worked out once per biome and pass.
 */
object VariedInhabitants {

    fun joiningIn(biomes: Collection<Holder<Biome>>): (Identifier?, MobCategory) -> List<MobSpawnSettings.SpawnerData> {
        val neighbours = ClimateNeighbours(biomes, WARMTH_APART)
        val byName = biomes.mapNotNull { biome -> biome.unwrapKey().orElse(null)?.let { it.identifier() to biome } }.toMap()
        val workedOut = ConcurrentHashMap<Pair<Identifier, MobCategory>, List<MobSpawnSettings.SpawnerData>>()
        return { name, category ->
            val biome = name?.let(byName::get)
            if (name == null || biome == null) emptyList()
            else workedOut.computeIfAbsent(name to category) { commonestAmong(neighbours.of(biome), category) }
        }
    }

    /** Every creature [biomes] offer in [category], the one they offer most of first. */
    private fun commonestAmong(biomes: List<Holder<Biome>>, category: MobCategory): List<MobSpawnSettings.SpawnerData> {
        val offered = biomes.flatMap { biome ->
            biome.value().attributes.applyModifier(EnvironmentAttributes.NATURAL_MOB_SPAWNS, MobSpawnSettings.EMPTY)
                .getMobsToSpawn(category).unwrap()
        }
        return offered.groupBy { BuiltInRegistries.ENTITY_TYPE.getKey(it.value().type()) }.values
            .sortedByDescending { entries -> entries.sumOf { it.weight() } }
            .map { entries -> entries.first().value() }
    }

    /** How much warmer or colder a biome may be and still count as like another, on vanilla's temperature. */
    private const val WARMTH_APART = 0.3f
}
