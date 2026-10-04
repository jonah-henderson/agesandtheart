package co.voik.agesandtheart.content

import co.voik.agesandtheart.generation.AgeChunkGenerator
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.ChunkGenerator

/**
 * Where an Age's sea is plasma, and what that does to the world: a plasma sea consumes every block at its
 * surface and below but bedrock, whatever the rest of the Age says (design §7.1.2), and fills back in
 * wherever it is taken from.
 */
object PlasmaSea {

    private const val CHUNK_WIDTH = 16

    /** How far over the sea its heat reaches: what the field will burn, and what generation clears for now. */
    const val FIELD_REACH = 8

    /**
     * The top of the plasma sea over a column, or null where the column's sea is anything else. Our own sea
     * where the Age laid one, and otherwise vanilla's rock's, which pours its sea as the noise settings'
     * default fluid up to their sea level.
     */
    fun surfaceAt(generator: ChunkGenerator, worldX: Int, worldZ: Int): Int? {
        val age = generator as? AgeChunkGenerator ?: return null
        val ourSurface = age.seaFill.surfaceY
        if (ourSurface != null) return ourSurface.takeIf { age.seaFill.blockAt(worldX, worldZ).`is`(Plasma.SEA) }
        val vanillasSea = age.generatorSettings().value().defaultFluid()
        return if (vanillasSea.`is`(Plasma.SEA)) (age as ChunkGenerator).seaLevel - 1 else null
    }

    /** Whether any of [generator]'s sea is plasma, which is the question asked before any column is. */
    fun isAnywhereIn(generator: ChunkGenerator): Boolean {
        val age = generator as? AgeChunkGenerator ?: return false
        val ours = age.seaFill.surfaceY != null && age.seaFill.blocks.any { it.`is`(Plasma.SEA) }
        return ours || age.generatorSettings().value().defaultFluid().`is`(Plasma.SEA)
    }

    fun isSeaAt(level: ServerLevel, position: BlockPos): Boolean {
        val surface = surfaceAt(level.chunkSource.generator, position.x, position.z) ?: return false
        return position.y <= surface
    }

    /**
     * Every block of [chunk] at or under its plasma sea's surface becomes plasma, bedrock excepted, and the
     * [FIELD_REACH] over it is cleared to air. The clearing stands in for the field's heat, which will burn
     * that band live once it is built, so a world without floating land is seen to burn rather than born bare.
     */
    fun consume(generator: ChunkGenerator, chunk: ChunkAccess) {
        val lit = Plasma.SEA.defaultBlockState().setValue(PlasmaBlock.BURIED, false)
        val buried = Plasma.SEA.defaultBlockState().setValue(PlasmaBlock.BURIED, true)
        val air = Blocks.AIR.defaultBlockState()
        val position = BlockPos.MutableBlockPos()
        for (localX in 0..<CHUNK_WIDTH) for (localZ in 0..<CHUNK_WIDTH) {
            val worldX = chunk.pos.getBlockX(localX)
            val worldZ = chunk.pos.getBlockZ(localZ)
            val surface = surfaceAt(generator, worldX, worldZ) ?: continue
            val top = minOf(surface, chunk.maxY)
            for (y in chunk.minY..top) {
                position.set(worldX, y, worldZ)
                val standing = chunk.getBlockState(position)
                // Plasma the sea fill laid is lit until told otherwise, so it is only skipped when already right.
                val wanted = if (y == surface) lit else buried
                if (standing.`is`(Blocks.BEDROCK) || standing == wanted) continue
                if (standing.hasBlockEntity()) chunk.removeBlockEntity(position)
                chunk.setBlockState(position, wanted)
            }
            for (y in surface + 1..minOf(surface + FIELD_REACH, chunk.maxY)) {
                position.set(worldX, y, worldZ)
                val standing = chunk.getBlockState(position)
                if (standing.isAir) continue
                if (standing.hasBlockEntity()) chunk.removeBlockEntity(position)
                chunk.setBlockState(position, air)
            }
        }
    }

    /** Fills each gap beside [position] that is part of the sea — never above it, nor out over land. */
    fun refillAround(level: ServerLevel, position: BlockPos) {
        for (direction in Direction.entries) {
            val beside = position.relative(direction)
            val there = level.getBlockState(beside)
            val isAGap = there.canBeReplaced() && !there.`is`(Plasma.SEA)
            if (!isAGap || !isSeaAt(level, beside)) continue
            level.setBlockAndUpdate(beside, Block.updateFromNeighbourShapes(Plasma.SEA.defaultBlockState(), level, beside))
        }
    }
}
