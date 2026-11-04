package co.voik.agesandtheart.worldgen.structure

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.StructureManager
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.StructurePiece
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext

/**
 * The cells in front of an [EntranceOnGroundStructure]'s bottom step, cleared to air so the steps can be walked up
 * from the ground. Water is left standing.
 *
 * Its box is a piece of the structure, so the terrain adaptation reads it too: a piece that is not a pool element
 * has its ground at the bottom of its box, which is the step's own layer, so the ground in front is drawn to just
 * under it.
 */
class DoorstepPiece : StructurePiece {

    constructor(box: BoundingBox) : super(AgeContent.DOORSTEP_PIECE, 0, box)

    constructor(saved: CompoundTag) : super(AgeContent.DOORSTEP_PIECE, saved)

    override fun addAdditionalSaveData(context: StructurePieceSerializationContext, saved: CompoundTag) = Unit

    override fun postProcess(
        level: WorldGenLevel,
        structures: StructureManager,
        generator: ChunkGenerator,
        random: RandomSource,
        within: BoundingBox,
        chunk: ChunkPos,
        at: BlockPos,
    ) {
        val air = Blocks.AIR.defaultBlockState()
        val box = boundingBox
        val cells = BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())
        for (cell in cells) {
            if (!within.isInside(cell)) continue
            val there = level.getBlockState(cell)
            val isInTheWay = !there.isAir && there.fluidState.isEmpty
            if (isInTheWay) level.setBlock(cell, air, UPDATE_FLAGS)
        }
    }

    private companion object {
        /** No neighbour updates: this is worldgen. */
        const val UPDATE_FLAGS = 2
    }
}
