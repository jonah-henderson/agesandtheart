package co.voik.agesandtheart.age.aspect

import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.level.biome.MobSpawnSettings

/**
 * Which creatures and features the pack already puts somewhere — what decides whether naming one scales it
 * where it lives or brings it in where it lives nowhere ([Claim.introduces]).
 *
 * Asked once per Age opened rather than remembered: a walk over the biomes and structures is cheap beside
 * building a generator, and a registry access outlives no server.
 */
object WhereThingsGrow {
    /** Every creature some biome offers in any spawn pass, or some structure offers inside itself. */
    fun creaturesListed(registries: RegistryAccess): Set<Identifier> {
        val offeredByBiomes = registries.lookupOrThrow(Registries.BIOME).listElements().toList().flatMap { biome ->
            // A biome's spawners are an environment attribute in 26.3, laid over nothing here.
            val settings = biome.value().attributes
                .applyModifier(EnvironmentAttributes.NATURAL_MOB_SPAWNS, MobSpawnSettings.EMPTY)
            MobCategory.entries.flatMap { category ->
                settings.getMobsToSpawn(category).unwrap().map { it.value().type() }
            }
        }
        val offeredByStructures = registries.lookupOrThrow(Registries.STRUCTURE).listElements().toList()
            .flatMap { structure ->
                structure.value().spawnOverrides().values.flatMap { override ->
                    override.spawns().unwrap().map { it.value().type() }
                }
            }
        return (offeredByBiomes + offeredByStructures).map(BuiltInRegistries.ENTITY_TYPE::getKey).toSet()
    }

    /** Every placed feature some biome grows. */
    fun featuresGrown(registries: RegistryAccess): Set<Identifier> =
        registries.lookupOrThrow(Registries.BIOME).listElements().toList()
            .flatMap { biome -> biome.value().generationSettings.features().flatMap { it.toList() } }
            .mapNotNull { it.unwrapKey().orElse(null)?.identifier() }
            .toSet()
}
