package co.voik.agesandtheart.worldgen.structure

import co.voik.agesandtheart.content.AgeContent
import com.mojang.datafixers.util.Either
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.JigsawBlock
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureType
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure
import java.util.Optional

/**
 * A vanilla jigsaw structure set down by its door rather than by its middle. The jigsaw places the start
 * piece as it always does; then the start's jigsaw block named [entrance] is found, the ground is read in
 * the columns just outside it, and everything is moved up or down until the jigsaw block stands on that
 * ground. On a slope the bottom step meets the ground instead of ending buried in it.
 *
 * The entrance jigsaw is the cell above the bottom step, facing out of the door; its pool is empty, so the
 * jigsaw never attaches anything to it. The ground is the lowest of [ENTRANCE_WIDTH] columns across the
 * doorway, starting [ENTRANCE_START_OFFSET] blocks to the jigsaw's left, so no part of the step is buried.
 *
 * `JigsawStructure` is final, so this holds one and asks it for the pieces.
 */
class EntranceOnGroundStructure(
    private val jigsaw: JigsawStructure,
    private val entrance: Identifier,
) : Structure(
    StructureSettings(jigsaw.biomes(), jigsaw.spawnOverrides(), jigsaw.step(), jigsaw.terrainAdaptation()),
) {

    override fun findGenerationPoint(context: GenerationContext): Optional<GenerationStub> =
        jigsaw.findValidGenerationPoint(context).map { placed -> setOnGround(placed, context) }

    /** The jigsaw asked the biome before the entrance moved it, and a few blocks up or down does not change it. */
    override fun findValidGenerationPoint(context: GenerationContext): Optional<GenerationStub> =
        findGenerationPoint(context)

    private fun setOnGround(placed: GenerationStub, context: GenerationContext): GenerationStub {
        val pieces = placed.getPiecesBuilder()
        val door = entranceOf(pieces, context)
        val rise = if (door == null) 0 else groundOutside(door, context) - door.position.y
        pieces.offsetPiecesVertically(rise)
        return GenerationStub(placed.position().above(rise), Either.right(pieces))
    }

    private class Entrance(val position: BlockPos, val outward: Direction)

    private fun entranceOf(pieces: StructurePiecesBuilder, context: GenerationContext): Entrance? {
        val start = pieces.build().pieces().firstOrNull() as? PoolElementStructurePiece ?: return null
        val jigsawBlocks = start.element.getShuffledJigsawBlocks(
            context.structureTemplateManager(),
            start.position,
            start.rotation,
            context.random(),
        )
        val named = jigsawBlocks.firstOrNull { it.name() == entrance } ?: return null
        return Entrance(named.pos(), JigsawBlock.getFrontFacing(named.state()))
    }

    /** The first free height in the lowest column across the doorway, one block out from the entrance. */
    private fun groundOutside(door: Entrance, context: GenerationContext): Int {
        val outside = door.position.relative(door.outward)
        val across = door.outward.clockWise
        return (ENTRANCE_START_OFFSET until ENTRANCE_START_OFFSET + ENTRANCE_WIDTH).minOf { step ->
            val column = outside.relative(across, step)
            context.chunkGenerator().getFirstFreeHeight(
                column.x,
                column.z,
                Heightmap.Types.WORLD_SURFACE_WG,
                context.heightAccessor(),
                context.randomState(),
            )
        }
    }

    override fun type(): StructureType<*> = AgeContent.ENTRANCE_ON_GROUND_STRUCTURE

    companion object {
        /** The steps run from one block to the entrance's left to two to its right, as seen leaving. */
        private const val ENTRANCE_START_OFFSET = -1
        private const val ENTRANCE_WIDTH = 4

        /** `JigsawStructure`'s own fields, and the name of the jigsaw block at the door. */
        val CODEC: MapCodec<EntranceOnGroundStructure> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                JigsawStructure.CODEC.forGetter { it.jigsaw },
                Identifier.CODEC.fieldOf("entrance").forGetter { it.entrance },
            ).apply(instance, ::EntranceOnGroundStructure)
        }
    }
}
