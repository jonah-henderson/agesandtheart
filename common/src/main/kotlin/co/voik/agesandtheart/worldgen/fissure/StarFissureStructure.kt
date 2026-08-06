package co.voik.agesandtheart.worldgen.fissure

import co.voik.agesandtheart.content.AgeContent
import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructurePiece
import net.minecraft.world.level.levelgen.structure.StructureType
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.StructureManager
import java.util.Optional

/**
 * A tear in an Age you can fall out of (design §7.8) — the star fissure.
 *
 * **A structure rather than a feature, and the reason is the guarantee.** A rarity-filtered feature is a
 * coin flip per chunk, so a player can search a very long way and find none — which is fatal for the one
 * thing whose entire job is "there is always a way out". A structure set spaced on a jittered grid always
 * has one within a bounded distance, which *is* the escape hatch. It also makes `/locate` work, which is how
 * this gets tested at all.
 *
 * **Carved rather than pasted.** A rift is a shape, not a building, so there is no template and none of the
 * unbuilt authoring tooling is in the way: [StarFissurePiece] cuts it directly.
 */
class StarFissureStructure(settings: StructureSettings) : Structure(settings) {

    override fun findGenerationPoint(context: GenerationContext): Optional<GenerationStub> =
        onTopOfChunkCenter(context, Heightmap.Types.WORLD_SURFACE_WG) { pieces ->
            val centre = context.chunkPos().getMiddleBlockPosition(0)
            val surface = context.chunkGenerator()
                .getFirstOccupiedHeight(
                    centre.x, centre.z,
                    Heightmap.Types.WORLD_SURFACE_WG,
                    context.heightAccessor(),
                    context.randomState(),
                )
            pieces.addPiece(StarFissurePiece(centre.atY(surface), context.random()))
        }

    override fun type(): StructureType<*> = AgeContent.STAR_FISSURE_STRUCTURE

    companion object {
        val CODEC: MapCodec<StarFissureStructure> = simpleCodec(::StarFissureStructure)
    }
}

/**
 * The rift itself: a ragged shaft cut down from the ground and filled to the brim with the fissure.
 *
 * **Deep enough to fall in.** `StarFissureBlock` lets go after about a second, which is roughly fifteen
 * blocks of falling, so a shorter shaft would drop a player onto its floor having seen the stars and gone
 * nowhere — the one failure that would make the whole thing feel broken rather than rare.
 */
class StarFissurePiece : StructurePiece {

    constructor(at: BlockPos, random: RandomSource) : super(
        AgeContent.STAR_FISSURE_PIECE,
        0,
        BoundingBox(
            at.x - WIDEST, at.y - DEEP, at.z - WIDEST,
            at.x + WIDEST, at.y + LIP, at.z + WIDEST,
        ),
    ) {
        this.turn = random.nextInt(A_FULL_TURN)
    }

    constructor(saved: CompoundTag) : super(AgeContent.STAR_FISSURE_PIECE, saved) {
        this.turn = saved.getIntOr(TURN_KEY, 0)
    }

    /** Which way the crack runs, so two fissures do not read as the same object twice. */
    private val turn: Int

    override fun addAdditionalSaveData(context: StructurePieceSerializationContext, saved: CompoundTag) {
        saved.putInt(TURN_KEY, turn)
    }

    override fun postProcess(
        level: WorldGenLevel,
        structures: StructureManager,
        generator: ChunkGenerator,
        random: RandomSource,
        within: BoundingBox,
        chunk: ChunkPos,
        at: BlockPos,
    ) {
        val cursor = BlockPos.MutableBlockPos()
        val top = boundingBox.maxY()
        val bottom = boundingBox.minY()
        val middleX = (boundingBox.minX() + boundingBox.maxX()) / 2
        val middleZ = (boundingBox.minZ() + boundingBox.maxZ()) / 2
        for (x in boundingBox.minX()..boundingBox.maxX()) {
            for (z in boundingBox.minZ()..boundingBox.maxZ()) {
                val across = reaches(x - middleX, z - middleZ)
                if (!across) continue
                for (y in bottom..top) {
                    cursor.set(x, y, z)
                    if (!within.isInside(cursor)) continue
                    // Air above the ground, fissure below it: the lip is a hole you can see into, and
                    // everything under the surface is the thing you fall through.
                    val open = y > topOfGround(level, x, z)
                    level.setBlock(cursor, if (open) AIR else FISSURE, UPDATE_FLAGS)
                }
            }
        }
    }

    /** The ground here, so the shaft opens at the surface however the terrain arrived at it. */
    private fun topOfGround(level: WorldGenLevel, x: Int, z: Int): Int =
        level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1

    /**
     * Whether the crack reaches this column — a slit rather than a shaft, [turn] deciding which way it runs.
     *
     * A round hole reads as a mineshaft and a square one as a build; a narrow tear across the ground is the
     * only one of the three that reads as something that *happened* to the Age.
     */
    private fun reaches(alongX: Int, alongZ: Int): Boolean {
        val runsNorth = turn < A_FULL_TURN / 2
        val along = if (runsNorth) alongZ else alongX
        val across = if (runsNorth) alongX else alongZ
        // Tapered: widest in the middle of the run and pinched at both ends, so it looks torn open.
        val room = WIDEST - (along * along) / TAPER
        return across * across <= room
    }

    private companion object {
        val FISSURE = AgeContent.STAR_FISSURE_BLOCK.defaultBlockState()
        val AIR = Blocks.AIR.defaultBlockState()

        /** Half the crack's length, and the most it opens across. */
        const val WIDEST = 4

        /** Comfortably past the second of falling the block asks for. */
        const val DEEP = 24

        /** A little above the ground, so the tear's edge is visible rather than flush. */
        const val LIP = 1

        const val TAPER = 3
        const val A_FULL_TURN = 2
        const val TURN_KEY = "turn"

        /** No neighbour updates: this is worldgen, and a chain of them across a chunk edge is a hang. */
        const val UPDATE_FLAGS = 2
    }
}
