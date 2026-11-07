package co.voik.agesandtheart.preview

import co.voik.agesandtheart.worldgen.IslandsField
import co.voik.agesandtheart.worldgen.field.Wear
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The archipelago's first island from above at each age, side by side — young, behind a barrier, sinking, an atoll —
 * with the water drawn: sand just over it, green by height above, and blue deepening under it. Below each, a slice
 * through the island's middle, stretched upward so a reef a block under the water can be told from one at it.
 *
 * `--args="[radius] [step] [size] [before]"`, e.g. `--args="800 2 0"`; `before` draws the islands as they aged
 * before generator 23.
 */
fun main(arguments: Array<String>) {
    val radius = arguments.getOrNull(0)?.toInt() ?: DEFAULT_RADIUS
    val step = arguments.getOrNull(1)?.toInt() ?: DEFAULT_STEP
    val size = arguments.getOrNull(2)?.toDouble()
    val agedAsBefore = arguments.getOrNull(3) == "before"
    val across = radius * 2 / step

    val panels = WEARS.map { wear ->
        val field = IslandsField.world(size, wear = Wear(seed = 0L, mean = wear, spread = 0.0), agedAsBefore = agedAsBefore)
        Array(across) { imageX -> IntArray(across) { imageZ ->
            field.columnSpans((imageX - across / 2) * step, (imageZ - across / 2) * step).highestSolidY ?: SEABED
        } }
    }

    val image = BufferedImage(across * WEARS.size, across + SLICE_HEIGHT, BufferedImage.TYPE_INT_RGB)
    panels.forEachIndexed { panel, tops ->
        for (imageX in 0..<across) for (imageZ in 0..<across) image.setRGB(panel * across + imageX, imageZ, shade(tops[imageX][imageZ]))
        for (imageX in 0..<across) {
            val top = tops[imageX][across / 2]
            for (row in 0..<SLICE_HEIGHT) {
                val y = SLICE_TOP - row / SLICE_STRETCH
                val colour = when {
                    y <= top -> ROCK
                    y <= SEA -> WATER
                    else -> SKY
                }
                image.setRGB(panel * across + imageX, across + row, colour)
            }
        }
    }
    val output = File("build/preview").apply { mkdirs() }
    val name = if (agedAsBefore) "island-ages-before.png" else "island-ages.png"
    ImageIO.write(image, "png", File(output, name))
    println("wrote build/preview/$name: wear ${WEARS.joinToString()} from left to right")
}

private fun shade(top: Int): Int = when {
    top < SEA -> {
        val depth = ((SEA - top).toDouble() / DEEPEST_DRAWN).coerceIn(0.0, 1.0)
        mix(SHALLOW, DEEP, depth)
    }
    top <= SEA + SAND_ABOVE -> SAND
    else -> mix(LOWLAND, HIGHLAND, ((top - SEA).toDouble() / HIGHEST_DRAWN).coerceIn(0.0, 1.0))
}

private fun mix(from: Color, to: Color, share: Double): Int = Color(
    (from.red + (to.red - from.red) * share).toInt(),
    (from.green + (to.green - from.green) * share).toInt(),
    (from.blue + (to.blue - from.blue) * share).toInt(),
).rgb

private val WEARS = listOf(0.0, 0.5, 1.0, 1.5, 2.0)
private const val DEFAULT_RADIUS = 800
private const val DEFAULT_STEP = 2
private const val SEA = IslandsField.SEA_LEVEL
private const val SEABED = IslandsField.SEABED_Y
private const val SAND_ABOVE = 4
private const val DEEPEST_DRAWN = 10.0
private const val HIGHEST_DRAWN = 90.0
private const val SLICE_HEIGHT = 240
private const val SLICE_STRETCH = 3
private const val SLICE_TOP = SEA + 60

private val SHALLOW = Color(120, 210, 220)
private val DEEP = Color(20, 50, 120)
private val SAND = Color(235, 220, 160).rgb
private val LOWLAND = Color(90, 160, 70)
private val HIGHLAND = Color(70, 80, 60)
private val ROCK = Color(110, 100, 90).rgb
private val WATER = Color(60, 110, 200).rgb
private val SKY = Color(225, 235, 245).rgb
