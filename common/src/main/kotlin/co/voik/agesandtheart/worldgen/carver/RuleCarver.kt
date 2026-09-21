package co.voik.agesandtheart.worldgen.carver

import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.CarverOutput

/**
 * Whether a block is cut away, as a pure function of where it is. The loop is the fiddly part — the mask,
 * the band, the one-chunk-only guard — so it is shared and the *judgement* is not.
 *
 * **Purity is load-bearing, not tidiness**: nothing an implementation does may touch a chunk, a registry
 * or a running server, which is what lets the terrain preview evaluate the exact object that generates.
 */
interface CarvingRule {
    /** The band this rule works in; rock outside it is untouched. */
    val fromY: Int
    val toY: Int

    /** Whether the block at this position is cut away. */
    fun cuts(worldX: Int, worldY: Int, worldZ: Int): Boolean
}

/**
 * A [CarverOutput] that can also be asked whether a position holds rock at all.
 *
 * **This is the air test that 26.2 did against the chunk itself.** A carver used to be handed the
 * `ChunkAccess` and could see what stood at a position; 26.3 hands it a write-only output, so the cheap
 * question has nowhere to be asked from. It matters here more than it would for a cave carver: a rule is
 * noise, [Porosity] runs over the whole world column rather than a tuned slice, and most of a tall column
 * is sky — so asking the cheap question first is what keeps that width affordable.
 *
 * Our own generator drives these carvers and supplies the output, so it supplies this too. The loop
 * degrades to asking the rule about everything if it is ever handed a plain mask.
 */
interface OpenGround : CarverOutput {
    fun holdsRockAt(worldX: Int, worldY: Int, worldZ: Int): Boolean
}

/**
 * Cuts rock away wherever a [CarvingRule] says so — the loop, not the judgement.
 *
 * **Because a rule is a pure function of position it cannot seam**, two chunks asking about one block
 * necessarily agreeing. So this runs *once* for the chunk being built rather than once per surrounding
 * source chunk, which is what keeps a per-block test affordable.
 */
object RuleCarving {

    private const val CHUNK_WIDTH = 16

    fun cut(rule: CarvingRule, chunkBeingBuilt: ChunkPos, sourceChunk: ChunkPos, output: CarverOutput): Boolean {
        // Position-pure, so every surrounding source chunk would recompute the same answer. Do the work
        // only when asked about the chunk actually being built.
        if (sourceChunk != chunkBeingBuilt) return false

        val lowestY = maxOf(output.minY(), rule.fromY)
        val highestY = minOf(output.maxY(), rule.toY)
        if (highestY <= lowestY) return false

        val ground = output as? OpenGround
        var cutAnything = false

        for (localX in 0..<CHUNK_WIDTH) {
            val worldX = chunkBeingBuilt.minBlockX + localX
            for (localZ in 0..<CHUNK_WIDTH) {
                val worldZ = chunkBeingBuilt.minBlockZ + localZ

                for (worldY in lowestY..highestY) {
                    if (ground != null && !ground.holdsRockAt(worldX, worldY, worldZ)) continue
                    if (!rule.cuts(worldX, worldY, worldZ)) continue

                    output.carve(localX, worldY, localZ)
                    cutAnything = true
                }
            }
        }
        return cutAnything
    }
}
