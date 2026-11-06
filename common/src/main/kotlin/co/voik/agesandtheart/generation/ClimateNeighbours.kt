package co.voik.agesandtheart.generation

import net.minecraft.core.Holder
import net.minecraft.tags.BiomeTags
import net.minecraft.world.level.biome.Biome

/**
 * Which of an Age's [biomes] have a climate like one another's: as warm within [warmthApart], alike in whether it
 * rains, and both land or both water. Only the Age's own, so a desert's neighbours are never the Nether's.
 */
class ClimateNeighbours(private val biomes: Collection<Holder<Biome>>, private val warmthApart: Float) {

    /** The biomes, other than [biome] itself, whose climate is like its own. */
    fun of(biome: Holder<Biome>): List<Holder<Biome>> = biomes.filter { other -> other != biome && areAlike(biome, other) }

    private fun areAlike(one: Holder<Biome>, other: Holder<Biome>): Boolean {
        val asWarm = kotlin.math.abs(one.value().baseTemperature - other.value().baseTemperature) <= warmthApart
        val alikeInRain = one.value().hasPrecipitation() == other.value().hasPrecipitation()
        return asWarm && alikeInRain && isWater(one) == isWater(other)
    }

    private fun isWater(biome: Holder<Biome>): Boolean =
        biome.`is`(BiomeTags.IS_OCEAN) || biome.`is`(BiomeTags.IS_DEEP_OCEAN) || biome.`is`(BiomeTags.IS_RIVER)
}
