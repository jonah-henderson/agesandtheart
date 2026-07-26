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
 * **This is the one place vanilla's density model reaches into our span model.** Vanilla adds the beard
 * to its terrain noise and keeps whatever ends up above zero; a field has no density to add to, only
 * solid and not-solid. So we read the beard's *sign* instead: firmly positive means "this block is
 * ground", firmly negative means "this block is clear", and anything in between leaves the field's own
 * answer exactly as it was. The structure wins where it means to and nowhere else.
 *
 * The maths is [Beardifier]'s, not ours — the kernel, the bury/beard/encapsulate cases and their
 * weights all stay vanilla's, because the shapes they produce are what every vanilla structure was
 * designed and tested against.
 */
class TerrainAdaptation private constructor(
    private val beardifier: Beardifier,
    private val lowY: Int,
    private val highY: Int,
) {

    /**
     * Whether the block at ([x], [y], [z]) is ground, or `null` where the beard has no opinion and the
     * terrain field's own answer should stand.
     *
     * Stateful, and deliberately so: [Beardifier] walks its piece list on every call and rewinds after,
     * which makes one of these safe to reuse down a chunk but never to share between chunk workers.
     */
    fun verdictAt(x: Int, y: Int, z: Int): Boolean? {
        if (y < lowY || y > highY) return null
        val beard = beardifier.compute(DensityFunction.SinglePointContext(x, y, z))
        return when {
            beard > BEARD_DECIDES -> true
            beard < -BEARD_DECIDES -> false
            else -> null
        }
    }

    companion object {
        /**
         * How much beard it takes to overrule the field. Vanilla thresholds a *sum* at zero, which a
         * binary field cannot do, so this stands in for it: low enough that a piece's own pad and
         * headroom land, high enough that the kernel's long tail does not quietly reshape terrain a dozen
         * blocks away. The one tuning knob in the whole adaptation.
         */
        private const val BEARD_DECIDES = 0.1

        /** Vanilla's own kernel reach: how far past a piece the beard can still have anything to say. */
        private const val REACH = Beardifier.BEARD_KERNEL_RADIUS

        /**
         * The adaptation [chunkPos] needs, or `null` when nothing standing in it asked for any — which is
         * almost every chunk, and the reason the fill loop can afford to consult this per block.
         *
         * The height band is what makes the rest affordable: a bearded chunk asks about the forty-odd
         * levels its pieces actually reach rather than all 384. Narrowing horizontally too would buy
         * little, since a village's pieces are scattered across the whole chunk anyway.
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
