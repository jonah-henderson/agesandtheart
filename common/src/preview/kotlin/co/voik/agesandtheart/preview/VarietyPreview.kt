package co.voik.agesandtheart.preview

import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.age.aspect.Span
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Underground
import co.voik.agesandtheart.worldgen.VerticalWindow
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * One landform from above with one of `LandVariety`'s dials at none, as an Age that said nothing gets it, and at the
 * most a word can ask — side by side, so what the dial does can be judged at the scale it works at. Shaded by height
 * over the range the three share, water blue under the landform's waterline and wherever its own rivers stand.
 *
 * `--args="<landform key> [radius] [step] [variety|wear]"`, e.g. `--args="sheer 2048 8 wear"`.
 */
fun main(arguments: Array<String>) {
    val key = arguments.getOrNull(0) ?: "gentle"
    val radius = arguments.getOrNull(1)?.toInt() ?: DEFAULT_RADIUS
    val step = arguments.getOrNull(2)?.toInt() ?: DEFAULT_STEP
    val dial = when (val named = arguments.getOrNull(3) ?: Terrain.VARIETY.name) {
        Terrain.VARIETY.name -> Terrain.VARIETY
        Terrain.WEAR.name -> Terrain.WEAR
        else -> error("Unknown dial '$named'. Try variety or wear.")
    }
    val terrain = Terrain.entries.firstOrNull { it.key == key }
        ?: error("Unknown landform '$key'. Try one of: ${Terrain.entries.filterNot { it.isVanillas }.joinToString { it.key }}")

    val strengths = listOf(-1.0, 0.0, 1.0)
    val across = radius * 2 / step
    val panels = strengths.map { setting ->
        val options = Options(mapOf(dial.name to listOf(Span(setting, setting).spelled())))
        val shape = terrain.ground(Underground.NONE, Options.NONE, options, VerticalWindow.DEFAULT, SALT).shape
        val water = terrain.standingWater(options, SALT)
        val plan = Array(across) { IntArray(across) }
        val rivers = Array(across) { BooleanArray(across) }
        // Tile by tile, as a level generates chunk by chunk, so the fields' remembered neighbours are still remembered.
        for (tileRow in 0..<across step TILE) for (tileColumn in 0..<across step TILE) {
            for (row in tileRow..<minOf(tileRow + TILE, across)) for (column in tileColumn..<minOf(tileColumn + TILE, across)) {
                val worldX = column * step - radius
                val worldZ = row * step - radius
                val top = shape.columnSpans(worldX, worldZ).highestSolidY ?: VerticalWindow.DEFAULT.minY
                plan[row][column] = top
                rivers[row][column] = (water?.columnSpans(worldX, worldZ)?.highestSolidY ?: top) > top
            }
        }
        plan to rivers
    }
    val heights = panels.map { it.first }
    val lowest = heights.minOf { plan -> plan.minOf { it.min() } }
    val highest = heights.maxOf { plan -> plan.maxOf { it.max() } }
    val waterline = terrain.waterlineAt(Options.NONE, SALT)

    val image = BufferedImage(across * strengths.size + GAP * (strengths.size - 1), across, BufferedImage.TYPE_INT_RGB)
    panels.forEachIndexed { panel, (plan, rivers) ->
        val left = panel * (across + GAP)
        for (row in 0..<across) for (column in 0..<across) {
            val height = plan[row][column]
            val shade = (0.35 * (height - lowest).toDouble() / (highest - lowest).coerceAtLeast(1) +
                0.65 * hillshade(plan, row, column, step)).toFloat().coerceIn(0f, 1f)
            val isUnderWater = (waterline != null && height < waterline) || rivers[row][column]
            val colour = if (isUnderWater) Color(0.1f, 0.2f + shade * 0.3f, 0.5f + shade * 0.4f)
            else Color(shade, shade, shade)
            image.setRGB(left + column, row, colour.rgb)
        }
    }
    val output = File("build/preview").apply { mkdirs() }
    val file = File(output, "${dial.name}-$key.png")
    ImageIO.write(image, "png", file)

    println("$key, ${dial.name}: heights $lowest..$highest, waterline $waterline; none | unsaid | most → ${file.absolutePath}")
    heights.zip(listOf("none", "unsaid", "most")).forEach { (plan, label) ->
        val tops = plan.flatMap { it.toList() }.sorted()
        fun percentile(share: Double) = tops[((tops.size - 1) * share).toInt()]
        println("  %-7s p10 %4d  p50 %4d  p90 %4d  p99 %4d".format(label, percentile(0.1), percentile(0.5), percentile(0.9), percentile(0.99)))
    }
}

/** Light from the north-west at forty-five degrees, so a gully reads by its walls rather than its depth. */
private fun hillshade(plan: Array<IntArray>, row: Int, column: Int, step: Int): Double {
    val last = plan.size - 1
    val eastward = (plan[row][minOf(column + 1, last)] - plan[row][maxOf(column - 1, 0)]) / (2.0 * step)
    val southward = (plan[minOf(row + 1, last)][column] - plan[maxOf(row - 1, 0)][column]) / (2.0 * step)
    val facing = (eastward + southward) / kotlin.math.sqrt(2.0)
    return (0.5 - facing * 0.5).coerceIn(0.0, 1.0)
}

private const val DEFAULT_RADIUS = 1536
private const val DEFAULT_STEP = 8
private const val GAP = 4
private const val TILE = 16
private const val SALT = 0x5EEDL
