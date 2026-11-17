package co.voik.agesandtheart.generation

import co.voik.agesandtheart.worldgen.field.Spans
import co.voik.agesandtheart.worldgen.field.StandingFluid
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess

/**
 * An underground's lakes, and the rock they stand on or against, where the rock is vanilla's, kept through vanilla's
 * carving.
 *
 * On a landform of ours the carving mask refuses to cut either ([AgeChunkGenerator]'s `LakeHolding`). On vanilla's
 * rock the lakes are poured by the overlay after vanilla fills, and vanilla's carvers, which may cut water under the
 * ground, then ask vanilla's aquifer what the cut becomes; in a chamber that is air, so a cave passing through a lake
 * left a dry shaft with the lake standing round it as walls (Jonah, 2026-11-17). Vanilla applies its carving through
 * a private method, so this remembers the lake and its shore before the carvers run and puts back what they took.
 *
 * **Read off the lakes' own fields**, so a lake in the next chunk over is as plain as one in this.
 */
internal class HeldShore private constructor(private val kept: Long2ObjectOpenHashMap<BlockState>) {

    /** Every kept block the carvers changed, as it was. */
    fun putBack(chunk: ChunkAccess) {
        val at = BlockPos.MutableBlockPos()
        val each = kept.long2ObjectEntrySet().fastIterator()
        while (each.hasNext()) {
            val entry = each.next()
            at.set(entry.longKey)
            if (chunk.getBlockState(at) != entry.value) chunk.setBlockState(at, entry.value)
        }
    }

    companion object {
        private const val CHUNK_WIDTH = 16

        /** [lakes] and what holds them in, in [chunk], or null where there are none. */
        fun of(chunk: ChunkAccess, lakes: List<StandingFluid>): HeldShore? {
            if (lakes.isEmpty()) return null
            val minX = chunk.pos.minBlockX
            val minZ = chunk.pos.minBlockZ
            val lakeColumns = HashMap<Long, Spans>()
            fun lakeAt(x: Int, z: Int): Spans = lakeColumns.getOrPut(BlockPos.asLong(x, 0, z)) {
                lakes.fold(Spans.EMPTY) { all, lake -> all.union(lake.where.columnSpans(x, z)) }
            }
            val kept = Long2ObjectOpenHashMap<BlockState>()
            val at = BlockPos.MutableBlockPos()
            for (x in minX..<minX + CHUNK_WIDTH) {
                for (z in minZ..<minZ + CHUNK_WIDTH) {
                    // Its own lake, what that stands on, and anything beside a neighbour's.
                    val own = lakeAt(x, z)
                    val lakeAndShore = own.union(own.shifted(-1))
                        .union(lakeAt(x - 1, z)).union(lakeAt(x + 1, z))
                        .union(lakeAt(x, z - 1)).union(lakeAt(x, z + 1))
                    for (range in lakeAndShore.ranges) {
                        for (y in maxOf(range.first, chunk.minY)..minOf(range.last, chunk.maxY)) {
                            val state = chunk.getBlockState(at.set(x, y, z))
                            if (!state.isAir) kept.put(at.asLong(), state)
                        }
                    }
                }
            }
            return if (kept.isEmpty()) null else HeldShore(kept)
        }
    }
}
