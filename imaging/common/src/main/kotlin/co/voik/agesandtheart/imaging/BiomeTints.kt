package co.voik.agesandtheart.imaging

import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeSpecialEffects
import java.util.IdentityHashMap

/**
 * Roughly what colour a biome's grass and leaves are, for the server's sketch. The game's own tints come from colour
 * maps only a client loads, so this stands in with their three corners — hot and wet, hot and dry, cold — blended by the
 * biome's temperature and rainfall as the colour maps are read. A biome that names its own colours keeps them.
 */
object BiomeTints {

    private val grass = IdentityHashMap<Biome, Int>()
    private val leaves = IdentityHashMap<Biome, Int>()

    fun grassOf(biome: Biome): Int = grass.getOrPut(biome) {
        val effects = biome.specialEffects
        val base = effects.grassColorOverride().orElseGet { fromClimate(biome, GRASS_HOT_WET, GRASS_HOT_DRY, GRASS_COLD) }
        when (effects.grassColorModifier()) {
            BiomeSpecialEffects.GrassColorModifier.SWAMP -> SWAMP_GRASS
            BiomeSpecialEffects.GrassColorModifier.DARK_FOREST -> ((base and DARK_FOREST_MASK) + DARK_FOREST_SHIFT) shr 1
            else -> base
        }
    }

    fun leavesOf(biome: Biome): Int = leaves.getOrPut(biome) {
        biome.specialEffects.foliageColorOverride().orElseGet { fromClimate(biome, LEAVES_HOT_WET, LEAVES_HOT_DRY, LEAVES_COLD) }
    }

    private fun fromClimate(biome: Biome, hotAndWet: Int, hotAndDry: Int, cold: Int): Int {
        val climate = biome.climateSettings
        val warmth = climate.temperature().coerceIn(0.0f, 1.0f)
        val wetness = climate.downfall().coerceIn(0.0f, 1.0f) * warmth
        return mixed(listOf(hotAndWet to wetness, hotAndDry to warmth - wetness, cold to 1.0f - warmth))
    }

    private fun mixed(parts: List<Pair<Int, Float>>): Int {
        fun channel(shift: Int) = parts.sumOf { (colour, weight) -> (colour shr shift and 0xFF) * weight.toDouble() }.toInt().coerceIn(0, 0xFF)
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private const val GRASS_HOT_WET = 0x47CD33
    private const val GRASS_HOT_DRY = 0xBFB755
    private const val GRASS_COLD = 0x80B497
    private const val LEAVES_HOT_WET = 0x1ABF00
    private const val LEAVES_HOT_DRY = 0xAEA42A
    private const val LEAVES_COLD = 0x60A17B
    private const val SWAMP_GRASS = 0x6A7039
    private const val DARK_FOREST_MASK = 0xFEFEFE
    private const val DARK_FOREST_SHIFT = 0x28340A
}
