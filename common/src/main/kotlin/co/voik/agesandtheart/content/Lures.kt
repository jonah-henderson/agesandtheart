package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.level.Level
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
    fun nearest(level: Level, around: Vec3, within: Double): Drawn? {
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
     * How far from its middle a storm falls when this many blocks are drawing it, in blocks.
     *
     * **A radius, like every other reach here**, so the disc is twice this across: the floor of forty is
     * eighty blocks of ground, not forty.
     *
     * One block already tightens it hard — the lure is the mechanic, not a slow ramp — and past
     * [ENOUGH_OF_THEM] there is nothing more to buy.
     */
    fun reachFor(blocks: Int): Double =
        ONE_LURE_REACHES + (TIGHTEST - ONE_LURE_REACHES) * drawnness(blocks)

    /**
     * How much of the tightening this many blocks have bought, from none of it to all of it.
     *
     * Named separately because the drawing wants the same number: rings that widen with the cluster are
     * how a player sees that adding blocks did anything, and reading it off the mechanic is the only way
     * that stays true when the mechanic is retuned.
     */
    fun drawnness(blocks: Int): Double =
        ((blocks - ONE).toDouble() / (ENOUGH_OF_THEM - ONE)).coerceIn(NONE, ALL_OF_IT)

    private fun aloftNear(level: Level, around: Vec3, within: Double): List<BlockPos> {
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
