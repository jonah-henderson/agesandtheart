package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.cellHash
import co.voik.agesandtheart.worldgen.field.fieldNoise
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SnowLayerBlock
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Wind-cut ridges of snow lying along the wind (Antarctica's sastrugi): each steep at its upwind prow and tapering
 * away downwind, a few to a dozen blocks long, laid in snow layers on whatever snow lies in the open, and higher the
 * harder the wind blows.
 *
 * Laid out as [co.voik.agesandtheart.worldgen.field.Yardangs] are, much smaller: a lattice turned to the wind, each
 * row slid along by its own amount and bent a little, a cell holding at most one ridge inside it.
 */
class Sastrugi(seed: Long, bearingDegrees: Double, windStrength: Double) {
    private val alongX = cos(Math.toRadians(bearingDegrees))
    private val alongZ = sin(Math.toRadians(bearingDegrees))
    private val salt = (seed xor (seed ushr Int.SIZE_BITS)).toInt()
    private val noise = fieldNoise(seed, 0, NOISE_AMPLITUDES)
    private val tallest = MOST_LAYERS * windStrength

    /** How many snow layers the wind has heaped on this column, nought between the ridges. */
    fun layersAt(worldX: Int, worldZ: Int): Int {
        val x = worldX + HALF_BLOCK
        val z = worldZ + HALF_BLOCK
        val along = x * alongX + z * alongZ
        val across = -x * alongZ + z * alongX
        val warp = noise.get(along / CELL_LENGTH * WARP_FREQUENCY, 0.0, across / CELL_WIDTH * WARP_FREQUENCY).toDouble()
        val bent = across + warp * CELL_WIDTH * WARP_REACH
        val row = floor(bent / CELL_WIDTH).toInt()
        val rowSlide = unit(row, 0, ROW_SLIDE) * CELL_LENGTH
        val column = floor((along - rowSlide) / CELL_LENGTH).toInt()
        if (unit(column, row, PRESENT) > SHARE_WITH_A_RIDGE) return 0

        val length = CELL_LENGTH * lerp(SHORTEST, LONGEST, unit(column, row, LENGTH))
        val halfWidth = CELL_WIDTH * lerp(NARROWEST, WIDEST, unit(column, row, WIDTH))
        val prowAt = rowSlide + column * CELL_LENGTH + (CELL_LENGTH - length) * unit(column, row, PROW)
        val centre = (row + 0.5) * CELL_WIDTH + (unit(column, row, CENTRE) * 2 - 1) * (CELL_WIDTH / 2 - halfWidth)
        val t = (along - prowAt) / length
        if (t < 0.0 || t >= 1.0) return 0
        val widthHere = halfWidth * widthProfile(t)
        val outward = if (widthHere > 0.0) abs(bent - centre) / widthHere else 1.0
        if (outward >= 1.0) return 0
        val height = tallest * lerp(LOWEST, 1.0, unit(column, row, HEIGHT))
        return (height * crestProfile(t) * sqrt(1.0 - outward.pow(FLANK_STEEPNESS))).roundToInt()
    }

    /** Heaps the ridges on the snow lying in the open across [chunk]: on a snow layer, or on a snow block. */
    fun cutInto(chunk: ChunkAccess) {
        val position = BlockPos.MutableBlockPos()
        val chunkX = chunk.pos.minBlockX
        val chunkZ = chunk.pos.minBlockZ
        for (column in 0..<CHUNK_COLUMNS) {
            val localX = column % CHUNK_WIDTH
            val localZ = column / CHUNK_WIDTH
            val heaped = layersAt(chunkX + localX, chunkZ + localZ)
            if (heaped <= 0) continue
            val topY = topOf(chunk, position.set(localX, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ), localZ))
            val top = chunk.getBlockState(position.set(localX, topY, localZ))
            val (baseY, lying) = when {
                top.`is`(Blocks.SNOW) -> topY to top.getValue(SnowLayerBlock.LAYERS)
                top.`is`(Blocks.SNOW_BLOCK) -> topY + 1 to 0
                else -> continue
            }
            heapOnto(chunk, position.set(localX, baseY, localZ), lying + heaped)
        }
    }

    /**
     * The topmost block of the column from [above] up. The heightmap was primed before decoration and is not kept as
     * it runs, so it stands under the snow the features laid.
     */
    private fun topOf(chunk: ChunkAccess, above: BlockPos.MutableBlockPos): Int {
        repeat(MOST_STEPS_UP) {
            if (chunk.getBlockState(above).isAir) return above.y - 1
            above.move(0, 1, 0)
        }
        return above.y - 1
    }

    /** [layers] of snow from [base] up: whole blocks while they fill one, and a layer of what is left on top. */
    private fun heapOnto(chunk: ChunkAccess, base: BlockPos.MutableBlockPos, layers: Int) {
        val wholeBlocks = (layers - 1) / LAYERS_IN_A_BLOCK
        repeat(wholeBlocks) {
            chunk.setBlockState(base, Blocks.SNOW_BLOCK.defaultBlockState())
            base.move(0, 1, 0)
        }
        val left = layers - wholeBlocks * LAYERS_IN_A_BLOCK
        chunk.setBlockState(base, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, left))
    }

    /** A number in 0..1 for a cell, separated by [use]. */
    private fun unit(column: Int, row: Int, use: Int): Double = cellHash(column, row, salt xor use) + 0.5

    private companion object {
        /** Rounded over the first [PROW_SHARE] of the length, then narrowing to a point downwind. */
        fun widthProfile(t: Double): Double =
            if (t < PROW_SHARE) sqrt(t / PROW_SHARE) else (1.0 - (t - PROW_SHARE) / (1.0 - PROW_SHARE)).pow(TAPER)

        /** A steep face upwind, then a crest falling away towards the tail. */
        fun crestProfile(t: Double): Double = minOf(1.0, sqrt(t / FACE_SHARE)) * (1.0 - CREST_FALL * t)

        fun lerp(from: Double, to: Double, share: Double) = from + (to - from) * share

        /** At the hardest wind, a ridge stands a block and three quarters over the snow it lies on. */
        const val MOST_LAYERS = 14.0
        const val LAYERS_IN_A_BLOCK = 8
        /** Past a tree's height, whatever the column holds it is not open snow. */
        const val MOST_STEPS_UP = 40

        const val CELL_LENGTH = 16.0
        const val CELL_WIDTH = 6.0
        const val SHARE_WITH_A_RIDGE = 0.85
        const val SHORTEST = 0.4
        const val LONGEST = 0.9
        const val NARROWEST = 0.2
        const val WIDEST = 0.4
        const val LOWEST = 0.35
        const val PROW_SHARE = 0.2
        const val TAPER = 0.9
        const val FACE_SHARE = 0.1
        const val CREST_FALL = 0.75
        const val FLANK_STEEPNESS = 3.0

        /** The rows meander a little along the wind, in cycles per cell, and by up to half a row. */
        const val WARP_FREQUENCY = 0.15
        const val WARP_REACH = 0.5

        const val HALF_BLOCK = 0.5
        const val CHUNK_WIDTH = 16
        const val CHUNK_COLUMNS = CHUNK_WIDTH * CHUNK_WIDTH
        val NOISE_AMPLITUDES = listOf(1.0)

        const val ROW_SLIDE = 0x524F57
        const val PRESENT = 0x50524553
        const val LENGTH = 0x4C454E47
        const val WIDTH = 0x57494454
        const val HEIGHT = 0x48454947
        const val PROW = 0x50524F57
        const val CENTRE = 0x43454E54
    }
}
