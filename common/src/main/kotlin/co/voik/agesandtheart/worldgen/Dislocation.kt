package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.fieldNoise
import co.voik.agesandtheart.worldgen.field.cellHash
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Where an Age's ground is read from somewhere else: patches whose terrain is the Age's own noise sampled
 * a long way off, which is as good as another seed and needs none.
 *
 * **One statement for both kinds of rock.** A field of ours asks [shiftAt] and reads its own shape at the
 * moved column ([co.voik.agesandtheart.worldgen.field.Dislocated]); vanilla's fill asks the same and reads
 * its density there ([DislocatedNoiseChunk]). The two cannot disagree about where a patch is.
 *
 * Pure in ([seed], [extent]), and monotonic in [extent]: a cell that is foreign at some extent stays foreign
 * at every larger one, with the same shift, so ground generated earlier is never contradicted by more.
 */
data class Dislocation(val seed: Long, val extent: Double) {
    val isNone: Boolean get() = extent <= 0.0

    private val coverage = extent.coerceIn(0.0, 1.0) * MOST_COVERED

    private val alongX by lazy { fieldNoise(seed xor EDGE_SALT, EDGE_FIRST_OCTAVE, EDGE_AMPLITUDES) }
    private val alongZ by lazy { fieldNoise((seed xor EDGE_SALT) + SECOND_AXIS, EDGE_FIRST_OCTAVE, EDGE_AMPLITUDES) }

    /** How far the column at ([worldX], [worldZ]) is read from, or [Shift.NONE] where the ground is its own. */
    fun shiftAt(worldX: Int, worldZ: Int): Shift {
        if (isNone) return Shift.NONE
        val edgeX = worldX / EDGE_WAVELENGTH
        val edgeZ = worldZ / EDGE_WAVELENGTH
        val cellX = floor((worldX + alongX.get(edgeX, 0.0, edgeZ) * EDGE_RAGGEDNESS) / CELL).toInt()
        val cellZ = floor((worldZ + alongZ.get(edgeX, 0.0, edgeZ) * EDGE_RAGGEDNESS) / CELL).toInt()
        val isForeign = cellHash(cellX, cellZ, saltOf(CLAIM_SALT)) + HALF < coverage
        if (!isForeign) return Shift.NONE
        return Shift.of(
            blocksFrom(cellHash(cellX, cellZ, saltOf(ALONG_X_SALT))),
            blocksFrom(cellHash(cellX, cellZ, saltOf(ALONG_Z_SALT))),
        )
    }

    /** Whether any column of the chunk whose corner is ([minBlockX], [minBlockZ]) is read from elsewhere. */
    fun touchesChunkAt(minBlockX: Int, minBlockZ: Int): Boolean {
        if (isNone) return false
        return (0..<CHUNK_WIDTH).any { x -> (0..<CHUNK_WIDTH).any { z -> !shiftAt(minBlockX + x, minBlockZ + z).isNone } }
    }

    /**
     * A distance of at least [LEAST_SHIFT] either way, in whole chunks: a shifted chunk is a whole chunk of
     * the noise, so vanilla's interpolation grid stays where it was drawn.
     */
    private fun blocksFrom(hash: Double): Int {
        val sign = if (hash < 0.0) -1 else 1
        val chunks = ((LEAST_SHIFT + (hash + HALF) * SHIFT_RANGE) / CHUNK_WIDTH).roundToInt()
        return sign * chunks * CHUNK_WIDTH
    }

    private fun saltOf(purpose: Int) = (seed xor (seed ushr SALT_FOLD)).toInt() + purpose

    /** A column's displacement, packed so that asking for one allocates nothing. */
    @JvmInline
    value class Shift private constructor(private val packed: Long) {
        val isNone: Boolean get() = this == NONE
        val x: Int get() = (packed shr HALF_A_LONG).toInt()
        val z: Int get() = packed.toInt()

        companion object {
            /** Never produced by [of]: a shift is at least a chunk either way on both axes. */
            val NONE = Shift(0L)

            fun of(x: Int, z: Int) = Shift((x.toLong() shl HALF_A_LONG) or (z.toLong() and LOW_HALF))
        }
    }

    companion object {
        val CODEC: Codec<Dislocation> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.LONG.fieldOf("seed").forGetter(Dislocation::seed),
                Codec.DOUBLE.fieldOf("extent").forGetter(Dislocation::extent),
            ).apply(instance, ::Dislocation)
        }

        val NONE = Dislocation(0L, 0.0)

        /** The share of the ground that is foreign at full extent. */
        private const val MOST_COVERED = 0.4

        /** Patches are a cell across, before their edges are made ragged. */
        private const val CELL = 192.0

        // The edge is wandered by two noises, so a patch is a lobed shape rather than a square of the grid.
        private const val EDGE_WAVELENGTH = 24.0
        private const val EDGE_FIRST_OCTAVE = -3
        private val EDGE_AMPLITUDES = listOf(1.0, 0.5)
        private const val EDGE_RAGGEDNESS = 56.0
        private const val EDGE_SALT = 0x4F_52_45_49L
        private const val SECOND_AXIS = 0x44_49_53_4CL

        /** Far enough that what is read is unrelated to what stands here, and short of where noise would lose precision. */
        private const val LEAST_SHIFT = 2048.0
        private const val SHIFT_RANGE = 6144.0

        private const val CLAIM_SALT = 0x1
        private const val ALONG_X_SALT = 0x2
        private const val ALONG_Z_SALT = 0x3
        private const val SALT_FOLD = 32
        private const val HALF = 0.5
        private const val HALF_A_LONG = 32
        private const val LOW_HALF = 0xFFFF_FFFFL
        private const val CHUNK_WIDTH = 16
    }
}
