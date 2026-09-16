package co.voik.agesandtheart.worldgen.fissure

import co.voik.agesandtheart.content.AgeContent
import com.mojang.serialization.MapCodec
import co.voik.agesandtheart.worldgen.field.fieldNoise
import net.minecraft.world.level.levelgen.synth.NormalNoise
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
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
                val ground = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1
                for (y in boundingBox.minY()..boundingBox.maxY()) {
                    cursor.set(x, y, z)
                    if (!within.isInside(cursor)) continue
                    // Air above the ground, fissure below it: the lip is a hole you can see into, and
                    // everything under the surface is the thing you fall through.
                    level.setBlock(cursor, if (y > ground) AIR else FISSURE, UPDATE_FLAGS)
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

        /** Comfortably past the second of falling the block asks for. */
        const val DEEP = 24

        /** A little above the ground, so the tear's edge is visible rather than flush. */
        const val LIP = 1

        const val SHAPE_KEY = "shape"

        /** No neighbour updates: this is worldgen, and a chain of them across a chunk edge is a hang. */
        const val UPDATE_FLAGS = 2
    }
}

/**
 * The plan of one crack: which way it runs, how it wanders, and how wide it is along its length.
 *
 * **Off the field toolkit's own noise**, which is the answer to "should this be worldgen somewhere". A
 * fissure is a landform, and the mod already has the thing that makes landforms look like they happened
 * rather than were placed — so the wander and the width are `fieldNoise` samples, not a taper written out
 * in arithmetic. The first version was a symmetric parabola on one of two axes and read, correctly, as a
 * hole somebody had dug.
 *
 * Three things do the work. It runs at **any angle**, not one of two. Its centreline **wanders**, so the
 * two sides are never mirror images. And its width **varies along the run** and pinches to nothing at both
 * ends, which is what makes it read as torn open rather than bored out.
 */
internal class Crack(private val alongX: Double, private val alongZ: Double, private val noise: NormalNoise) {

    fun reaches(offsetX: Int, offsetZ: Int): Boolean = reaches(offsetX, offsetZ, 0.0)

    /**
     * The same crack [wider] blocks fatter and longer — **which is what lets a tear grow without becoming a
     * circle**.
     *
     * A radius around a point reads as a hole somebody bored; a crack widened along its own axis still
     * reads as something torn. Both the half-length and the width take the widening, so a tear that has
     * been open a while is a longer, fatter crack rather than a blob with a crack inside it.
     */
    fun reaches(offsetX: Int, offsetZ: Int, wider: Double): Boolean {
        val (offCentre, width) = measure(offsetX, offsetZ, wider) ?: return false
        return offCentre <= width
    }

    /**
     * How central a column is: 1 on the crack's own centreline, falling to 0 at its edge, and below 0
     * outside it — what a cave-in cuts its floor to, so a trough is deepest down the middle.
     */
    fun centralityAt(offsetX: Int, offsetZ: Int): Double {
        val (offCentre, width) = measure(offsetX, offsetZ, 0.0) ?: return OUTSIDE
        return if (width > 0.0) 1.0 - offCentre / width else OUTSIDE
    }

    /** How far a column stands off the crack's wandering centre, and how wide the crack is there; null past its ends. */
    private fun measure(offsetX: Int, offsetZ: Int, wider: Double): Pair<Double, Double>? {
        // Into the crack's own frame: how far along its run, and how far off its centre.
        val along = offsetX * alongX + offsetZ * alongZ
        val across = -offsetX * alongZ + offsetZ * alongX
        val length = HALF_LENGTH + wider
        if (along < -length || along > length) return null

        val reach = along / length
        // Pinched at both ends, so it tapers to a point rather than stopping square.
        val taper = 1.0 - reach * reach
        val wander = noise.getValue(along * WANDER_SCALE, 0.0, 0.0) * MOST_WANDER * taper
        val widening = noise.getValue(0.0, 0.0, along * WIDTH_SCALE) * WIDTH_VARIES
        val width = (NARROWEST + widening + wider) * taper
        return kotlin.math.abs(across - wander) to width
    }

    companion object {
        /** What [centralityAt] says of a column the crack does not reach at all. */
        private const val OUTSIDE = -1.0

        /** Long and thin: a crack across the ground rather than a pit in it. */
        const val HALF_LENGTH = 17.0

        /** How far the centreline may stray, which is what stops the two sides mirroring. */
        const val MOST_WANDER = 5.0

        // Wide enough that the middle is never a single column: walked at 1.4 and the crack pinched to
        // one block for long stretches, which reads as a seam in the ground rather than a way into it.
        private const val NARROWEST = 2.6
        private const val WIDTH_VARIES = 1.6

        /** Slow enough that the wander reads as a curve rather than as static. */
        private const val WANDER_SCALE = 0.06
        private const val WIDTH_SCALE = 0.22

        private const val OCTAVE = -3
        private val AMPLITUDES = listOf(1.0, 0.5)

        fun of(shape: Long): Crack {
            val turn = XoroshiroRandomSource(shape).nextDouble() * Math.PI
            return Crack(kotlin.math.cos(turn), kotlin.math.sin(turn), fieldNoise(shape, OCTAVE, AMPLITUDES))
        }
    }
}
