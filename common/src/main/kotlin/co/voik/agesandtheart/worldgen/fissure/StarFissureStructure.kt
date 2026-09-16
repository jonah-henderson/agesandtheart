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

    override fun findGenerationPoint(context: GenerationContext): Optional<GenerationStub> {
        val site = drySiteNear(context)
        return Optional.of(GenerationStub(site) { pieces -> pieces.addPiece(StarFissurePiece(site, context.random())) })
    }

    /**
     * The nearest dry ground to the chunk's middle, or the middle itself if this Age has none nearby.
     *
     * **It slides rather than declines**, and that is the whole shape of it. A fissure that refused a wet
     * cell would leave a gap in the grid, and the grid *is* the guarantee — the escape hatch's entire job
     * is that there is always one within a bounded distance. So a sea Age still gets its fissure on the
     * ocean floor; it just gets it there only when there was nothing better within reach.
     *
     * Dry means the **ocean floor** stands at or above sea level: `WORLD_SURFACE_WG` counts water as
     * surface, so asking it would call every ocean dry.
     */
    private fun drySiteNear(context: GenerationContext): BlockPos {
        val middle = context.chunkPos().getMiddleBlockPosition(0)
        val generator = context.chunkGenerator()

        fun groundAt(x: Int, z: Int, through: Heightmap.Types) =
            generator.getFirstOccupiedHeight(x, z, through, context.heightAccessor(), context.randomState())

        fun isDry(offset: Pair<Int, Int>): Boolean {
            val (offsetX, offsetZ) = offset
            return groundAt(middle.x + offsetX, middle.z + offsetZ, Heightmap.Types.OCEAN_FLOOR_WG) >= generator.seaLevel
        }

        val (offsetX, offsetZ) = NEARBY.firstOrNull(::isDry) ?: (0 to 0)
        val x = middle.x + offsetX
        val z = middle.z + offsetZ
        return BlockPos(x, groundAt(x, z, Heightmap.Types.WORLD_SURFACE_WG), z)
    }

    override fun type(): StructureType<*> = AgeContent.STAR_FISSURE_STRUCTURE

    companion object {
        val CODEC: MapCodec<StarFissureStructure> = simpleCodec(::StarFissureStructure)

        /**
         * How far the site may slide, and how coarsely it looks — twenty-five columns, walked nearest
         * first, so land costs one sample and only a wholly drowned cell pays for all of them.
         */
        private const val LOOK_AROUND = 32
        private const val LOOK_EVERY = 16

        private val NEARBY: List<Pair<Int, Int>> =
            (-LOOK_AROUND..LOOK_AROUND step LOOK_EVERY).flatMap { offsetX ->
                (-LOOK_AROUND..LOOK_AROUND step LOOK_EVERY).map { offsetZ -> offsetX to offsetZ }
            }.sortedBy { (offsetX, offsetZ) -> offsetX * offsetX + offsetZ * offsetZ }
    }
}

/**
 * The rift itself: a ragged crack in the ground, one layer deep, filled with the fissure.
 *
 * **One block is enough, and that is the whole of the design.** What a player falls through is the surface
 * giving way; `StarFissureFall` takes them from the moment they step in and the ground under the tear stops
 * holding them, so there is no shaft to dig and nothing to land on.
 */
class StarFissurePiece : StructurePiece {

    constructor(at: BlockPos, random: RandomSource) : super(
        AgeContent.STAR_FISSURE_PIECE,
        0,
        BoundingBox(
            at.x - REACH, at.y - DEEP, at.z - REACH,
            at.x + REACH, at.y + LIP, at.z + REACH,
        ),
    ) {
        this.shape = random.nextLong()
    }

    constructor(saved: CompoundTag) : super(AgeContent.STAR_FISSURE_PIECE, saved) {
        this.shape = saved.getLongOr(SHAPE_KEY, 0L)
    }

    /**
     * The whole of what this fissure looks like, in one number.
     *
     * One seed rather than an angle, a length and a wobble each saved separately: everything is derived
     * from it at generation time, so the shape can be retuned later without a piece written last week
     * decoding into something with fields it has never heard of.
     */
    private val shape: Long

    override fun addAdditionalSaveData(context: StructurePieceSerializationContext, saved: CompoundTag) {
        saved.putLong(SHAPE_KEY, shape)
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
        val crack = Crack.of(shape)
        val cursor = BlockPos.MutableBlockPos()
        val middleX = (boundingBox.minX() + boundingBox.maxX()) / 2
        val middleZ = (boundingBox.minZ() + boundingBox.maxZ()) / 2
        for (x in within.minX()..within.maxX()) {
            for (z in within.minZ()..within.maxZ()) {
                if (!crack.reaches(x - middleX, z - middleZ)) continue
                // **One layer, at this column's own ground.** A tear is a hole in the world's skin rather
                // than a shaft: what you fall through is the surface giving way, and the fall itself is
                // `StarFissureFall`'s from the moment you step in.
                val ground = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1
                cursor.set(x, ground, z)
                if (within.isInside(cursor)) level.setBlock(cursor, FISSURE, UPDATE_FLAGS)
                // And the lip cleared over it, so the tear is a hole you can see into rather than flush.
                for (y in ground + 1..boundingBox.maxY()) {
                    cursor.set(x, y, z)
                    if (!within.isInside(cursor)) continue
                    level.setBlock(cursor, AIR, UPDATE_FLAGS)
                }
            }
        }
    }

    private companion object {
        val FISSURE = AgeContent.STAR_FISSURE_BLOCK.defaultBlockState()
        val AIR = Blocks.AIR.defaultBlockState()

        /**
         * How far the box reaches from the middle — the crack's half-length plus everything it can wander.
         *
         * Generous on purpose: a box too small clips the ends off, and the cost of a large one is only the
         * columns [Crack.reaches] declines, since nothing here beards the terrain.
         */
        const val ROOM_TO_SPARE = 2
        const val REACH = (Crack.HALF_LENGTH + Crack.MOST_WANDER).toInt() + ROOM_TO_SPARE

        /**
         * How far below the middle's own surface the box reaches.
         *
         * Nothing is filled this deep — the tear is one layer, at each column's own ground. The box only
         * has to *contain* that ground, and a crack crossing a slope meets a good spread of them, so the
         * slack is what stops the low end of a crack falling outside its own piece.
         */
        const val DEEP = 24

        /** A little above the ground, so the tear's edge is visible rather than flush. */
        const val LIP = 1

        const val SHAPE_KEY = "shape"

        /** No neighbour updates: this is worldgen, and a chain of them across a chunk edge is a hang. */
        const val UPDATE_FLAGS = 2
    }
}
