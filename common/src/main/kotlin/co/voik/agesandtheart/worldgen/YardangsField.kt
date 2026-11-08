package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Yardangs

/**
 * A dry basin of yardangs lying along the Age's wind, on a floor standing just over vanilla's sea level. Tuned at
 * [SizeScale.ORDINARY]; a size grows the ridges and the corridors between them alike, about the floor.
 */
object YardangsField {

    fun world(salt: Long, scale: Double, variety: Double, windBearingDegrees: Double): TerrainField =
        Yardangs(
            seed = salt xor SEED_SALT,
            bearingDegrees = windBearingDegrees,
            floorY = FLOOR_Y,
            cellLength = CELL_LENGTH,
            cellWidth = CELL_WIDTH,
            tallest = TALLEST,
            variety = variety,
        ).resized(scale, FLOOR_Y + 1)

    const val FLOOR_Y = 64

    private const val CELL_LENGTH = 170.0
    private const val CELL_WIDTH = 52.0
    private const val TALLEST = 34.0
    private const val SEED_SALT = 0x59415244L
}
