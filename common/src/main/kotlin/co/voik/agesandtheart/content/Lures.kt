package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import kotlin.math.roundToInt

/**
 * Astrite set high enough to draw a storm down on itself (design §7.1.2).
 *
 * **Found by scanning, and deliberately not indexed.** A storm gathers about three times an hour, so the
 * search happens three times an hour — there is nothing here to keep up to date, nothing to go stale when
 * a chunk unloads, and no bookkeeping to get wrong. What makes that affordable is the same trick `Wounds`
 * uses: a section's palette answers whether it holds a lure at all, so all but a handful of sections are
 * dismissed without reading a single position, and only the two sections per chunk above [AstriteBlock.HIGH_ENOUGH]
 * are looked at in the first place.
 *
 * **A cluster is proximity and nothing else** — no shape, no connection, no chunk. A cube, a flat pad and
 * a scattered dozen within [TOGETHER] all read the same, so a player never has to learn an arrangement or
 * discover that a grid exists.
 */
object Lures {

    /** Where a storm has been drawn to, and how strongly. */
    data class Drawn(val at: Vec3, val blocks: Int)

    /**
     * The cluster nearest [around], or nothing if there is none within [within].
     *
     * The nearest qualifying block anchors it and everything within [TOGETHER] of that joins; the centroid
     * is where the storm falls and the count is how tightly.
     */
    fun nearest(level: ServerLevel, around: Vec3, within: Double): Drawn? {
        val found = aloftNear(level, around, within)
        val anchor = found.minByOrNull { it.distToCenterSqr(around) } ?: return null
        val cluster = found.filter { it.distSqr(anchor) <= TOGETHER * TOGETHER }
        val middle = Vec3(
            cluster.sumOf { it.x + HALF } / cluster.size,
            cluster.sumOf { it.y.toDouble() } / cluster.size,
            cluster.sumOf { it.z + HALF } / cluster.size,
        )
        return Drawn(middle, cluster.size)
    }

    /**
     * How wide a storm falls when this many blocks are drawing it, in blocks.
     *
     * One block already tightens it hard — the lure is the mechanic, not a slow ramp — and past
     * [ENOUGH_OF_THEM] there is nothing more to buy.
     */
    fun reachFor(blocks: Int): Double {
        val share = (blocks - ONE).toDouble() / (ENOUGH_OF_THEM - ONE)
        val drawn = share.coerceIn(NONE, ALL_OF_IT)
        return ONE_LURE_REACHES + (TIGHTEST - ONE_LURE_REACHES) * drawn
    }

    private fun aloftNear(level: ServerLevel, around: Vec3, within: Double): List<BlockPos> {
        val found = mutableListOf<BlockPos>()
        val middle = BlockPos.containing(around)
        val reach = within.roundToInt()
        val fromX = SectionPos.blockToSectionCoord(middle.x - reach)
        val toX = SectionPos.blockToSectionCoord(middle.x + reach)
        val fromZ = SectionPos.blockToSectionCoord(middle.z - reach)
        val toZ = SectionPos.blockToSectionCoord(middle.z + reach)
        for (chunkX in fromX..toX) {
            for (chunkZ in fromZ..toZ) {
                val chunk = level.chunkSource.getChunkNow(chunkX, chunkZ) ?: continue
                for (section in chunk.sections.indices) {
                    val bottom = chunk.getSectionYFromSectionIndex(section) * SECTION
                    if (bottom + SECTION <= AstriteBlock.HIGH_ENOUGH) continue
                    val states = chunk.sections[section]
                    if (states.hasOnlyAir() || !states.maybeHas(::drawing)) continue
                    for (x in 0..<SECTION) {
                        for (y in 0..<SECTION) {
                            for (z in 0..<SECTION) {
                                if (!drawing(states.getBlockState(x, y, z))) continue
                                val at = BlockPos(chunk.pos.minBlockX + x, bottom + y, chunk.pos.minBlockZ + z)
                                if (at.distToCenterSqr(around) <= within * within) found += at
                            }
                        }
                    }
                }
            }
        }
        return found
    }

    private fun drawing(state: net.minecraft.world.level.block.state.BlockState): Boolean =
        state.block is AstriteBlock && state.getValue(AstriteBlock.ALOFT)

    /** How near two blocks must be to be drawing together, in blocks. Generous, so no shape is implied. */
    private const val TOGETHER = 8.0

    /** What one block draws a storm down to, and what any number past [ENOUGH_OF_THEM] does. */
    private const val ONE_LURE_REACHES = 90.0
    private const val TIGHTEST = 40.0
    private const val ENOUGH_OF_THEM = 27

    private const val SECTION = 16
    private const val HALF = 0.5
    private const val ONE = 1
    private const val NONE = 0.0
    private const val ALL_OF_IT = 1.0
}
