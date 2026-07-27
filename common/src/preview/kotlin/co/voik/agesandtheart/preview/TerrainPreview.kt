package co.voik.agesandtheart.preview

import co.voik.agesandtheart.worldgen.CavernField
import co.voik.agesandtheart.worldgen.ErodedField
import co.voik.agesandtheart.worldgen.NoiseField
import co.voik.agesandtheart.worldgen.PillarField
import co.voik.agesandtheart.worldgen.ShapesField
import co.voik.agesandtheart.worldgen.SpireField
import co.voik.agesandtheart.worldgen.carver.Weathering
import co.voik.agesandtheart.worldgen.field.TerrainField
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Draws an Age's terrain *shape* to PNGs, offline, in about a second — so a change to a field tree or to
 * the weathering can be looked at without building a jar and booting a server.
 *
 * It gets away with this because the shape half of the toolkit needs no Minecraft runtime at all: field
 * evaluation and [Weathering] reach only for noise and plain maths, never a registry or a chunk. What it
 * therefore *cannot* show is anything material — palettes, surface rules, water — which live behind the
 * registries. This is a geometry instrument, and only that.
 *
 * Crucially it renders from the **same** [TerrainField] and [Weathering] objects that generate the world,
 * so a preview cannot quietly disagree with the game.
 *
 * Three orthogonal views, named for the axis you are looking down:
 * - `view-y.png` — from above: the plan, shaded by height. Shows layout, ridge direction, how spires are
 *   distributed.
 * - `view-z.png` — a slice on the XZ=0 plane, looking along Z. Shows vertical structure.
 * - `view-x.png` — a slice looking along X. Paired with the above it distinguishes a wall from a spire,
 *   and reveals anisotropy, since a wind-stretched world should look different down its two axes.
 *
 * The slices are the point. A single surface height cannot tell a sheer wall from a gentle slope; a
 * cross-section shows it at a glance.
 */
fun main(arguments: Array<String>) {
    val name = arguments.firstOrNull() ?: "spire"
    val subject = subjects[name] ?: error("Unknown preset '$name'. Try one of: ${subjects.keys.joinToString()}")

    val output = File("build/preview").apply { mkdirs() }
    val solid = solidity(subject)

    ImageIO.write(fromAbove(solid, subject), "png", File(output, "$name-view-y.png"))
    ImageIO.write(sliceAlongZ(solid, subject), "png", File(output, "$name-view-z.png"))
    ImageIO.write(sliceAlongX(solid, subject), "png", File(output, "$name-view-x.png"))

    report(name, solid, subject, output)
}

/** What to draw: a shape, the weathering working on it, and the window to look through. */
private class Subject(
    val field: TerrainField,
    val weathering: Weathering,
    val lowestY: Int,
    val highestY: Int,
    val radius: Int = 128,
)

private val subjects: Map<String, Subject> = mapOf(
    "spire" to Subject(SpireField.world(), Weathering.SPIRE, lowestY = 30, highestY = 300, radius = 180),
    // The same islands with weathering switched off — the pair shows what erosion is actually contributing.
    "spire-nowind" to Subject(SpireField.world(), Weathering.NONE, lowestY = 30, highestY = 300, radius = 180),
    "hills" to Subject(NoiseField.hills(), Weathering.NONE, lowestY = 20, highestY = 120),
    "pillars" to Subject(PillarField.world(), Weathering.NONE, lowestY = -70, highestY = 80),
    "shapes" to Subject(ShapesField.world(), Weathering.NONE, lowestY = 55, highestY = 130, radius = 200),
    "caverns" to Subject(CavernField.world(), Weathering.NONE, lowestY = -64, highestY = 110),
    // The tunnels on their own. A cave system reads far better as a solid lattice hanging in space than
    // as absence inside a hill, and the slices are where the network's connectedness actually shows.
    "caverns-voids" to Subject(CavernField.caves(), Weathering.NONE, lowestY = -64, highestY = 70),
    "eroded" to Subject(ErodedField.world(), Weathering.NONE, lowestY = -64, highestY = 100, radius = 200),
)

