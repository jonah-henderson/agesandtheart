package co.voik.agesandtheart.worldgen

import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.StructureManager
import net.minecraft.world.level.levelgen.Beardifier
import net.minecraft.world.level.levelgen.DensityFunction
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment

/**
 * The ground a structure asks us to reshape around it — the "beard" that flattens a pad under a village
 * and clears the headroom above it, so a jigsaw build lands on a hillside instead of half-floating and
 * half-buried in it.
 *
 * **The one place vanilla's density model reaches into our span model.** Vanilla adds the beard to its
 * terrain noise and keeps whatever lands above zero; a field has no density to add to, only solid and
 * not-solid. So this reads the beard's *sign*: firmly positive is ground, firmly negative is clear, and
 * anything between leaves the field's own answer alone.
 *
 * The maths is [Beardifier]'s — the kernel and the bury/beard/encapsulate weights stay vanilla's, because
 * the shapes they produce are what every vanilla structure was designed against.
 */
class TerrainAdaptation private constructor(
    private val beardifier: Beardifier,
    private val lowY: Int,
    private val highY: Int,
) {

    /**
     * Whether the block at ([x], [y], [z]) is ground, or null where the beard has no opinion. **Stateful**:
     * [Beardifier] walks its piece list per call and rewinds, so one of these is safe to reuse down a chunk
     * and never to share between chunk workers.
     */
    fun verdictAt(x: Int, y: Int, z: Int): Boolean? {
        if (y !in lowY..highY) return null
        val beard = beardifier.compute(DensityFunction.SinglePointContext(x, y, z))
        return when {
            beard > BEARD_DECIDES -> true
            beard < -BEARD_DECIDES -> false
            else -> null
        }
    }

    companion object {
        /**
         * How much beard it takes to overrule the field — the one tuning parameter here. Low enough that a
         * piece's own pad and headroom land, high enough that the kernel's long tail does not quietly
         * reshape terrain a dozen blocks away.
         */
        private const val BEARD_DECIDES = 0.1

        /** Vanilla's own kernel reach: how far past a piece the beard can still have anything to say. */
        private const val REACH = Beardifier.BEARD_KERNEL_RADIUS

        /**
         * The adaptation [chunkPos] needs, or null when nothing in it asked for any — which is almost
         * every chunk, and why the fill loop can afford to consult this per block. The height band does
         * the rest: a bearded chunk asks about the forty-odd levels its pieces reach rather than all 384.
         */
        fun around(structureManager: StructureManager, chunkPos: ChunkPos): TerrainAdaptation? {
            val boxes = structureManager
                .startsForStructure(chunkPos) { it.terrainAdaptation() != TerrainAdjustment.NONE }
                .flatMap { it.pieces }
                .filter { it.isCloseToChunk(chunkPos, REACH) }
                .map { it.boundingBox }
            if (boxes.isEmpty()) return null
            return TerrainAdaptation(
                Beardifier.forStructuresInChunk(structureManager, chunkPos),
                lowY = boxes.minOf { it.minY() } - REACH,
                highY = boxes.maxOf { it.maxY() } + REACH,
            )
        }
    }
}
