package co.voik.agesandtheart.worldgen

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.server.level.WorldGenRegion
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.NoiseColumn
import net.minecraft.world.level.StructureManager
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.blending.Blender
import java.util.Random
import java.util.concurrent.CompletableFuture
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Spire" terrain: sparse clusters of large floating islands over an infinite plasma sea.
 *
 * Each island is a wide, flattish, walkable **body** (the "centre of gravity") from which spires
 * grow — pointed **stalagmites** rising above and long, deep **stalactites** hanging below, some
 * plunging toward the sea. Everything is imperative and deterministic from [seed], so the shape is
 * easy to reason about and tune; there is no noise/density-function machinery.
 *
 * Registered via a codec ([CODEC]) because Fantasy persists Ages, which serialises the generator.
 * The shape constants will become symbol-driven later.
 */
class SpireChunkGenerator(private val biomes: BiomeSource, private val seed: Long) : ChunkGenerator(biomes) {

    /** A tapering cone: full [baseRadius] at [baseY], shrinking to a point at [tipY] (above or below). */
    private data class Spire(val baseX: Int, val baseZ: Int, val baseRadius: Double, val baseY: Int, val tipY: Int)

    private data class Island(
        val centerX: Int,
        val centerY: Int,
        val centerZ: Int,
        val bodyRadius: Double,
        val bodyHalfHeight: Double,
        val spires: List<Spire>,
    )

    override fun codec(): MapCodec<out ChunkGenerator> = CODEC

    override fun fillFromNoise(
        blender: Blender,
        randomState: RandomState,
        structureManager: StructureManager,
        chunk: ChunkAccess,
    ): CompletableFuture<ChunkAccess> {
        val chunkMinX = chunk.pos.minBlockX
        val chunkMinZ = chunk.pos.minBlockZ
        val islands = islandsAffecting(chunkMinX, chunkMinZ)
        val cursor = BlockPos.MutableBlockPos()

        for (localX in 0..<16) {
            for (localZ in 0..<16) {
                val worldX = chunkMinX + localX
                val worldZ = chunkMinZ + localZ

                // The plasma sea fills everything below sea level.
                for (y in MIN_Y..<SEA_LEVEL) {
                    chunk.setBlockState(cursor.set(worldX, y, worldZ), SEA_FLUID, false)
                }

                for (island in islands) {
                    solidifyColumn(chunk, cursor, worldX, worldZ, island)
                }
            }
        }
        return CompletableFuture.completedFuture(chunk)
    }

    /** Fills this column with rock wherever it falls inside the island's body or any of its spires. */
    private fun solidifyColumn(chunk: ChunkAccess, cursor: BlockPos.MutableBlockPos, worldX: Int, worldZ: Int, island: Island) {
        // Body: a wide, flattish lens you can walk on.
        val bodyDistance = horizontalDistance(worldX, worldZ, island.centerX, island.centerZ)
        if (bodyDistance <= island.bodyRadius) {
            val fraction = bodyDistance / island.bodyRadius
            val reach = island.bodyHalfHeight * sqrt(1.0 - fraction * fraction)
            fillColumn(chunk, cursor, worldX, worldZ, island.centerY - reach, island.centerY + reach)
        }

        // Spires: each a tapering cone, solid from its base toward its point.
        for (spire in island.spires) {
            val spireDistance = horizontalDistance(worldX, worldZ, spire.baseX, spire.baseZ)
            if (spireDistance > spire.baseRadius) continue
            val length = abs(spire.tipY - spire.baseY).toDouble()
            val reachFromBase = length * (1.0 - spireDistance / spire.baseRadius)
            if (spire.tipY >= spire.baseY) {
                fillColumn(chunk, cursor, worldX, worldZ, spire.baseY.toDouble(), spire.baseY + reachFromBase)
            } else {
                fillColumn(chunk, cursor, worldX, worldZ, spire.baseY - reachFromBase, spire.baseY.toDouble())
            }
        }
    }

    private fun fillColumn(chunk: ChunkAccess, cursor: BlockPos.MutableBlockPos, worldX: Int, worldZ: Int, yLow: Double, yHigh: Double) {
        val bottom = max(ceil(yLow).toInt(), MIN_Y)
        val top = min(floor(yHigh).toInt(), TOP_Y - 1)
        for (y in bottom..top) {
            chunk.setBlockState(cursor.set(worldX, y, worldZ), rockAt(worldX, y, worldZ), false)
        }
    }

    private fun horizontalDistance(x1: Int, z1: Int, x2: Int, z2: Int): Double {
        val deltaX = (x1 - x2).toDouble()
        val deltaZ = (z1 - z2).toDouble()
        return sqrt(deltaX * deltaX + deltaZ * deltaZ)
    }