/**
 * Solidity for the whole window, resolved once. Mirrors generation exactly: the field lays rock down and
 * the weathering takes some back out.
 */
private fun solidity(subject: Subject): BooleanArray {
    val width = subject.radius * 2
    val height = subject.highestY - subject.lowestY + 1
    val solid = BooleanArray(width * width * height)

    for (imageX in 0..<width) {
        val worldX = imageX - subject.radius
        for (imageZ in 0..<width) {
            val worldZ = imageZ - subject.radius
            val spans = subject.field.columnSpans(worldX, worldZ)
            if (spans.ranges.isEmpty()) continue
            for (worldY in subject.lowestY..subject.highestY) {
                if (!spans.contains(worldY)) continue
                if (subject.weathering.erodes(worldX, worldY, worldZ)) continue
                solid[index(subject, imageX, worldY, imageZ)] = true
            }
        }
    }
    return solid
}

private fun index(subject: Subject, imageX: Int, worldY: Int, imageZ: Int): Int {
    val width = subject.radius * 2
    return (worldY - subject.lowestY) * width * width + imageZ * width + imageX
}

/**
 * Looking down: the topmost solid block per column, shaded by how high it stands.
 *
 * Shaded across the range the rock actually occupies, not the whole window. Ranging over the window makes
 * every surface come out the same brightness — the first version did, and hid the very height variation it
 * was drawn to show.
 */
private fun fromAbove(solid: BooleanArray, subject: Subject): BufferedImage {
    val width = subject.radius * 2
    val tops = Array(width) { imageX ->
        IntArray(width) { imageZ ->
            (subject.highestY downTo subject.lowestY)
                .firstOrNull { solid[index(subject, imageX, it, imageZ)] } ?: Int.MIN_VALUE
        }
    }
    val standing = tops.flatMap { row -> row.filter { it != Int.MIN_VALUE } }
    val lowest = standing.minOrNull() ?: 0
    val highest = standing.maxOrNull() ?: 1
    println("  rock stands between y=$lowest and y=$highest")

    return draw(width, width) { imageX, imageZ ->
        val top = tops[imageX][imageZ]
        if (top == Int.MIN_VALUE) EMPTY else heightShade(top, lowest, highest)
    }
}

/**
 * Looking along Z: a vertical cut, the clearest read on wall versus slope.
 *
 * Cut through the *fullest* row rather than the origin. A fixed cut is a trap — the first version sliced
 * at z=0, landed in a gap between two masses, and drew a blank image that looked like a bug.
 */
private fun sliceAlongZ(solid: BooleanArray, subject: Subject): BufferedImage {
    val width = subject.radius * 2
    val height = subject.highestY - subject.lowestY + 1
    val row = fullest(subject) { imageX, worldY, imageZ -> solid[index(subject, imageX, worldY, imageZ)] }
    println("  slice along Z cuts world z=${row - subject.radius}")
    return draw(width, height) { imageX, pixelRow ->
        val worldY = subject.highestY - pixelRow
        shadeSlice(solid[index(subject, imageX, worldY, row)], worldY)
    }
}

/** Looking along X: the perpendicular cut. Against [sliceAlongZ] it exposes wind anisotropy. */
private fun sliceAlongX(solid: BooleanArray, subject: Subject): BufferedImage {
    val width = subject.radius * 2
    val height = subject.highestY - subject.lowestY + 1
    val column = fullest(subject) { imageZ, worldY, imageX -> solid[index(subject, imageX, worldY, imageZ)] }
    println("  slice along X cuts world x=${column - subject.radius}")
    return draw(width, height) { imageZ, pixelRow ->
        val worldY = subject.highestY - pixelRow
        shadeSlice(solid[index(subject, column, worldY, imageZ)], worldY)
    }
}

/** The line through the window carrying the most rock, so a cut always has something to show. */
private fun fullest(subject: Subject, isSolid: (Int, Int, Int) -> Boolean): Int {
    val width = subject.radius * 2
    var bestLine = subject.radius
    var bestCount = -1
    for (line in 0..<width) {
        var count = 0
        for (across in 0..<width) {
            for (worldY in subject.lowestY..subject.highestY) {
                if (isSolid(across, worldY, line)) count++
            }
        }
        if (count > bestCount) {
            bestCount = count
            bestLine = line
        }
    }
    return bestLine
}

