package co.voik.agesandtheart

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level
import net.minecraft.world.level.lighting.LightEngine
import net.minecraft.world.phys.shapes.Shapes

/**
 * One source's block light, flooded out as vanilla floods it: **`BlockLightEngine.propagateIncrease`, step for
 * step**, through the same public helpers. Each block entered costs a level, or its own dampening where that is
 * more, and light does not cross a face whose occlusion shape meets the next block's. Brightest first, so each
 * block is settled at its highest the first time it is reached; nothing is read in a chunk that is not loaded.
 */
object BlockLightFlood {

    /** The level [emitted] light from [at] reaches each block with, by position — the source itself included. */
    fun from(level: Level, at: BlockPos, emitted: Int): Long2ByteOpenHashMap {
        val levels = Long2ByteOpenHashMap()
        if (emitted <= 0) return levels
        val byLevel = Array(emitted + 1) { LongArrayFIFOQueue() }
        levels.put(at.asLong(), emitted.toByte())
        byLevel[emitted].enqueue(at.asLong())
        val from = BlockPos.MutableBlockPos()
        val into = BlockPos.MutableBlockPos()
        for (standing in emitted downTo 2) {
            val queue = byLevel[standing]
            while (!queue.isEmpty) {
                val here = queue.dequeueLong()
                if (levels.get(here).toInt() != standing) continue
                from.set(here)
                val fromState = level.getBlockState(from)
                for (way in Direction.entries) {
                    into.setWithOffset(from, way)
                    if (level.isOutsideBuildHeight(into) || !level.hasChunkAt(into)) continue
                    val intoState = level.getBlockState(into)
                    val reached = standing - maxOf(1, intoState.lightDampening)
                    if (reached <= levels.get(into.asLong()).toInt()) continue
                    val facesMeet = Shapes.faceShapeOccludes(
                        LightEngine.getOcclusionShape(fromState, way),
                        LightEngine.getOcclusionShape(intoState, way.opposite),
                    )
                    if (facesMeet) continue
                    levels.put(into.asLong(), reached.toByte())
                    if (reached > 1) byLevel[reached].enqueue(into.asLong())
                }
            }
        }
        return levels
    }
}
