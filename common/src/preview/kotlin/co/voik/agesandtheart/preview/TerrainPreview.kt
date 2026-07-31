package co.voik.agesandtheart.preview

import co.voik.agesandtheart.sky.SpireSky
import co.voik.agesandtheart.worldgen.CavernField
import co.voik.agesandtheart.worldgen.ErodedField
import co.voik.agesandtheart.worldgen.NoiseField
import co.voik.agesandtheart.worldgen.PillarField
import co.voik.agesandtheart.worldgen.ShapesField
import co.voik.agesandtheart.worldgen.SpireField
import co.voik.agesandtheart.age.Seam
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.worldgen.carver.Weathering
import co.voik.agesandtheart.worldgen.field.Fault
import co.voik.agesandtheart.worldgen.field.Raised
import co.voik.agesandtheart.worldgen.field.RegionMap
import co.voik.agesandtheart.worldgen.field.Regions
import co.voik.agesandtheart.worldgen.field.Ridge
import co.voik.agesandtheart.worldgen.field.Rift
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Weathered
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Draws an Age's terrain *shape* to PNGs, offline, in about a second. Possible because field evaluation
 * and [Weathering] reach only for noise and plain maths — so it can show **nothing material**: palettes,
 * surface rules and water all live behind the registries. A geometry instrument, and only that.
 *
 * It renders from the **same** [TerrainField] and [Weathering] objects that generate the world, so a
 * preview cannot quietly disagree with the game.
 *
 * Three orthogonal views named for the axis you look down: `view-y.png` from above shaded by height, and
 * `view-z.png`/`view-x.png` as slices. **The slices are the point** — a surface height cannot tell a
 * sheer wall from a gentle slope, and the pair reveals anisotropy in a wind-stretched world.
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
    /**
     * How far up the world this subject floats — see `Terrain.ALTITUDE`. Carried here rather than baked
     * into [field] because **a lift has to reach the weathering too**: erosion's keel and band are absolute
     * heights, so a shape raised without its wind sails over the band and comes out unweathered.
     */
    val lift: Int = 0,
) {
    /**
     * The field as it will actually generate. Built through `Weathered.spire`, **the same factory the
     * generator uses**, so there is no second set of numbers to drift.
     */
    fun weathered(): TerrainField {
        val raised = if (lift == 0) field else Raised(field, lift)
        return if (weathering === Weathering.NONE) raised else Weathered.spire(raised, lift)
    }
}

/**
 * The two shapes the divided subjects share, so a fault and a rift read against the same `regions`.
 * Declared **above** [subjects]: top-level properties initialise in file order, and one read from above
 * its declaration is null.
 */
private val dividedTerrains = listOf(NoiseField.hills(), PillarField.world())

/**
 * And one landscape either side, for the three seam-form subjects.
 *
 * The same terrain twice on purpose: it makes the boundary invisible until a form is applied, so each picture
 * shows the form and nothing else. Two *different* shapes already meet at a cliff of their own.
 */
private val twoOfOneTerrain = listOf(NoiseField.hills(), NoiseField.hills())

/**
 * The one territory map they read, since a seam has to be the same seam in every picture.
 *
 * A knife edge, which is what every form but [Seam.FUZZED] draws — see [fuzzedTerritories] for the third.
 */
private val territories = RegionMap(
    members = 2,
    scale = 400.0,
    blend = Seam.SCARP.blendBlocks(400),
    originX = 0,
    originZ = 0,
    seed = 0x4E6109L,
)

/**
 * The same territories fuzzed — the rare form, and the only one that is a *width* rather than a
 * displacement, so the only one visible in the map instead of a node over the shape.
 *
 * **16 blocks wide**, which is worth knowing before reading the render: a band this narrow is a detail of
 * a boundary rather than something you can see from above. See [Seam.WIDEST_FUZZ_BLOCKS].
 */
private val fuzzedTerritories = territories.copy(blend = Seam.FUZZED.blendBlocks(400))

