package co.voik.agesandtheart.worldgen.biome

import co.voik.agesandtheart.worldgen.field.ColumnMemo
import co.voik.agesandtheart.worldgen.field.TerrainField
import net.minecraft.core.Holder
import net.minecraft.world.level.biome.Biome

/**
 * A biome the land declares for a volume of its own — wherever [where] is solid, read as a volume rather than as
 * rock. No climate can say "inside a hollow tree", so the landform says it, and the climate answers everywhere else.
 */
class BiomeVolume(val biome: Holder<Biome>, private val where: TerrainField) {
    /** [where]'s columns, remembered: a biome is asked per quart cell, many levels to a column. */
    private val columns = ColumnMemo(where::columnSpans)

    fun biomeAt(blockX: Int, blockY: Int, blockZ: Int): Holder<Biome>? =
        biome.takeIf { columns.at(blockX, blockZ).contains(blockY) }
}
