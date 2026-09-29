package co.voik.agesandtheart.preview

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.age.aspect.Options
import co.voik.agesandtheart.content.DeepWater
import co.voik.agesandtheart.age.aspect.Terrain
import co.voik.agesandtheart.age.aspect.Underground
import co.voik.agesandtheart.worldgen.VerticalWindow
import net.minecraft.core.QuartPos
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.NoiseColumn
import net.minecraft.world.level.biome.MultiNoiseBiomeSource
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.RandomState
import kotlin.random.Random

/**
 * How deep vanilla's water actually gets, measured rather than guessed.
 *
 * **The number this exists to settle is `DeepWater.DEEPEST_VANILLA_SEA`** — how much water has to stand
 * over deep water for it to hold. Eighty was a guess.
 *
 * **It measures the same quantity `DeepWater.columnFrom` walks**, which is the whole point and is not what
 * the first version of this measured. That asked `getBaseHeight(OCEAN_FLOOR_WG)` and called
 * `seaLevel - floor` the water depth — but that heightmap answers with the highest **motion-blocking**
 * block, so a narrow noise-cave shaft in a forest came back as ninety-three blocks of "ocean". The
 * deepest columns it reported were in jungle, savanna and snowy plains, which is what gave it away.
 *
 * So this reads the **actual block column** and counts the unbroken run of fluid down from sea level.
 * That is dearer — the whole column rather than a walk that stops at the first solid block — and it is the
 * only version of the question worth asking, because it sees caves and aquifers exactly as the rule does.
 *
 * **Columns are scattered rather than gridded**: a grid at a fixed stride can beat against the noise's own
 * periodicity and report a distribution that is an artefact of the stride.
 */
