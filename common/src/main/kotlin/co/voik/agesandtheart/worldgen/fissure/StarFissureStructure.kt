package co.voik.agesandtheart.worldgen.fissure

import co.voik.agesandtheart.content.AgeContent
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
        val shape = context.random().nextLong()
        val cut = cutAcross(context, site, Crack.of(shape))
        val at = BlockPos(site.x, cut.floor, site.z)
        return Optional.of(GenerationStub(at) { pieces -> pieces.addPiece(StarFissurePiece(at, cut.crest, shape)) })
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

        fun isDry(offset: Pair<Int, Int>): Boolean {
            val (offsetX, offsetZ) = offset
            val floorOfTheSea =
                groundAt(context, middle.x + offsetX, middle.z + offsetZ, Heightmap.Types.OCEAN_FLOOR_WG)
            return floorOfTheSea >= generator.seaLevel
        }

        val (offsetX, offsetZ) = NEARBY.firstOrNull(::isDry) ?: (0 to 0)
        val x = middle.x + offsetX
        val z = middle.z + offsetZ
        return BlockPos(x, groundAt(context, x, z, Heightmap.Types.WORLD_SURFACE_WG), z)
    }

    /**
     * The one level the whole crack is cut at, and the highest ground it has to cut down through.
     *
     * **A tear is level, and what is over it is taken away.** Laying each column at its own ground made the
     * tear follow the hillside, which put one fissure across as many heights as the slope had — so the
     * opening a fall looks up at was never a single layer and the field had to hunt for it. One level is
     * what a crack in the skin of a world looks like anyway, and the rise above it becomes the gouge the
     * crack is seen down.
     *
     * **The lowest ground rather than the middle's**, so that nothing ever hangs in the air: every column
     * the crack reaches is then at or above the tear, and what stands over it can simply be cleared.
     * [DROPS_AT_MOST] is the one guard on that — a crack whose end happens to cross a chasm or a cave mouth
     * would otherwise take its whole length down to the bottom of it, where what is wanted is for that end
     * to run out.
     */
    private fun cutAcross(context: GenerationContext, site: BlockPos, crack: Crack): Cut {
        var lowest = site.y
        var crest = site.y
        for (offsetX in -REACH..REACH step SAMPLED_EVERY) {
            for (offsetZ in -REACH..REACH step SAMPLED_EVERY) {
                if (!crack.reaches(offsetX, offsetZ)) continue
                val ground = groundAt(context, site.x + offsetX, site.z + offsetZ, Heightmap.Types.WORLD_SURFACE_WG)
                lowest = minOf(lowest, ground)
                crest = maxOf(crest, ground)
            }
        }
        return Cut(floor = maxOf(lowest, site.y - DROPS_AT_MOST), crest = crest)
    }

    /** Where a crack is cut and how far the ground stands over it — [cutAcross]'s two answers. */
    private data class Cut(val floor: Int, val crest: Int)

    private fun groundAt(context: GenerationContext, x: Int, z: Int, through: Heightmap.Types): Int =
        context.chunkGenerator()
            .getFirstOccupiedHeight(x, z, through, context.heightAccessor(), context.randomState())

    override fun type(): StructureType<*> = AgeContent.STAR_FISSURE_STRUCTURE

    companion object {

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

        /** How coarsely the crack's own ground is read — fine enough for a shape five blocks across. */
        private const val SAMPLED_EVERY = 3

        /**
         * How far under the site's own ground the floor may be dragged, in blocks.
         *
         * **The one guard on taking the lowest ground.** A column holding no rock at all answers the bottom
         * of the world, and a crack whose end crosses a chasm or a cave mouth reads nearly as low — either
         * would take the whole length down with it, where what is wanted is for that end to run out. It is
         * also what decides how deep a gouge is on steep country, which is a look and wants an opinion.
         */
        private const val DROPS_AT_MOST = 24
    }
}

/**
 * How far a piece reaches from its middle — the crack's half-length plus everything it can wander.
 *
 * Generous on purpose: a box too small clips the ends off, and the cost of a large one is only the columns
 * [Crack.reaches] declines, since nothing here beards the terrain.
 */
private const val ROOM_TO_SPARE = 2
private const val REACH = (Crack.HALF_LENGTH + Crack.MOST_WANDER).toInt() + ROOM_TO_SPARE

/**
 * The rift itself: a ragged crack in the ground, one layer deep, filled with the fissure.
 *
 * **One block is enough, and that is the whole of the design.** What a player falls through is the surface
 * giving way; `StarFissureFall` takes them from the moment they step in and the ground under the tear stops
 * holding them, so there is no shaft to dig and nothing to land on.
 *
 * **And one layer means one level.** The whole crack is cut at [StarFissureStructure]'s floor rather than at
 * each column's own ground, so what stands over it is taken away and the rise above becomes the gouge the
 * crack is seen down. On gentle country that is a lip; on a hillside it is a slot; where the land falls away
 * under it the crack simply runs out, because a tear hanging in the air is not one.
 */
class StarFissurePiece : StructurePiece {

    constructor(at: BlockPos, crest: Int, shape: Long) : super(
        AgeContent.STAR_FISSURE_PIECE,
        0,
        BoundingBox(
            at.x - REACH, at.y, at.z - REACH,
            at.x + REACH, crest + LIP, at.z + REACH,
        ),
    ) {
        this.shape = shape
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
        // The one level the crack is cut at, which is the box's own underside — the piece is built around it.
        val floor = boundingBox.minY()
        for (x in within.minX()..within.maxX()) {
            for (z in within.minZ()..within.maxZ()) {
                if (!crack.reaches(x - middleX, z - middleZ)) continue
                // **A column the ground does not reach gets no tear.** The floor is the lowest ground the
                // crack was measured over, so this is what a carver took since, or an end that ran out over
                // open air — and a tear hanging over a drop is not one.
                val ground = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1
                if (ground < floor) continue
                cursor.set(x, floor, z)
                if (within.isInside(cursor)) level.setBlock(cursor, FISSURE, UPDATE_FLAGS)
                // And everything standing over it taken away, so the tear is a hole you can see into rather
                // than flush — up to whichever is higher, the box's own lip or **what is actually here**. A
                // neighbouring chunk decorates before this one is cut, and what it sowed over this column is
                // above the worldgen heightmap the crack is measured from: clearing only to the lip left
                // leaf litter and grass sitting on the tear a chunk of the crack at a time.
                val standing = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)
                for (y in floor + 1..maxOf(boundingBox.maxY(), standing)) {
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
         * A little above the highest ground the crack was measured over.
         *
         * The crest is read at a stride, so a cliff between two samples can stand higher than the box says.
         * Nothing depends on it: what a column clears is read from that column, and the writable area the
         * chunk hands in is what actually fences the writes.
         */
        const val LIP = 1

        const val SHAPE_KEY = "shape"

        /** No neighbour updates: this is worldgen, and a chain of them across a chunk edge is a hang. */
        const val UPDATE_FLAGS = 2
    }
}
