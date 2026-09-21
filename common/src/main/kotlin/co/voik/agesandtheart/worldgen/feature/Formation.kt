package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.worldgen.field.Placement
import co.voik.agesandtheart.worldgen.field.Spans
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Variation
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.feature.Feature
import com.mojang.serialization.MapCodec
import net.minecraft.util.RandomSource

/**
 * A shape standing on the ground, made of one substance — an obelisk, a boulder, a ring of stones.
 *
 * **The shape is a [TerrainField], which is the whole reason this is one feature and not six.** The field
 * toolkit already describes solids as spans and already serialises, so an obelisk is a box under a
 * pyramid and a ring is a cylinder with a smaller one taken out of it — authored as data in a configured
 * feature rather than written as a placement loop apiece. A pack adds a seventh shape without us.
 *
 * It also settles two things that would otherwise each need code. [TerrainField.resized] resizes the
 * *description* rather than the output — a bigger obelisk genuinely has more courses of blocks, where
 * resampling a built one stretches its staircase — so `Features.SIZE` reaches this for free. And
 * [Variation] poses each copy, which is what turns an arch to face somewhere and lets boulders differ.
 *
 * **Not terrain, though the toolkit is the terrain's.** A field laid as ground answers `columnSpans` for
 * the whole world and takes its material from `TerrainFill`, which is keyed per *column* — so an obelisk
 * built that way would make the ground beneath it gold to bedrock. A feature carries its own substance
 * and sits on top of whatever the world is made of.
 *
 * **It tiles itself, and that is what lets a formation be bigger than a chunk.** A feature may only write
 * within one chunk of the one it is decorating (`ChunkStep.blockStateWriteRadius` is 1 for the features
 * step), and past that the blocks land in a proto-chunk that has not run its noise step yet and are
 * erased when it does. So this does not take a position from vanilla's placement and build outwards from
 * it. It runs in **every** chunk, asks its own [Placement] which formations reach this one, and lays only
 * the columns it owns. Nothing is ever written outside the chunk being decorated, and a ring a hundred
 * blocks across is simply forty chunks each laying their share.
 *
 * The cost of that is the cost of the pipeline rather than of this design: a formation's position has to
 * be computable from a neighbouring chunk without asking anything, so it is a hash of the cell it sits in.
 * `Grid` keeps each cell only with `Density.keepProbability`, jitters the origin freely inside it, and
 * that probability carries a noise swing — so it scatters and clumps rather than ranking up.
 */
