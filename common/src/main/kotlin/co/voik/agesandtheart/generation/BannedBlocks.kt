package co.voik.agesandtheart.generation

import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.ChunkAccess

/**
 * The blocks a book banned (`no snow`), taken out of what the Age generates as air — whatever laid them: the surface,
 * a feature, a structure, the sea. Players may still place them; only generation is held to it (Jonah, 2026-10-21).
 */
object BannedBlocks {

    /** The blocks [banned] names that the game has. */
    fun named(banned: Set<String>): Set<Block> = banned.mapNotNull { id ->
        Identifier.tryParse(id)?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }
    }.toSet()

    /**
     * [banned] cleared from the chunk being decorated and the ones round it, since a feature reaches into its
     * neighbours. A section that never held one is passed over off its palette.
     */
    fun sweep(level: WorldGenLevel, chunk: ChunkAccess, banned: Set<Block>) {
        if (banned.isEmpty()) return
        for (dx in -NEIGHBOURS..NEIGHBOURS) for (dz in -NEIGHBOURS..NEIGHBOURS) {
            sweepOne(level.getChunk(chunk.pos.x + dx, chunk.pos.z + dz), banned)
        }
    }

    private fun sweepOne(chunk: ChunkAccess, banned: Set<Block>) {
        val air = Blocks.AIR.defaultBlockState()
        val position = BlockPos.MutableBlockPos()
        for ((index, section) in chunk.sections.withIndex()) {
            if (section.hasOnlyAir() || !section.maybeHas { it.block in banned }) continue
            val bottomY = SectionPos.sectionToBlockCoord(chunk.minSectionY + index)
            for (y in 0..<SECTION) for (z in 0..<SECTION) for (x in 0..<SECTION) {
                if (section.getBlockState(x, y, z).block !in banned) continue
                chunk.setBlockState(position.set(chunk.pos.minBlockX + x, bottomY + y, chunk.pos.minBlockZ + z), air)
            }
        }
    }

    private const val NEIGHBOURS = 1
    private const val SECTION = 16
}
