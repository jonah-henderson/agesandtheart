package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.worldgen.PreliminarySurface
import co.voik.agesandtheart.worldgen.VerticalWindow
import net.minecraft.world.level.levelgen.Aquifer
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext

/**
 * The surface an aquifer reads where no generator hands it one: [PreliminarySurface] over [field] and the
 * rock it was cut from, in the default window — what the generator's router answers.
 */
fun preliminarySurfaceOf(field: TerrainField, uncut: TerrainField? = null): WaterTable.SurfaceAt {
    val window = VerticalWindow.DEFAULT
    val surface = PreliminarySurface(field, uncut, window.minY, window.topY - 1)
    return WaterTable.SurfaceAt { worldX, worldZ ->
        Math.floor(surface.sampleValue(SamplerContext.EMPTY_UNCACHED, worldX, 0, worldZ).toDouble()).toInt()
    }
}

/**
 * This table's aquifer over [field], reading [preliminarySurfaceOf] and with no deep dark anywhere. [seaFill] is
 * only asked what the shape pours for itself, so the default is a shape that pours nothing.
 */
fun WaterTable.aquiferOver(field: TerrainField, uncut: TerrainField? = null, seaFill: SeaFill = SeaFill.NONE): Aquifer =
    aquiferFor(field, preliminarySurfaceOf(field, uncut), isDeepDark = null, seaFill)
