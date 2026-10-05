package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.Registries
import net.minecraft.tags.TagKey
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.status.ChunkStatus

/**
 * What nara holds against an Age coming apart (design §7.1.2): **a block with nara above it and below it,
 * close by, is not taken** by a cave-in, a spreading tear or a wound — whatever it is, air included. A nara
 * floor and a nara roof make a room that survives a collapsing world; a single nara block holds nothing,
 * so scattering it protects nothing.
 *
 * "Close by" is within [REACH] blocks up and down, and up to [SIDEWAYS] columns aside, so a grid of nara
 * every third block is a floor. The sections around are asked first, off their palettes, so in a world
 * with no nara near the cost is a handful of set lookups and no block reads.
 */
object BetweenCompoundedStone {

    /** What holds what lies between — nara, and whatever a pack adds. */
    val HOLDS: TagKey<Block> = TagKey.create(Registries.BLOCK, "holds_what_lies_between".location())

    const val REACH = 8
    const val SIDEWAYS = 1

    fun holds(level: LevelReader, at: BlockPos): Boolean {
        if (level.getBlockState(at).`is`(HOLDS)) return true
        if (!anyNear(level, at)) return false
        return isHeldFrom(level, at, 1..REACH) && isHeldFrom(level, at, -REACH..-1)
    }

    /** Whether nara stands at any of [heights] above [at] (negative below), in its column or one aside. */
    private fun isHeldFrom(level: LevelReader, at: BlockPos, heights: IntRange): Boolean {
        val cursor = BlockPos.MutableBlockPos()
        for (dy in heights) {
            for (dx in -SIDEWAYS..SIDEWAYS) {
                for (dz in -SIDEWAYS..SIDEWAYS) {
                    cursor.set(at.x + dx, at.y + dy, at.z + dz)
                    if (level.getBlockState(cursor).`is`(HOLDS)) return true
                }
            }
        }
        return false
    }

    /** Whether any section the search would reach could hold nara, asked of their palettes. Loaded chunks only. */
    private fun anyNear(level: LevelReader, at: BlockPos): Boolean {
        fun isCompoundedStone(state: BlockState) = state.`is`(HOLDS)
        for (chunkX in SectionPos.blockToSectionCoord(at.x - SIDEWAYS)..SectionPos.blockToSectionCoord(at.x + SIDEWAYS)) {
            for (chunkZ in SectionPos.blockToSectionCoord(at.z - SIDEWAYS)..SectionPos.blockToSectionCoord(at.z + SIDEWAYS)) {
                val chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) ?: continue
                val lowest = SectionPos.blockToSectionCoord(at.y - REACH).coerceAtLeast(chunk.minSectionY)
                val highest = SectionPos.blockToSectionCoord(at.y + REACH).coerceAtMost(chunk.maxSectionY)
                for (sectionY in lowest..highest) {
                    val section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY))
                    if (!section.hasOnlyAir() && section.maybeHas(::isCompoundedStone)) return true
                }
            }
        }
        return false
    }
}
