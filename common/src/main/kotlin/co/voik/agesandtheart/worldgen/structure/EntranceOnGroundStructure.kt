package co.voik.agesandtheart.worldgen.structure

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.generation.IncongruousStructures
import com.mojang.datafixers.util.Either
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.JigsawBlock
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.BoundingBox
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
 * doorway, starting [ENTRANCE_START_OFFSET] blocks to the jigsaw's left, so no part of the step is buried, and a
 * [DoorstepPiece] clears what stands higher in front of it.
 *
 * A place where the ground anywhere under the start piece falls more than [MOST_FALL_UNDER] below the step's is
 * refused, so a building is never left hanging off a cliff on a platform of terrain adaptation.
 *
 * The template keeps one layer of structure void under its bottom step: `beard_thin` builds the ground up to
 * the second layer of a piece's box, and without that layer the second would be the step's own.
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
        jigsaw.findValidGenerationPoint(context).flatMap { placed -> Optional.ofNullable(setOnGround(placed, context)) }

    /** The jigsaw asked the biome before the entrance moved it, and a few blocks up or down does not change it. */
    override fun findValidGenerationPoint(context: GenerationContext): Optional<GenerationStub> =
        findGenerationPoint(context)

    /** The structure moved onto the ground outside its door, or null where that ground is over a drop. */
    private fun setOnGround(placed: GenerationStub, context: GenerationContext): GenerationStub? {
        val pieces = placed.getPiecesBuilder()
        val door = entranceOf(pieces, context) ?: return placed
        val stepLayer = groundOutside(door, context)
        if (fallsAwayUnder(pieces, stepLayer, context)) return null

        // The entrance is the cell over the bottom step, so it stands one over the first free height and the step on it.
        val rise = stepLayer + OVER_THE_STEP - door.position.y
        // Deprecated in vanilla with no replacement, and vanilla still calls it.
        @Suppress("DEPRECATION")
        pieces.offsetPiecesVertically(rise)
        pieces.addPiece(DoorstepPiece(doorstepOf(door, stepLayer)))
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

    /** The columns across the doorway [out] blocks from the entrance, as seen leaving. */
    private fun acrossTheDoorway(door: Entrance, out: Int): List<BlockPos> {
        val outside = door.position.relative(door.outward, out)
        val across = door.outward.clockWise
        return (ENTRANCE_START_OFFSET until ENTRANCE_START_OFFSET + ENTRANCE_WIDTH).map { outside.relative(across, it) }
    }

    /** The first free height in the lowest column across the doorway, one block out from the entrance. */
    private fun groundOutside(door: Entrance, context: GenerationContext): Int =
        acrossTheDoorway(door, out = 1).minOf { column -> groundAt(column.x, column.z, context) } + 1

    /**
     * The highest occupied block in a column — or, in an `incongruous structures` Age, the height drawn for this
     * start, which the snap would otherwise undo. Every column moves by the same draw there, so the shape of the
     * ground under a building reads the same at any height.
     */
    private fun groundAt(x: Int, z: Int, context: GenerationContext): Int =
        IncongruousStructures.groundAt(
            context.chunkGenerator(),
            x,
            z,
            Heightmap.Types.WORLD_SURFACE_WG,
            context.heightAccessor(),
            context.randomState(),
        )

    /** Whether the ground anywhere under the start piece lies more than [MOST_FALL_UNDER] below the step's. */
    private fun fallsAwayUnder(pieces: StructurePiecesBuilder, stepLayer: Int, context: GenerationContext): Boolean {
        val footprint = pieces.build().pieces().firstOrNull()?.boundingBox ?: return false
        val lowestAllowed = stepLayer - 1 - MOST_FALL_UNDER
        fun samplesAcross(min: Int, max: Int) = (min..max step FOOTPRINT_SAMPLE_SPACING) + max
        return samplesAcross(footprint.minX(), footprint.maxX()).any { x ->
            samplesAcross(footprint.minZ(), footprint.maxZ()).any { z -> groundAt(x, z, context) < lowestAllowed }
        }
    }

    /** The cells in front of the bottom step, from its layer up through a player's height. */
    private fun doorstepOf(door: Entrance, stepLayer: Int): BoundingBox {
        val nearest = acrossTheDoorway(door, out = 1)
        val farthest = acrossTheDoorway(door, out = DOORSTEP_DEPTH)
        val corners = listOf(nearest.first(), nearest.last(), farthest.first(), farthest.last())
        return BoundingBox(
            corners.minOf { it.x }, stepLayer, corners.minOf { it.z },
            corners.maxOf { it.x }, stepLayer + DOORSTEP_HEADROOM - 1, corners.maxOf { it.z },
        )
    }

    override fun type(): StructureType<*> = AgeContent.ENTRANCE_ON_GROUND_STRUCTURE

    companion object {
        /** The entrance jigsaw's height over the step under it. */
        private const val OVER_THE_STEP = 1

        /** The steps run from one block to the entrance's left to two to its right, as seen leaving. */
        private const val ENTRANCE_START_OFFSET = -1
        private const val ENTRANCE_WIDTH = 4

        /** How many rows out from the bottom step are cleared, and how high: a landing a player stands in. */
        private const val DOORSTEP_DEPTH = 2
        private const val DOORSTEP_HEADROOM = 2

        /** How far the ground under a building may fall below the step's before the place is refused. */
        private const val MOST_FALL_UNDER = 4

        /** How far apart the columns read under a building are; its far edges are always read too. */
        private const val FOOTPRINT_SAMPLE_SPACING = 4

        /** `JigsawStructure`'s own fields, and the name of the jigsaw block at the door. */
        val CODEC: MapCodec<EntranceOnGroundStructure> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                JigsawStructure.CODEC.forGetter { it.jigsaw },
                Identifier.CODEC.fieldOf("entrance").forGetter { it.entrance },
            ).apply(instance, ::EntranceOnGroundStructure)
        }
    }
}