    /** Islands from every cluster cell whose reach could overlap this chunk. */
    private fun islandsAffecting(chunkMinX: Int, chunkMinZ: Int): List<Island> {
        val minCellX = Math.floorDiv(chunkMinX - HORIZONTAL_REACH, CLUSTER_SPACING)
        val maxCellX = Math.floorDiv(chunkMinX + 15 + HORIZONTAL_REACH, CLUSTER_SPACING)
        val minCellZ = Math.floorDiv(chunkMinZ - HORIZONTAL_REACH, CLUSTER_SPACING)
        val maxCellZ = Math.floorDiv(chunkMinZ + 15 + HORIZONTAL_REACH, CLUSTER_SPACING)
        val islands = ArrayList<Island>()
        for (cellX in minCellX..maxCellX) {
            for (cellZ in minCellZ..maxCellZ) {
                islands += clusterIslands(cellX, cellZ)
            }
        }
        return islands
    }

    /** The islands of one cluster cell, placed deterministically from the world seed. */
    private fun clusterIslands(cellX: Int, cellZ: Int): List<Island> {
        val random = Random(seed xor (cellX * 341873128712L) xor (cellZ * 132897987541L))
        val isOriginCell = cellX == 0 && cellZ == 0
        val clusterCenterX = cellX * CLUSTER_SPACING + CLUSTER_SPACING / 2 + random.nextInt(-CLUSTER_JITTER, CLUSTER_JITTER)
        val clusterCenterZ = cellZ * CLUSTER_SPACING + CLUSTER_SPACING / 2 + random.nextInt(-CLUSTER_JITTER, CLUSTER_JITTER)
        val islandCount = random.nextInt(MIN_ISLANDS_PER_CLUSTER, MAX_ISLANDS_PER_CLUSTER + 1)

        return (0..<islandCount).map { index ->
            val bodyRadius = between(random, MIN_BODY_RADIUS, MAX_BODY_RADIUS)
            val bodyHalfHeight = between(random, MIN_BODY_HALF_HEIGHT, MAX_BODY_HALF_HEIGHT)

            // Guarantee an island at the origin so /age tp lands the player on solid ground.
            val centerX: Int
            val centerZ: Int
            val centerY: Int
            if (isOriginCell && index == 0) {
                centerX = 0
                centerZ = 0
                centerY = SPAWN_ISLAND_CENTER_Y
            } else {
                centerX = clusterCenterX + random.nextInt(-ISLAND_SPREAD, ISLAND_SPREAD)
                centerZ = clusterCenterZ + random.nextInt(-ISLAND_SPREAD, ISLAND_SPREAD)
                centerY = random.nextInt(MIN_ISLAND_CENTER_Y, MAX_ISLAND_CENTER_Y)
            }

            val spires = ArrayList<Spire>()
            val upBaseY = (centerY + bodyHalfHeight * SPIRE_ANCHOR_FRACTION).toInt()
            repeat(random.nextInt(MIN_SPIRES, MAX_SPIRES + 1)) {
                val (baseX, baseZ) = footprint(random, centerX, centerZ, bodyRadius * UP_FOOTPRINT_FRACTION)
                val length = between(random, MIN_UP_SPIRE_LENGTH, MAX_UP_SPIRE_LENGTH).toInt()
                spires += Spire(baseX, baseZ, between(random, MIN_UP_SPIRE_RADIUS, MAX_UP_SPIRE_RADIUS), upBaseY, min(upBaseY + length, TOP_Y - 2))
            }
            val downBaseY = (centerY - bodyHalfHeight * SPIRE_ANCHOR_FRACTION).toInt()
            repeat(random.nextInt(MIN_SPIRES, MAX_SPIRES + 1)) {
                val (baseX, baseZ) = footprint(random, centerX, centerZ, bodyRadius * DOWN_FOOTPRINT_FRACTION)
                val length = between(random, MIN_DOWN_SPIRE_LENGTH, MAX_DOWN_SPIRE_LENGTH).toInt()
                spires += Spire(baseX, baseZ, between(random, MIN_DOWN_SPIRE_RADIUS, MAX_DOWN_SPIRE_RADIUS), downBaseY, max(downBaseY - length, MIN_Y + 2))
            }

            Island(centerX, centerY, centerZ, bodyRadius, bodyHalfHeight, spires)
        }
    }

    /** A random point within [maxRadius] of ([centerX], [centerZ]). */
    private fun footprint(random: Random, centerX: Int, centerZ: Int, maxRadius: Double): Pair<Int, Int> {
        val angle = random.nextDouble() * TWO_PI
        val distance = random.nextDouble() * maxRadius
        return (centerX + cos(angle) * distance).toInt() to (centerZ + sin(angle) * distance).toInt()
    }

    private fun between(random: Random, minValue: Double, maxValue: Double): Double =
        minValue + random.nextDouble() * (maxValue - minValue)

