package co.voik.agesandtheart.preview

import co.voik.agesandtheart.MinecraftRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.biome.MultiNoiseBiomeSource
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists
import net.minecraft.world.level.NoiseColumn
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.RandomState
import kotlin.random.Random

/**
 * What a raised sea does to vanilla's overworld land: how much of it stays above water at each raise, and where the
 * abyss line has to stand under that sea for the abyss to come out a given depth on average.
 *
 * Reads the ground the way the generator does on vanilla's rock — the top of the rock, which `OCEAN_FLOOR_WG` stands
 * on — since a column is abyss there where that floor lies under the line. Raising the sea moves no rock, so one sample serves
 * every raise.
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
    val generator = NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(preset), settings)
    val noise = settings.value().noiseSettings()
    val world = LevelHeightAccessor.create(noise.minY(), noise.height())
    val vanillasSurface = settings.value().seaLevel() - 1

    val tops = buildList {
        for (index in 0..<seedCount) {
            val seed = FIRST_SEED + index
            val randomState = RandomState.create(worldgen.lookupOrThrow(Registries.NOISE), seed, settings.value())
            val scatter = Random(seed)
            repeat(columnCount) {
                val x = scatter.nextInt(-reach, reach)
                val z = scatter.nextInt(-reach, reach)
                add(topOfTheRock(generator.getBaseColumn(x, z, world, randomState), world))
            }
        }
    }

    val vanillasOcean = tops.filter { it < vanillasSurface }
    println("Vanilla's overworld under a raised sea — ${tops.size} columns over $seedCount seeds, ±$reach blocks")
    println("Its own surface is y=$vanillasSurface, and ${percent(vanillasOcean.size, tops.size)} of it is sea there.")
    println()
    println("LAND: the share of columns whose rock still stands above a sea raised by so much.")
    for (raise in RAISES) {
        println("  +%-3d  land %s".format(raise, percent(tops.count { it >= vanillasSurface + raise }, tops.size)))
    }
    println()
    println("THE ABYSS under a line at each height, which raising the sea does not move: how much of the world it lies")
    println("under, how deep it is on average over vanilla's own ocean (MEAN, shallower parts counting nothing), how deep")
    println("where it is (WHERE), and how much of it a vent, wanting $VENT_WANTS blocks, can stand in.")
    for (line in vanillasSurface + LINES_ABOVE downTo vanillasSurface - LINES_BELOW step LINE_STEP) {
        val under = tops.filter { it < line }.map { line - it }
        if (under.isEmpty()) continue
        val overTheOcean = vanillasOcean.sumOf { (line - it).coerceAtLeast(0) }.toDouble() / vanillasOcean.size
        println(
            "  y=%3d  under %s of the world   mean %5.1f   where %5.1f   vent-deep %s".format(
                line,
                percent(under.size, tops.size),
                overTheOcean,
                under.average(),
                percent(under.count { it >= VENT_WANTS }, under.size),
            ),
        )
    }

}

/** The highest block that blocks motion — what `OCEAN_FLOOR_WG` stands on. `isSolid` is what is left of `blocksMotion`. */
@Suppress("DEPRECATION")
private fun topOfTheRock(column: NoiseColumn, world: LevelHeightAccessor): Int =
    (world.maxY downTo world.minY).firstOrNull { column.getBlock(it).isSolid } ?: world.minY

private fun percent(part: Int, whole: Int): String = "%5.1f%%".format(part * PER_CENT / whole)

private fun Array<String>.intOption(name: String, fallback: Int): Int =
    firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')?.toIntOrNull() ?: fallback

private const val DEFAULT_SEEDS = 8
private const val DEFAULT_COLUMNS = 10_000
private const val DEFAULT_REACH = 20_000
private const val FIRST_SEED = 1000L
private const val PER_CENT = 100.0
private val RAISES = (0..96 step 4).toList()
private const val LINE_STEP = 2
private const val LINES_ABOVE = 24
private const val LINES_BELOW = 16

/** The water a vent needs over the rock: its foot, its shortest chimney and the clearance over the mouth. */
private const val VENT_WANTS = 12
