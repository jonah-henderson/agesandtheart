package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.carver.Weathering
import co.voik.agesandtheart.worldgen.field.CellCanyon
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Weathered

/**
 * A tableland cracked into cells, with a gorge down every join — mesa country as a broken plate.
 *
 * **The sibling of [CanyonlandsField], and deliberately the unrealistic one.** That world is cut by
 * canyons running three ways, which is at least the shape a set of rivers might leave. This one is cut
 * along the boundaries of a mosaic, so every join is a peer of every other: there is no trunk, no
 * tributary and no downhill anywhere in it. Water could not have made it, and it does not pretend to.
 *
 * It stands on **canyonlands' own table and floor**, so it wears the same weather rather than needing a
 * fifth tuned profile. Give it its own the moment the two should differ.
 */
object ShatteredField {

    fun world(salt: Long = 0L): TerrainField =
        Weathered.sculpting(bareWorld(salt), Weathering.CANYONLANDS, SHELTER_REACH)

    /** The cracks before the weather reaches them — the previewer's other half, and nothing else's. */
    fun bareWorld(salt: Long = 0L): TerrainField = CellCanyon.cut(ground(), cells(salt))

    /** The plate the cells are cracked out of. */
    fun ground(): TerrainField = Slab(lowY = WORLD_FLOOR, highY = PLATEAU_Y)

    fun cells(salt: Long = 0L) = CellCanyon(
        map = mosaic(salt),
        halfWidth = HALF_WIDTH,
        floorY = FLOOR_Y,
        rimY = PLATEAU_Y + 1,
        seed = CRACK_SEED xor salt,
    )

    /**
     * The mosaic the joins run along.
     *
     * [RegionMap.blend] is zero because nothing here asks which cell a column is *in* — only how far it
     * stands from the nearest join, which is measured before the blend's dither would fray it anyway.
     * [CELLS] is what decides how often two neighbours draw the same number and run together, so it is a
     * parameter on how irregular the plate is as much as on anything.
     */
    fun mosaic(salt: Long = 0L) = RegionMap(
        members = CELLS,
        scale = CELL_SCALE,
        blend = 0,
        originX = 0,
        originZ = 0,
        seed = MOSAIC_SEED xor salt,
    )

    private const val WORLD_FLOOR = -64

    /** Canyonlands' table and floor — see the class note on why they are shared rather than restated. */
    const val PLATEAU_Y = CanyonlandsField.PLATEAU_Y
    const val FLOOR_Y = CanyonlandsField.FLOOR_Y
    const val RIVER_LEVEL = CanyonlandsField.RIVER_LEVEL

    /**
     * How many cells the mosaic draws between. Six, so about one join in six is missing where two
     * neighbours draw the same number and run together — which is what keeps the plate from reading as
     * evenly cracked. Fewer merges more; many more and every cell is its own.
     */
    private const val CELLS = 6

    /** How far a cell runs, in blocks — the mesa-size parameter, and the only one that matters much. */
    private const val CELL_SCALE = 620.0

    /** Half a join's width, so about 140 across against a 145-block drop. Canyonlands' proportion. */
    private const val HALF_WIDTH = 70.0

    /** How far into a wall the weather works, the same as canyonlands' for the same reason. */
    private const val SHELTER_REACH = 24

    private const val MOSAIC_SEED = 0xC7AC_ED0L
    private const val CRACK_SEED = 0xC7AC_5EEDL
}