/** Solid rock light, air dark, with a faint rule every [GRID_SPACING] blocks so heights can be read off. */
private fun shadeSlice(isSolid: Boolean, worldY: Int): Int = when {
    isSolid -> ROCK
    worldY % GRID_SPACING == 0 -> GRID
    else -> EMPTY
}

private fun heightShade(top: Int, lowest: Int, highest: Int): Int {
    val fraction = (top - lowest).toDouble() / (highest - lowest).coerceAtLeast(1)
    val level = (LOWEST_SHADE + (HIGHEST_SHADE - LOWEST_SHADE) * fraction).toInt().coerceIn(0, 255)
    return (level shl 16) or (level shl 8) or level
}

/** Every pixel is [MAGNIFY] blocks square, so a 1:1 render is not too small to read. */
private fun draw(blocksWide: Int, blocksHigh: Int, shade: (Int, Int) -> Int): BufferedImage {
    val image = BufferedImage(blocksWide * MAGNIFY, blocksHigh * MAGNIFY, BufferedImage.TYPE_INT_RGB)
    for (blockX in 0..<blocksWide) {
        for (blockY in 0..<blocksHigh) {
            val colour = shade(blockX, blockY)
            for (offsetX in 0..<MAGNIFY) {
                for (offsetY in 0..<MAGNIFY) {
                    image.setRGB(blockX * MAGNIFY + offsetX, blockY * MAGNIFY + offsetY, colour)
                }
            }
        }
    }
    return image
}

/** Numbers worth having next to the pictures: how much rock stands, and how tall it gets. */
private fun report(name: String, solid: BooleanArray, subject: Subject, output: File) {
    val width = subject.radius * 2
    val columns = width * width
    var occupied = 0
    var tallest = Int.MIN_VALUE

    for (imageX in 0..<width) {
        for (imageZ in 0..<width) {
            val top = (subject.highestY downTo subject.lowestY)
                .firstOrNull { solid[index(subject, imageX, it, imageZ)] }
            if (top != null) {
                occupied++
                tallest = maxOf(tallest, top)
            }
        }
    }
    val standing = solid.count { it }
    val area = if (occupied == 0) "no rock in window" else "${occupied * 100 / columns}% of columns hold rock"
    val peak = if (tallest == Int.MIN_VALUE) "—" else tallest.toString()
    println("$name: $area, ${standing} solid blocks, tallest y=$peak")
    reportResistance(subject)
    println("  window ±${subject.radius} blocks, y ${subject.lowestY}..${subject.highestY}")
    println("  wrote ${output.absolutePath}/$name-view-{y,z,x}.png")
}

/**
 * The spread of the resistance noise, which every threshold in [Weathering] is judged against. Worth
 * printing because guessing it is how thresholds end up an order of magnitude out: values that look
 * decisive against an assumed ±0.1 do nothing against a real ±0.4.
 */
private fun reportResistance(subject: Subject) {
    if (subject.weathering === Weathering.NONE) return
    val samples = ArrayList<Double>()
    for (worldX in -subject.radius..<subject.radius step 4) {
        for (worldZ in -subject.radius..<subject.radius step 4) {
            samples += subject.weathering.resistanceAt(worldX, subject.weathering.keelY, worldZ)
        }
    }
    samples.sort()
    fun at(fraction: Double) = "%+.3f".format(samples[(samples.size * fraction).toInt().coerceAtMost(samples.size - 1)])
    println("  resistance: min ${at(0.0)}, p10 ${at(0.10)}, median ${at(0.50)}, p90 ${at(0.90)}, max ${at(0.999)}")
    println("  a threshold must sit inside that range to select anything")
}

private const val MAGNIFY = 2
private const val GRID_SPACING = 16
private const val ROCK = 0xF0F0F0
private const val EMPTY = 0x101418
private const val GRID = 0x1E2630
private const val LOWEST_SHADE = 70.0
private const val HIGHEST_SHADE = 255.0