private val subjects: Map<String, Subject> = mapOf(
    // Wide enough to hold more than one island, because size and lift variation is a thing you can only
    // see by comparing copies; and tall enough to reach the world ceiling, so a spire that runs into it
    // reads as a clipped flat top rather than as the window's edge.
    //
    // **The floor followed the deck down on 2026-07-29 and must keep following it.** It was 100, which was
    // comfortably below an island when the deck sat at y=190; once the deck dropped to 148 to buy the 2:1
    // split, the hanging spires reached past it and the readout started reporting the *window's* edge as the
    // rock's. A measurement that silently clips is worse than none — 56 sits just under the sea at 63, which
    // is as low as an island is ever allowed to hang.
    // **The window follows the Age's own vertical band**, which for the Spire is `VerticalWindow.LIFTED` —
    // y 0..383 rather than -64..319 — because the recipe pins `altitude=high`. Keeping 320 here would have
    // reported the window's edge as the rock's, the same silent clip the note above warns about.
    "spire" to Subject(
        SpireField.world(), Weathering.SPIRE, lowestY = 56, highestY = 383, radius = 300,
        lift = Terrain.HIGH_ALTITUDE_LIFT,
    ),
    // The same islands with weathering switched off — the pair shows what erosion is actually contributing.
    "spire-nowind" to Subject(
        SpireField.world(), Weathering.NONE, lowestY = 56, highestY = 383, radius = 300,
        lift = Terrain.HIGH_ALTITUDE_LIFT,
    ),
    "hills" to Subject(NoiseField.hills(), Weathering.NONE, lowestY = 20, highestY = 120),
    "pillars" to Subject(PillarField.world(), Weathering.NONE, lowestY = 30, highestY = 185),
    "shapes" to Subject(ShapesField.world(), Weathering.NONE, lowestY = 55, highestY = 130, radius = 200),
    "caverns" to Subject(CavernField.world(), Weathering.NONE, lowestY = -64, highestY = 110),
    // The tunnels on their own. A cave system reads far better as a solid lattice hanging in space than
    // as absence inside a hill, and the slices are where the network's connectedness actually shows.
    "caverns-voids" to Subject(CavernField.caves(), Weathering.NONE, lowestY = -64, highestY = 70),
    "eroded" to Subject(ErodedField.world(), Weathering.NONE, lowestY = 30, highestY = 195, radius = 200),

    // Two terrains sharing a world. The top-down view is the one to read: it shows the territories and
    // what the seam does to whatever it cuts through. Region size here is the default one, so this is
    // what an Age written in a default world looks like.
    "regions" to Subject(
        Regions(members = dividedTerrains, map = territories),
        Weathering.NONE,
        lowestY = 30,
        highestY = 185,
        radius = 420,
    ),

    // **`fault`, `rift` and `fuzz` are the three forms a seam can take, and they are a set to read together.**
    // Each is `Seam.SCARP`, `Seam.RIFT` or `Seam.FUZZED` applied to the SAME two territories, so the pictures
    // differ by nothing but the form. Whether a scarp reads as drama or as breakage is the entire acceptance
    // test for Phase 4.5 step 9, and these three plus `hills` are what it is read from.

    // A scarp: `Seam.SCARP`. **Read against `hills` above**, and note that both territories are the same
    // terrain — the point of the picture rather than laziness. The first version divided hills from pillars,
    // as `regions` does, and showed nothing: those two already stand about ninety blocks apart, so a throw of
    // thirty-two disappeared into a step that was there anyway (design §3.4's "terrain-vs-terrain faults
    // already happen", met from the wrong end). With one landscape either side the seam is invisible without
    // a throw and a clean sixty-four-block cliff with one, so the picture shows the node and nothing else.
    //
    // The window opens by the throw at both ends, so a thrown territory cannot be clipped by the *picture* and
    // read as clipped by the world — the silent-clip trap `spire`'s floor note warns about.
    "fault" to Subject(
        Fault(
            base = Regions(members = twoOfOneTerrain, map = territories),
            map = territories,
            // The same helper the generator uses, so the picture cannot disagree about which side rises.
            throws = Fault.alternatingThrows(members = 2, throwBlocks = Terrain.SCARP_THROW, seed = 1L),
        ),
        Weathering.NONE,
        lowestY = 20 - Terrain.SCARP_THROW,
        highestY = 120 + Terrain.SCARP_THROW,
        radius = 420,
    ),

    // A rift: `Seam.RIFT`. The top-down view shows how wide the band comes out and how far it runs; the slices
    // show it as a chasm rather than as a stripe of missing map. Two shapes here rather than one, since a
    // chasm cutting through both is what a written Age will usually look like.
    //
    // **`FaultCheck` prints the band's width in blocks**, which is the number to tune
    // `Rift.DEFAULT_HALF_WIDTH` against — the seam distance it is measured in is proportionate rather than
    // surveyed, so what the constant means on the ground is something to read off, not to reason about.
    "wall" to Subject(
        Ridge.raised(
            base = Regions(members = twoOfOneTerrain, map = territories),
            map = territories,
            footingY = Terrain.WALL_FOOTING,
            crestY = Terrain.WALL_CREST,
        ),
        Weathering.NONE,
        lowestY = 30,
        highestY = 185,
        // The same window as `rift`, so the two forms can be read against each other.
        radius = 420,
    ),

    "rift" to Subject(
        Rift.opened(
            base = Regions(members = twoOfOneTerrain, map = territories),
            map = territories,
            floorY = Terrain.RIFT_FLOOR,
            rimY = Terrain.RIFT_RIM,
        ),
        Weathering.NONE,
        lowestY = 30,
        highestY = 185,
        radius = 420,
    ),

    // The fuzz: `Seam.FUZZED`, the rare form and the only one that is a *width*. There is no node — the whole
    // of it is in the map, so this is a plain `Regions` over a blended one, and what to look at is the band
    // where the two shapes dissolve into each other instead of meeting. Read it against `regions`, which is
    // the same pair knife-edged.
    //
    // **Two DIFFERENT shapes here, where `fault` needs one shape twice, and the asymmetry is the finding.**
    // Fuzzing one terrain against itself is invisible by construction: interlocking two identical shapes
    // column by column reproduces that shape exactly, and the first version of this subject came out as plain
    // hills. So the three forms are not quite peers — a scarp and a rift *make* geology and show up between
    // any two territories, while the fuzz only softens a boundary that was already there and does nothing
    // wherever the two sides happen to be similar. Worth knowing before reading "5% of Ages get a fuzzed
    // border" as though it always delivered as much as the other two.
    //
    // **Worth reading against `fault` in particular**, because the pair is the argument for the whole reshape:
    // these two used to be able to happen at once, and the combination threw the interlocking columns
    // alternately up and down into a picket fence of one-block spikes. A seam is one form or the other now.
    "fuzz" to Subject(
        Regions(members = dividedTerrains, map = fuzzedTerritories),
        Weathering.NONE,
        lowestY = 30,
        highestY = 185,
        radius = 420,
    ),
)