fun main(args: Array<String>) {
    val seedCount = args.intOption("seeds", DEFAULT_SEEDS)
    val columnCount = args.intOption("columns", DEFAULT_COLUMNS)
    val reach = args.intOption("reach", DEFAULT_REACH)

    MinecraftRegistries.ensureStoodUp()
    val worldgen = MinecraftRegistries.worldgen
    val settings = worldgen.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD)
    val preset = worldgen.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
        .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)
    val biomes = MultiNoiseBiomeSource.createFromPreset(preset)
    val generator = NoiseBasedChunkGenerator(biomes, settings)

    val seaLevel = settings.value().seaLevel()
    val noise = settings.value().noiseSettings()
    val world = LevelHeightAccessor.create(noise.minY(), noise.height())

    args.firstOrNull { it.startsWith("--dump=") }?.let { spelled ->
        val (s, x, z) = spelled.substringAfter('=').split(':')
        val randomState = RandomState.create(worldgen.lookupOrThrow(Registries.NOISE), s.toLong(), overworldSettings())
        val column = generator.getBaseColumn(x.toInt(), z.toInt(), world, randomState)
        println("Column at ($x, $z) on seed $s, y=100 down to y=-30:")
        for (y in 100 downTo -30) {
            val state = column.getBlock(y)
            println("  y=%4d  %-28s fluid=%s".format(y, state.block.descriptionId, state.fluidState.type))
        }
        return
    }

    println("Vanilla water columns — $seedCount seeds, $columnCount columns each, scattered over ±$reach blocks")
    println("Sea level is y=$seaLevel. Depth is the UNBROKEN run of fluid downward from there — the same")
    println("span DeepWater.columnFrom walks, read off the real blocks rather than off a heightmap.")
    println()

    val openSea = mutableListOf<Int>()
    val enclosed = mutableListOf<Int>()
    var everyColumn = 0
    val deepest = mutableListOf<Sounding>()

    for (index in 0..<seedCount) {
        val seed = FIRST_SEED + index
        val randomState = RandomState.create(worldgen.lookupOrThrow(Registries.NOISE), seed, overworldSettings())
        val resolver = biomes.createUncachedResolver(randomState)
        val scatter = Random(seed)
        var deepestHere: Sounding? = null
        var openHere = 0

        repeat(columnCount) {
            val x = scatter.nextInt(-reach, reach)
            val z = scatter.nextInt(-reach, reach)
            everyColumn++
            val column = generator.getBaseColumn(x, z, world, randomState)
            // **The sea's surface is `seaLevel - 1`.** `NoiseGeneratorSettings.seaLevel` is the level the
            // aquifer fills *below*, so the topmost water block is y=62 where the setting says 63 — asking
            // at 63 finds air in every ocean in the game, which is how the first run of this reported no
            // water anywhere at all.
            val surface = seaLevel - 1
            if (column.isDry(surface)) return@repeat

            val run = column.fluidRunDownFrom(surface, world.minY)
            // Open to the sky, or a pocket with rock over it — a cave shaft full of aquifer water is not an
            // ocean, and lumping the two together is what made the heightmap version nonsense.
            // `blocksMotion` is gone; what is left of it is `isSolid`, the cobweb and bamboo-sapling
            // exceptions it also carried having been dropped. Neither is rock over an ocean.
            val roofed = column.getBlock(seaLevel).isSolid
            if (roofed) {
                enclosed += run
            } else {
                openSea += run
                openHere++
            }

            if (!roofed && (deepestHere == null || run > deepestHere.depth)) {
                val biome = resolver.getNoiseBiome(
                    QuartPos.fromBlock(x),
                    QuartPos.fromBlock(seaLevel),
                    QuartPos.fromBlock(z),
                )
                val named = biome.unwrapKey().orElse(null)?.identifier()?.path ?: "?"
                deepestHere = Sounding(seed, x, z, run, named)
            }
        }

        deepestHere?.let { deepest += it }
        println(
            "  seed %-8d  %5.1f%% open sea   deepest %3d blocks at (%6d, %6d)  %s"
                .format(
                    seed,
                    openHere * PER_CENT / columnCount.toDouble(),
                    deepestHere?.depth ?: 0,
                    deepestHere?.x ?: 0,
                    deepestHere?.z ?: 0,
                    deepestHere?.biome ?: "—",
                ),
        )
    }

    val sorted = openSea.sorted()
    println()
    println("Across all seeds: ${sorted.size} open-sea columns and ${enclosed.size} roofed ones, of $everyColumn sampled")
    println()
    println("Open-sea depth distribution:")
    sorted.printBands()

    println()
    println("Percentiles of open-sea depth:")
    for (mark in PERCENTILES) println("  p%-7s %3d blocks".format(mark.toString(), sorted.percentile(mark)))
    println("  max      ${sorted.lastOrNull() ?: 0} blocks")

    if (enclosed.isNotEmpty()) {
        val roofed = enclosed.sorted()
        println()
        println("Roofed water (aquifers, flooded caves) — deep water's rule cannot tell these from a sea:")
        println("  p99 %d, p99.9 %d, max %d blocks".format(roofed.percentile(NEARLY_ALL), roofed.percentile(99.9), roofed.last()))
    }

    println()
    println("Deepest open sea per seed, deepest first:")
    for (sounding in deepest.sortedByDescending { it.depth }) {
        println("  %3d blocks  seed %-8d (%6d, %6d)  %s".format(sounding.depth, sounding.seed, sounding.x, sounding.z, sounding.biome))
    }

    println()
    println("For a candidate threshold T, the share of open-sea columns that would reach it:")
    for (candidate in CANDIDATES) {
        val over = sorted.count { it >= candidate }
        val share = if (sorted.isEmpty()) 0.0 else over * PER_CENT / sorted.size
        val roofedOver = enclosed.count { it >= candidate }
        println("  T=%3d  %6d open-sea columns (%.4f%%) and %d roofed ones would grow deep water".format(candidate, over, share, roofedOver))
    }

    surveyOurOwnSeas(args.intOption("ours", DEFAULT_OUR_COLUMNS), reach, RAISES)
}

/** Whether there is no fluid at [y] — land, or open air. */
private fun NoiseColumn.isDry(y: Int): Boolean = getBlock(y).fluidState.isEmpty

/** The unbroken run of fluid from [from] downward, stopping at the first block that holds none. */
private fun NoiseColumn.fluidRunDownFrom(from: Int, floor: Int): Int {
    var run = 0
    var y = from
    while (y >= floor && !isDry(y)) {
        run++
        y--
    }
    return run
}

private fun List<Int>.printBands() {
    if (isEmpty()) return
    for (band in 0..<BANDS) {
        val low = band * BAND_WIDTH
        val inBand = count { it in low..<(low + BAND_WIDTH) }
        if (inBand == 0) continue
        val share = inBand * PER_CENT / size
        println("  %3d–%3d  %-40s %6.3f%%".format(low, low + BAND_WIDTH - 1, "#".repeat(share.toInt().coerceAtMost(BAR_WIDTH)), share))
    }
    val over = count { it >= BANDS * BAND_WIDTH }
    if (over > 0) println("  %3d+     %-40s %6.3f%%".format(BANDS * BAND_WIDTH, "", over * PER_CENT / size))
}