data class Formation(
    /**
     * The shapes this kind of formation may take, one drawn per copy.
     *
     * **A list because [Variation] can only turn a shape about Y.** It rotates the *query column* into the
     * template's frame, which is a yaw and cannot be anything else — so a ring that leans has to lean in
     * the shape itself, and several leans means several shapes.
     */
    val shapes: List<TerrainField>,
    val variation: Variation,
    /** Where formations of this kind sit — the field toolkit's own instancing, not vanilla's placement. */
    val placement: Placement,
    /** Keeps one kind of formation's layout from being another's. */
    val seed: Long,
    val substance: BlockState,
) : Feature {

    override fun codec(): MapCodec<out Feature> = CODEC

    override fun place(
        level: WorldGenLevel,
        generator: ChunkGenerator,
        random: RandomSource,
        origin: BlockPos,
    ): Boolean {
        // The chunk being decorated, which is the only one this call may write into.
        val chunk = ChunkPos(SectionPos.blockToSectionCoord(origin.x), SectionPos.blockToSectionCoord(origin.z))
        val groundAt = surfaceOf(level, generator)
        val laid = raise(level.seed, chunk, groundAt) { position, state ->
            if (!level.isOutsideBuildHeight(position)) level.setBlock(position, state, PLACED_BY_WORLDGEN)
        }
        return laid > 0
    }

    /**
     * Where the ground stands at a column, **asked of the generator rather than of a heightmap**.
     *
     * A formation is anchored once and laid by many chunks, so every one of them has to arrive at the same
     * answer for the same column — and a heightmap is only readable for a chunk that has one, which the
     * neighbours laying the far side of a ring do not. `getBaseHeight` is a pure function of the generator
     * and answers anywhere.
     *
     * The ocean floor rather than the world surface, so a formation in a sea stands on the bed instead of
     * floating on the water.
     */
    private fun surfaceOf(level: WorldGenLevel, generator: ChunkGenerator): (Int, Int) -> Int {
        val randomState = level.level.chunkSource.randomState()
        return { x, z -> generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState) }
    }

    /**
     * Every formation reaching [chunk], laid where it crosses it — of anything that can take a block, so
     * what it draws can be checked without a world under it, as [SpilledSpring.spill] is.
     *
     * Returns how many blocks it laid, so a caller can tell a formation from nothing at all.
     */
    fun raise(
        worldSeed: Long,
        chunk: ChunkPos,
        groundAt: (Int, Int) -> Int,
        lay: (BlockPos, BlockState) -> Unit,
    ): Int {
        val posed = shapes.flatMap(variation::sizesOf)
        if (posed.isEmpty()) return 0
        // **An unbounded shape is not a formation.** A slab or a half-space is solid to the horizon, and
        // there would be no cell scan wide enough to find every copy covering a column.
        val furthest = posed.maxOf { it.horizontalReach }
        if (!furthest.isFinite()) return 0

        val origins = XoroshiroRandomSource(worldSeed xor seed).forkPositional()

        // **Asked once for the chunk, never once per column.** This runs in every chunk of every Age, so
        // the common answer — nothing near — has to be cheap, and the ground under a formation is a noise
        // lookup that must not be repeated two hundred and fifty-six times for the one answer it has.
        val standing = mutableListOf<Standing>()
        val centreX = chunk.minBlockX + HALF_A_CHUNK
        val centreZ = chunk.minBlockZ + HALF_A_CHUNK
        val anywhereInTheChunk = furthest + CORNER_OF_A_CHUNK
        placement.forEachInstanceNear(centreX, centreZ, anywhereInTheChunk, origins) { x, z, _ ->
            val pose = origins.at(x, 0, z)
            val template = posed[pose.nextInt(posed.size)]
            standing += Standing(x, z, template, template.horizontalReach)
        }
        if (standing.isEmpty()) return 0

        var laid = 0
        // **A formation at a time, so the ground under it is asked for at most once.** `getBaseHeight`
        // runs a whole noise column, and a formation is anchored by every chunk that lays part of it — so
        // asked per column it was the entire cost of this feature, and asked eagerly it was paid even by
        // chunks a formation reaches but never actually touches.
        for (formation in standing) {
            var ground: Int? = null
            for (x in chunk.minBlockX..chunk.maxBlockX) {
                for (z in chunk.minBlockZ..chunk.maxBlockZ) {
                    if (!formation.couldReach(x, z)) continue
                    // Re-seeded from where the formation stands, so every chunk laying part of it turns it
                    // the same way — the pose belongs to the thing, not to the column asking about it.
                    val turning = origins.at(formation.originX, 0, formation.originZ)
                    turning.nextInt(posed.size)
                    val solid = variation.sample(
                        formation.template, x - formation.originX, z - formation.originZ, turning,
                    )
                    if (solid.ranges.isEmpty()) continue
                    val standsOn = ground ?: groundAt(formation.originX, formation.originZ).also { ground = it }
                    laid += layColumn(solid, standsOn, x, z, substance, lay)
                }
            }
        }
        return laid
    }

    /** One copy of a formation: where it stands, and which shape it drew. */
    private data class Standing(
        val originX: Int,
        val originZ: Int,
        val template: TerrainField,
        val reach: Double,
    ) {
        // Turning moves a column within the template's own reach and never outside it, so this bound holds
        // whatever pose is drawn.
        fun couldReach(x: Int, z: Int): Boolean {
            val awayX = (x - originX).toDouble()
            val awayZ = (z - originZ).toDouble()
            return awayX * awayX + awayZ * awayZ <= reach * reach
        }
    }

    private fun layColumn(
        solid: Spans,
        ground: Int,
        x: Int,
        z: Int,
        substance: BlockState,
        put: (BlockPos, BlockState) -> Unit,
    ): Int {
        var laid = 0
        for (range in solid.ranges) {
            for (height in range) {
                put(BlockPos(x, ground + height, z), substance)
                laid++
            }
        }
        return laid
    }

    private fun LevelHeightAccessor.isOutsideBuildHeight(position: BlockPos): Boolean =
        position.y < minY || position.y > maxY

    companion object {

        val CODEC: MapCodec<Formation> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                TerrainField.CODEC.listOf().fieldOf("shapes").forGetter(Formation::shapes),
                Variation.CODEC.codec().optionalFieldOf("variation", Variation.NONE)
                    .forGetter(Formation::variation),
                Placement.CODEC.fieldOf("placement").forGetter(Formation::placement),
                Codec.LONG.fieldOf("seed").forGetter(Formation::seed),
                BlockState.CODEC.fieldOf("substance").forGetter(Formation::substance),
            ).apply(instance, ::Formation)
        }

        /** Vanilla's own flag for a block a feature lays: change it, and do not tell a neighbour. */
        private const val PLACED_BY_WORLDGEN = 2

        private const val HALF_A_CHUNK = 8

        /** From a chunk's middle to its furthest corner, rounded up — so no covering formation is missed. */
        private const val CORNER_OF_A_CHUNK = 12.0
    }
}
