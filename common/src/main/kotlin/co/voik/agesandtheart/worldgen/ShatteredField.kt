package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.CellCanyon
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.worldgen.field.Regions
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField
import kotlin.math.roundToInt

/**
 * A plate broken into fault blocks: every cell of a mosaic stands at a height of its own, with a gorge
 * down every join — some cells the full table, some dropped most of the way to the floor.
 *
 * **The sibling of [CanyonlandsField], and deliberately the unrealistic one.** That world is one table cut
 * by canyons running three ways, which is at least the shape a set of rivers might leave. This one is cut
 * along the boundaries of a mosaic, so every join is a peer of every other: there is no trunk, no
 * tributary and no downhill anywhere in it. Water could not have made it, and it does not pretend to.
 * The stepped heights are what tell the two apart from the ground, since a slot between two tables at
 * one height reads the same whichever plan it was cut to.
 *
 * It stands on **canyonlands' floor and under its highest table**, so it wears the same weather rather than
 * needing a profile of its own. Give it one the moment the two should differ.
 */
object ShatteredField {

    /** [scale] is [SizeScale]'s factor, read exactly as canyonlands reads it: see [CanyonlandsField.world]. */
    fun world(salt: Long = 0L, scale: Double = SizeScale.ORDINARY): TerrainField =
        CanyonlandsField.weathered(bareWorld(salt, scale), scale)

    /** The cracks before the weather reaches them — the previewer's other half, and nothing else's. */
    fun bareWorld(salt: Long = 0L, scale: Double = SizeScale.ORDINARY): TerrainField =
        CellCanyon.cut(ground(scale, salt), cells(salt, scale))

    /**
     * The plate the cells are cracked out of: one table per member of the mosaic, each at its own height,
     * so a cell stands wherever its member does. The steps fall on the joins, which the gorges then cut.
     */
    fun ground(scale: Double = SizeScale.ORDINARY, salt: Long = 0L): TerrainField = Regions(
        members = TABLE_HEIGHTS.map { share -> Slab(lowY = VerticalWindow.MIN_Y, highY = tableY(share, scale)) },
        map = mosaic(salt).resized(scale / SizeScale.COLOSSAL),
    )

    /** A table [share] of the way from the floor to the full plateau, at [scale]. */
    private fun tableY(share: Double, scale: Double): Int =
        FLOOR_Y + ((CanyonlandsField.plateauY(scale) - FLOOR_Y) * share).roundToInt()

    fun cells(salt: Long = 0L, scale: Double = SizeScale.ORDINARY) = CellCanyon(
        map = mosaic(salt),
        halfWidth = HALF_WIDTH,
        floorY = FLOOR_Y,
        rimY = PLATEAU_Y + 1,
        seed = CRACK_SEED xor salt,
    ).resized(scale / SizeScale.COLOSSAL, FLOOR_Y)

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

    /**
     * Canyonlands' table and floor — see the class note on why they are shared rather than restated. The
     * table is the **highest** cell's; the rest stand below it by [TABLE_HEIGHTS].
     */
    const val PLATEAU_Y = CanyonlandsField.PLATEAU_Y
    const val FLOOR_Y = CanyonlandsField.FLOOR_Y
    const val RIVER_LEVEL = CanyonlandsField.RIVER_LEVEL

    /**
     * How many cells the mosaic draws between. Six, so about one join in six is missing where two
     * neighbours draw the same number and run together — which is what keeps the plate from reading as
     * evenly cracked. Fewer merges more; many more and every cell is its own.
     */
    private const val CELLS = 6

    /**
     * Each member's table, as a share of the way from the floor to the full plateau — one per member of
     * [CELLS]. The lowest is a graben about a third of the way up: low enough to read as a block that fell,
     * high enough that it is still a table and not a basin.
     */
    private val TABLE_HEIGHTS = listOf(1.0, 0.86, 0.72, 0.58, 0.44, 0.30)

    /** How far a cell runs, in blocks — the mesa-size parameter, and the only one that matters much. */
    private const val CELL_SCALE = 620.0

    /** Half a join's width, so about 140 across against a 145-block drop. Canyonlands' proportion. */
    private const val HALF_WIDTH = 70.0

    private const val MOSAIC_SEED = 0xC7AC_ED0L
    private const val CRACK_SEED = 0xC7AC_5EEDL
}