/**
 * And the half of the question vanilla cannot answer: how deep are the seas in **our own** landforms?
 *
 * **This is the fence that actually matters.** An Age using `landmass=overworld` gets vanilla's oceans, so
 * the numbers above are a real constraint — but every other Age gets one of these, and an ordinary sea in
 * an Age nobody wrote as an abyss must not grow deep water.
 *
 * Read from the field tree, which is what `/age probe` reads and is a pure function: no server, no chunks,
 * no aquifers to confuse it.
 */
private fun surveyOurOwnSeas(columnCount: Int, reach: Int, raises: List<Int>) {
    println()
    println("═".repeat(RULE_WIDTH))
    println("Our own landforms — $columnCount columns each, at each raise `deep` can put on the waterline.")
    println("ABYSSAL is the share of ALL columns clearing ${DeepWater.DEEPEST_VANILLA_SEA} unbroken blocks — the material's reach.")
    println()

    for (terrain in Terrain.entries) {
        val waterline = terrain.waterline ?: continue
        val shape = runCatching {
            terrain.ground(Underground.NONE, Options.NONE, Options.NONE, VerticalWindow.DEFAULT, OUR_SALT).shape
        }.getOrElse {
            println("  %-16s could not be built offline: %s".format(terrain.key, it::class.simpleName))
            continue
        }

        for (raise in raises) {
            val line = waterline + raise
            val scatter = Random(OUR_SALT)
            val depths = mutableListOf<Int>()
            repeat(columnCount) {
                val x = scatter.nextInt(-reach, reach)
                val z = scatter.nextInt(-reach, reach)
                val spans = shape.columnSpans(x, z)
                if (spans.contains(line)) return@repeat
                val floorTop = spans.ranges.filter { it.last < line }.maxOfOrNull { it.last }
                    ?: VerticalWindow.DEFAULT.minY
                depths += line - floorTop
            }

            if (depths.isEmpty()) {
                println("  %-16s +%-3d (y=%4d)   no sea anywhere in the sample".format(terrain.key, raise, line))
                continue
            }
            val sorted = depths.sorted()
            // The one number that decides whether this pairing can grow the material at all.
            val abyssal = depths.count { it >= DeepWater.DEEPEST_VANILLA_SEA }
            println(
                "  %-16s +%-3d (y=%4d)  %5.1f%% wet  median %3d  p99 %3d  deepest %4d   ABYSSAL %6.3f%% of columns"
                    .format(
                        terrain.key,
                        raise,
                        line,
                        depths.size * PER_CENT / columnCount.toDouble(),
                        sorted.percentile(HALF),
                        sorted.percentile(NEARLY_ALL),
                        sorted.last(),
                        abyssal * PER_CENT / columnCount.toDouble(),
                    ),
            )
        }
    }

    println()
    println("A threshold below any 'deepest' above would grow an abyss in that landform's ordinary sea.")
}

private data class Sounding(val seed: Long, val x: Int, val z: Int, val depth: Int, val biome: String)

private fun List<Int>.percentile(mark: Double): Int {
    if (isEmpty()) return 0
    return this[((mark / PER_CENT) * (size - 1)).toInt().coerceIn(0, size - 1)]
}

private fun Array<String>.intOption(name: String, fallback: Int): Int =
    firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')?.toIntOrNull() ?: fallback

/** Enough seeds that one freak world cannot decide the answer. */
private const val DEFAULT_SEEDS = 12

private const val DEFAULT_COLUMNS = 20_000

/** Far enough out that a sample is not all one continent, and inside where anybody will ever play. */
private const val DEFAULT_REACH = 20_000

private const val FIRST_SEED = 1000L

private const val BAND_WIDTH = 10

private const val BANDS = 12

private const val BAR_WIDTH = 40

private const val PER_CENT = 100.0

private val PERCENTILES = listOf(50.0, 90.0, 99.0, 99.9, 99.99)

/** The thresholds worth pricing, so the choice is made against shares rather than against one maximum. */
private val CANDIDATES = listOf(48, 56, 64, 72, 80, 96, 112, 128)

private const val DEFAULT_OUR_COLUMNS = 20_000

private const val OUR_SALT = 0L

private const val HALF = 50.0

private const val NEARLY_ALL = 99.0

private const val RULE_WIDTH = 78

/** What `deep` puts on a waterline: nothing, its lowest draw, its middle and its highest. */
private val RAISES = listOf(0, 40, 61, 82)

/** Vanilla's overworld noise settings, resolved — `RandomState.create` takes the value, not the key. */
private fun overworldSettings(): NoiseGeneratorSettings =
    MinecraftRegistries.worldgen.lookupOrThrow(Registries.NOISE_SETTINGS)
        .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value()