/**
 * Solidity for the whole window, resolved once. **Asks the very field generation asks** — applying the
 * weathering rule itself was a faithful mirror only while the two stayed identical, and once erosion
 * learned to spare a column by how thick its rock stands the preview showed a world nobody would generate.
 */
private fun solidity(subject: Subject): BooleanArray {
    val width = subject.radius * 2
    val height = subject.highestY - subject.lowestY + 1
    val solid = BooleanArray(width * width * height)
    val shape = subject.weathered()

    for (imageX in 0..<width) {
        val worldX = imageX - subject.radius
        for (imageZ in 0..<width) {
            val worldZ = imageZ - subject.radius
            val spans = shape.columnSpans(worldX, worldZ)
            if (spans.ranges.isEmpty()) continue
            for (worldY in subject.lowestY..subject.highestY) {
                if (!spans.contains(worldY)) continue
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
 * Looking down: the topmost solid block per column, shaded across **the range the rock actually
 * occupies** rather than the whole window — ranging over the window makes every surface the same
 * brightness and hides the height variation this is drawn to show.
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
    val tops = ArrayList<Int>()

    for (imageX in 0..<width) {
        for (imageZ in 0..<width) {
            val top = (subject.highestY downTo subject.lowestY)
                .firstOrNull { solid[index(subject, imageX, it, imageZ)] }
            if (top != null) tops += top
        }
    }
    val standing = solid.count { it }
    val area = if (tops.isEmpty()) "no rock in window" else "${tops.size * 100 / columns}% of columns hold rock"
    val peak = tops.maxOrNull()?.toString() ?: "—"
    println("$name: $area, ${standing} solid blocks, tallest y=$peak")
    reportTops(tops)
    reportResistance(subject)
    println("  window ±${subject.radius} blocks, y ${subject.lowestY}..${subject.highestY}")
    println("  wrote ${output.absolutePath}/$name-view-{y,z,x}.png")
}

/**
 * Where the rock's *surfaces* actually sit — a different question from how tall the tallest column is.
 * **Reading altitude off the single peak is misleading by a wide margin**: `PEAK_CEILING` said 296 while
 * most island tops were nowhere near it. A percentile spread plus a count of what breaks each cloud deck
 * is the readout that answers the brief.
 */
private fun reportTops(tops: List<Int>) {
    if (tops.isEmpty()) return
    val sorted = tops.sorted()
    fun at(fraction: Double) = sorted[(sorted.size * fraction).toInt().coerceAtMost(sorted.size - 1)]
    val overUpper = tops.count { it > UPPER_CLOUD_DECK }
    val overLower = tops.count { it > LOWER_CLOUD_DECK }
    println("  column tops: p10 ${at(0.10)}, median ${at(0.50)}, p90 ${at(0.90)}, p99 ${at(0.99)}")
    println(
        "  above the lower cloud deck ($LOWER_CLOUD_DECK): ${overLower * 100 / tops.size}%" +
            ", above the upper ($UPPER_CLOUD_DECK): ${overUpper * 100 / tops.size}%"
    )
}

// The Spire's own deck heights, read rather than copied: they used to be mirrored here because the only
// place they existed was a client-side renderer, and now they are data in `common` like the rest of its
// sky. Only ever read for the printed comparison above — nothing here generates against them.
private val UPPER_CLOUD_DECK = SpireSky.UPPER_DECK_HEIGHT
private val LOWER_CLOUD_DECK = SpireSky.LOWER_DECK_HEIGHT

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