    /** A basalt-delta-ish mix, chosen deterministically per block position. */
    private fun rockAt(x: Int, y: Int, z: Int): BlockState {
        var hash = x * 374761393 + y * 668265263 + z * 1274126177
        hash = (hash xor (hash ushr 13)) * 1274126177
        return when (Math.floorMod(hash, 100)) {
            in 0..<62 -> BASALT
            in 62..<94 -> BLACKSTONE
            else -> GRAVEL
        }
    }

    // --- Purely descriptive overrides (no caves, surface rules, or mobs — fillFromNoise does it all) ---

    override fun applyCarvers(
        level: WorldGenRegion,
        seed: Long,
        randomState: RandomState,
        biomeManager: BiomeManager,
        structureManager: StructureManager,
        chunk: ChunkAccess,
        step: GenerationStep.Carving,
    ) = Unit

    override fun buildSurface(level: WorldGenRegion, structureManager: StructureManager, randomState: RandomState, chunk: ChunkAccess) = Unit

    override fun spawnOriginalMobs(level: WorldGenRegion) = Unit

    override fun addDebugScreenInfo(info: MutableList<String>, randomState: RandomState, pos: BlockPos) = Unit

    override fun getGenDepth(): Int = GEN_HEIGHT

    override fun getSeaLevel(): Int = SEA_LEVEL

    override fun getMinY(): Int = MIN_Y

    override fun getBaseHeight(x: Int, z: Int, type: Heightmap.Types, level: LevelHeightAccessor, randomState: RandomState): Int = SEA_LEVEL

    override fun getBaseColumn(x: Int, z: Int, level: LevelHeightAccessor, randomState: RandomState): NoiseColumn {
        val column = Array(GEN_HEIGHT) { index -> if (MIN_Y + index < SEA_LEVEL) SEA_FLUID else AIR }
        return NoiseColumn(MIN_Y, column)
    }

    companion object {
        val CODEC: MapCodec<SpireChunkGenerator> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BiomeSource.CODEC.fieldOf("biome_source").forGetter { it.biomes },
                Codec.LONG.fieldOf("seed").forGetter { it.seed },
            ).apply(instance, ::SpireChunkGenerator)
        }

        private val TWO_PI = 2.0 * Math.PI

        // Vertical layout (matches the agesandtheart:age dimension type: min_y -64, height 384).
        private const val MIN_Y = -64
        private const val GEN_HEIGHT = 384
        private const val TOP_Y = MIN_Y + GEN_HEIGHT
        private const val SEA_LEVEL = 0

        // Clusters: sparse, since a single island is a couple hundred blocks wide.
        private const val CLUSTER_SPACING = 768
        private const val CLUSTER_JITTER = 150
        private const val ISLAND_SPREAD = 280 // island offset from cluster centre
        private const val HORIZONTAL_REACH = 560 // ~ jitter + spread + max body radius; for cell scanning
        private const val MIN_ISLANDS_PER_CLUSTER = 2
        private const val MAX_ISLANDS_PER_CLUSTER = 4

        // Body: wide and flattish so it's walkable; the "centre of gravity".
        private const val MIN_BODY_RADIUS = 80.0
        private const val MAX_BODY_RADIUS = 120.0
        private const val MIN_BODY_HALF_HEIGHT = 24.0
        private const val MAX_BODY_HALF_HEIGHT = 40.0
        private const val MIN_ISLAND_CENTER_Y = 100
        private const val MAX_ISLAND_CENTER_Y = 170
        private const val SPAWN_ISLAND_CENTER_Y = 150

        // Spires anchor near the top/bottom of the body, then taper to points.
        private const val SPIRE_ANCHOR_FRACTION = 0.4
        private const val MIN_SPIRES = 3
        private const val MAX_SPIRES = 6
        private const val UP_FOOTPRINT_FRACTION = 0.6
        private const val DOWN_FOOTPRINT_FRACTION = 0.75
        private const val MIN_UP_SPIRE_RADIUS = 10.0
        private const val MAX_UP_SPIRE_RADIUS = 24.0
        private const val MIN_UP_SPIRE_LENGTH = 40.0
        private const val MAX_UP_SPIRE_LENGTH = 90.0
        private const val MIN_DOWN_SPIRE_RADIUS = 12.0
        private const val MAX_DOWN_SPIRE_RADIUS = 30.0
        private const val MIN_DOWN_SPIRE_LENGTH = 80.0
        private const val MAX_DOWN_SPIRE_LENGTH = 170.0

        private val SEA_FLUID: BlockState = Blocks.WATER.defaultBlockState()
        private val AIR: BlockState = Blocks.AIR.defaultBlockState()
        private val BASALT: BlockState = Blocks.BASALT.defaultBlockState()
        private val BLACKSTONE: BlockState = Blocks.BLACKSTONE.defaultBlockState()
        private val GRAVEL: BlockState = Blocks.GRAVEL.defaultBlockState()
    }
}
