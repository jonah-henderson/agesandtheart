package co.voik.agesandtheart.preview

import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Underground
import co.voik.agesandtheart.worldgen.VerticalWindow
import co.voik.agesandtheart.worldgen.field.Reconciled
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.worldgen.field.TableLayout
import co.voik.agesandtheart.worldgen.field.TerrainTable
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Several landforms dividing the ground from above: their territories as chance draws them (`patchwork`), as the
 * terrain table lays them out, and the land the table's layout makes once reconciled — so whether neighbours are
 * plausible can be judged at a glance. Each landform's share of the ground is printed for both layouts.
 *
 * `--args="<landform> <landform> … [radius] [step]"`, e.g. `--args="mountainous gentle canyoned archipelagic 3072 8"`.
 */
fun main(arguments: Array<String>) {
    val numbers = arguments.mapNotNull { it.toIntOrNull() }
    val terrains = arguments.filter { it.toIntOrNull() == null }.map { key ->
        Terrain.entries.firstOrNull { it.key == key } ?: error("Unknown landform '$key'")
    }
    require(terrains.size >= 2) { "Name two landforms or more, e.g. mountainous gentle archipelagic" }
    val radius = numbers.getOrNull(0) ?: DEFAULT_RADIUS
    val step = numbers.getOrNull(1) ?: DEFAULT_STEP

    val table = TerrainTable(SALT)
    val drawn = RegionMap(members = terrains.size, scale = TERRITORY_BLOCKS, blend = 0, originX = 0, originZ = 0, seed = SALT)
    val laidOut = drawn.copy(layout = TableLayout(table, terrains.map { it.home }, emptyList(), SALT))
    val sea = Terrain.seaSettingFor(terrains, { Options.NONE }, { member -> SALT + member })
    val shapes = terrains.mapIndexed { member, terrain ->
        terrain.ground(Underground.NONE, Options.NONE, Options.NONE, VerticalWindow.DEFAULT, SALT + member, table, sea).shape
    }
    val land = Reconciled(shapes, shapes, laidOut, RECONCILED_BLOCKS, keepsFloor = true)

    val across = radius * 2 / step
    fun worldOf(index: Int) = index * step - radius
    val drawnMembers = Array(across) { row -> IntArray(across) { column -> drawn.memberAt(worldOf(column), worldOf(row)) } }
    val laidMembers = Array(across) { row -> IntArray(across) { column -> laidOut.memberAt(worldOf(column), worldOf(row)) } }
    val tops = Array(across) { IntArray(across) }
    for (tileRow in 0..<across step TILE) for (tileColumn in 0..<across step TILE) {
        for (row in tileRow..<minOf(tileRow + TILE, across)) for (column in tileColumn..<minOf(tileColumn + TILE, across)) {
            tops[row][column] = land.columnSpans(worldOf(column), worldOf(row)).highestSolidY ?: VerticalWindow.DEFAULT.minY
        }
    }
    val lowest = tops.minOf { it.min() }
    val highest = tops.maxOf { it.max() }
    val waterline = terrains.withIndex().firstNotNullOfOrNull { (member, terrain) -> terrain.waterlineAt(Options.NONE, SALT + member, sea) }

    val image = BufferedImage(across * 3 + GAP * 2, across, BufferedImage.TYPE_INT_RGB)
    for (row in 0..<across) for (column in 0..<across) {
        image.setRGB(column, row, PALETTE[drawnMembers[row][column] % PALETTE.size].rgb)
        image.setRGB(across + GAP + column, row, PALETTE[laidMembers[row][column] % PALETTE.size].rgb)
        val height = tops[row][column]
        val shade = ((height - lowest).toDouble() / (highest - lowest).coerceAtLeast(1)).toFloat()
        val colour = if (waterline != null && height < waterline) Color(0.1f, 0.2f + shade * 0.3f, 0.5f + shade * 0.4f)
        else Color(shade, shade, shade)
        image.setRGB((across + GAP) * 2 + column, row, colour.rgb)
    }
    val output = File("build/preview").apply { mkdirs() }
    val file = File(output, "table-${terrains.joinToString("-") { it.key }}.png")
    ImageIO.write(image, "png", file)

    println("chance | table | land → ${file.absolutePath}")
    val cells = (across * across).toDouble()
    terrains.forEachIndexed { member, terrain ->
        val byChance = drawnMembers.sumOf { row -> row.count { it == member } } / cells
        val byTable = laidMembers.sumOf { row -> row.count { it == member } } / cells
        println("  %-14s %-8s chance %3.0f%%  table %3.0f%%".format(terrain.key, COLOUR_NAMES[member % COLOUR_NAMES.size], byChance * 100, byTable * 100))
    }
}

private val PALETTE = listOf(
    Color(0xC0392B), Color(0x27AE60), Color(0xD4AC0D), Color(0x2E86C1), Color(0x8E44AD), Color(0xE67E22), Color(0x16A085),
)
private val COLOUR_NAMES = listOf("red", "green", "yellow", "blue", "purple", "orange", "teal")

private const val DEFAULT_RADIUS = 3072
private const val DEFAULT_STEP = 8
private const val GAP = 4
private const val TILE = 16
private const val TERRITORY_BLOCKS = 768.0
private const val RECONCILED_BLOCKS = 96.0
private const val SALT = 0x7AB1EL
