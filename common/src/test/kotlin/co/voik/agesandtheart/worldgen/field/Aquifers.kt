package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.PreliminarySurface
import co.voik.agesandtheart.worldgen.VerticalWindow
import net.minecraft.world.level.levelgen.Aquifer
import net.minecraft.world.level.levelgen.DensityFunction

/** The overworld's noise cell height, which is how `findTopSurface` steps. */
private const val DEFAULT_CELL_HEIGHT = 8

/**
 * The surface an aquifer reads where no generator hands it one: [PreliminarySurface] over [field] and the
 * rock it was cut from, in the default window — what the generator's router answers.
 */
fun preliminarySurfaceOf(field: TerrainField, uncut: TerrainField? = null): WaterTable.SurfaceAt {
    val window = VerticalWindow.DEFAULT
    val surface = PreliminarySurface(field, uncut, window.minY, window.topY - 1, DEFAULT_CELL_HEIGHT)
    return WaterTable.SurfaceAt { worldX, worldZ ->
        Math.floor(surface.compute(DensityFunction.SinglePointContext(worldX, 0, worldZ))).toInt()
    }
}

/** This table's aquifer over [field], reading [preliminarySurfaceOf] and with no deep dark anywhere. */
fun WaterTable.aquiferOver(field: TerrainField, uncut: TerrainField? = null): Aquifer =
    aquiferFor(field, preliminarySurfaceOf(field, uncut), isDeepDark = null)
