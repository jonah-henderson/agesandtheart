package co.voik.agesandtheart.preview

import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Underground
import co.voik.agesandtheart.worldgen.VerticalWindow
import co.voik.agesandtheart.worldgen.field.Reconciled
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.worldgen.field.Regions
import co.voik.agesandtheart.worldgen.field.TerrainField
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Two landforms dividing the ground from above, as rival words leave them (a sheared seam) and as `and` reconciles
 * them, side by side, with a slice through the middle of each underneath. Shaded by height over the range both
 * share, water blue under the waterline.
 *
 * `--args="<landform> <landform> [radius] [step]"`, e.g. `--args="gentle sheer 1024 4"`.
 */
fun main(arguments: Array<String>) {
    val terrains = arguments.take(2).map { key ->
        Terrain.entries.firstOrNull { it.key == key } ?: error("Unknown landform '$key'")
    }
    require(terrains.size == 2) { "Name two landforms, e.g. gentle sheer" }
    val radius = arguments.getOrNull(2)?.toInt() ?: DEFAULT_RADIUS
    val step = arguments.getOrNull(3)?.toInt() ?: DEFAULT_STEP

    val shapes = terrains.mapIndexed { member, terrain ->
        terrain.ground(Underground.NONE, Options.NONE, Options.NONE, VerticalWindow.DEFAULT, SALT + member).shape
    }
    val map = RegionMap(members = 2, scale = TERRITORY_BLOCKS, blend = 0, originX = 0, originZ = 0, seed = SALT)
    val views: List<TerrainField> = listOf(Regions(shapes, map), Reconciled(shapes, shapes, map, RECONCILED_BLOCKS, keepsFloor = true))

    val across = radius * 2 / step
    val tops = views.map { field ->
        Array(across) { row ->
            IntArray(across) { column ->
                field.columnSpans(column * step - radius, row * step - radius).highestSolidY ?: VerticalWindow.DEFAULT.minY
            }
        }
    }
    val lowest = tops.minOf { plan -> plan.minOf { it.min() } }
    val highest = tops.maxOf { plan -> plan.maxOf { it.max() } }
    val waterline = terrains.firstNotNullOfOrNull { it.waterlineAt(Options.NONE, SALT) }
    val sliceHeight = (highest - lowest + 1).coerceAtLeast(1)

    val image = BufferedImage(across * 2 + GAP, across + GAP + sliceHeight, BufferedImage.TYPE_INT_RGB)
    tops.forEachIndexed { panel, plan ->
        val left = panel * (across + GAP)
        for (row in 0..<across) for (column in 0..<across) {
            val height = plan[row][column]
            val shade = ((height - lowest).toDouble() / (highest - lowest).coerceAtLeast(1)).toFloat()
            val colour = if (waterline != null && height < waterline) Color(0.1f, 0.2f + shade * 0.3f, 0.5f + shade * 0.4f)
            else Color(shade, shade, shade)
            image.setRGB(left + column, row, colour.rgb)
        }
        // A slice along the middle row, read from the plan: each column filled up to its top.
        val middle = plan[across / 2]
        for (column in 0..<across) {
            val top = middle[column] - lowest
            for (level in 0..<sliceHeight) {
                val colour = when {
                    level <= top -> Color.LIGHT_GRAY
                    waterline != null && level + lowest < waterline -> Color(0.1f, 0.2f, 0.6f)
                    else -> Color.BLACK
                }
                image.setRGB(left + column, across + GAP + sliceHeight - 1 - level, colour.rgb)
            }
        }
    }
    val output = File("build/preview").apply { mkdirs() }
    val file = File(output, "joined-${terrains[0].key}-${terrains[1].key}.png")
    ImageIO.write(image, "png", file)
    println("${terrains[0].key} and ${terrains[1].key}: heights $lowest..$highest; sheared | reconciled → ${file.absolutePath}")
}

private const val DEFAULT_RADIUS = 1024
private const val DEFAULT_STEP = 4
private const val GAP = 4
private const val SALT = 0x5EEDL
private const val TERRITORY_BLOCKS = 400.0
private const val RECONCILED_BLOCKS = 96.0
