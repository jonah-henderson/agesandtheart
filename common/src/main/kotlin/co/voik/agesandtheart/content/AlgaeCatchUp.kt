package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import java.util.WeakHashMap

/**
 * Algae in a chunk that comes back after the hour turned, brought towards the hour (Jonah, 2026-10-01).
 *
 * A mat turns on its random tick, and a chunk that was away keeps the state it left with: a lake lit at
 * noon, unloaded, and come back to at midnight burned on as a band among the dark ones. So each stale mat in
 * an arriving chunk turns now, with [AlgaeBlock.chanceOfHavingTurned] — the share a lake left loaded would
 * have turned by this hour — and the rest turn on their ticks as any mat does.
 *
 * **Noted as the chunk loads, turned on the tick**, as `Worsening` is, since writing blocks inside the load
 * itself re-enters chunk loading. Only chunks whose palettes hold algae are noted at all.
 */
object AlgaeCatchUp {

    private val waiting = WeakHashMap<ServerLevel, MutableSet<Long>>()

    fun chunkArrived(level: ServerLevel, chunk: ChunkAccess) {
        val holdsAlgae = chunk.sections.any { !it.hasOnlyAir() && it.maybeHas(::isAlgae) }
        if (holdsAlgae) waiting.getOrPut(level) { LinkedHashSet() }.add(ChunkPos.pack(chunk.pos.x, chunk.pos.z))
    }

    fun chunkLeft(level: ServerLevel, at: ChunkPos) {
        val here = waiting[level] ?: return
        here.remove(ChunkPos.pack(at.x, at.z))
        if (here.isEmpty()) waiting.remove(level)
    }

    /** Every arrived chunk's algae, as far as the tick's budget goes; the rest wait for the next. */
    fun catchUp(server: MinecraftServer) {
        for (level in server.allLevels) {
            val here = waiting[level] ?: continue
            var budget = MATS_PER_TICK
            val iterator = here.iterator()
            while (iterator.hasNext() && budget > 0) {
                val at = iterator.next()
                iterator.remove()
                val chunk = level.chunkSource.getChunkNow(ChunkPos.getX(at), ChunkPos.getZ(at)) ?: continue
                budget -= turnTowardsTheHour(level, chunk)
            }
            if (here.isEmpty()) waiting.remove(level)
        }
    }

    /** Turns the chunk's stale mats by the hour's chance, answering how many it looked at. */
    private fun turnTowardsTheHour(level: ServerLevel, chunk: ChunkAccess): Int {
        val clock = level.defaultClockTime
        val lit = AlgaeBlock.isLitAtHour(clock)
        val chance = AlgaeBlock.chanceOfHavingTurned(clock)
        var looked = 0
        val cursor = BlockPos.MutableBlockPos()
        for ((index, section) in chunk.sections.withIndex()) {
            if (section.hasOnlyAir() || !section.maybeHas(::isAlgae)) continue
            val bottom = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index))
            for (y in 0..<SECTION) for (z in 0..<SECTION) for (x in 0..<SECTION) {
                val state = section.getBlockState(x, y, z)
                if (!isAlgae(state) || state.getValue(AlgaeBlock.LIT) == lit) continue
                looked++
                if (level.random.nextDouble() >= chance) continue
                cursor.set(chunk.pos.minBlockX + x, bottom + y, chunk.pos.minBlockZ + z)
                level.setBlock(cursor, state.setValue(AlgaeBlock.LIT, lit), Block.UPDATE_CLIENTS)
            }
        }
        return looked
    }

    private fun isAlgae(state: BlockState): Boolean = state.`is`(AgeContent.ALGAE_BLOCK)

    /** Enough for a whole lake's chunk in one tick, and a stop on a vault full of them arriving at once. */
    private const val MATS_PER_TICK = 2048

    private const val SECTION = 16
}
