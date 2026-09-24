package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.worldgen.field.Placement
import co.voik.agesandtheart.worldgen.field.Spans
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.TerrainFill
import co.voik.agesandtheart.worldgen.field.Variation
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.QuartPos
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import java.util.Optional
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.feature.Feature
import com.mojang.serialization.MapCodec
import net.minecraft.util.ExtraCodecs
import net.minecraft.util.RandomSource

/**
 * A shape standing on the ground, made of one substance or several mingled — an obelisk, a boulder, a ring
 * of stones.
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
    /** What it is made of. Several mingle as a landmass's rock does — `mud and sand pits`. */
    val substances: List<BlockState>,
    /** Whether this is dug into the ground rather than stood on it — a pit. See [sinkingInto]. */
    val sunk: Boolean = false,
    /**
     * The one biome a formation may stand in, or null for anywhere — `pits in jungle`.
     *
     * **Asked of the formation, not left to the biome's feature list.** Vanilla runs a feature only in
     * chunks with one of its biomes within a chunk of them, so a formation carried by jungle alone was
     * laid in the chunks near the jungle and missing from the rest of itself. Carried everywhere and asked
     * at its own origin instead, every chunk it crosses agrees about it.
     */
    val onlyIn: Identifier? = null,
) : Feature {

    /**
     * The landmass's own mingling, so two materials read as one ground — but finer than the rock's, a
     * formation being a few dozen blocks across rather than a territory, where the rock's patches came out
     * as a stripe of each (Jonah, 2026-09-23).
     */
    private val material = TerrainFill(blocks = listOf(substances), mingleStretch = FORMATION_MINGLING)

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
        val laid = raise(
            level.seed,
            chunk,
            groundAt,
            clear = { position -> clearUpwardsFrom(level, position) },
            standsAt = onlyIn?.let { biome -> standsIn(biome, level, generator, groundAt) } ?: ANYWHERE,
        ) { position, state ->
            if (!level.isOutsideBuildHeight(position)) level.setBlock(position, state, PLACED_BY_WORLDGEN)
        }
        return laid > 0
    }

    /**
     * Air where a sunk formation cut the ground away, and whatever grew on that ground with it — grass and
     * flowers stood on a block that is no longer there. The same column, so still inside the chunk.
     */
    private fun clearUpwardsFrom(level: WorldGenLevel, position: BlockPos) {
        if (level.isOutsideBuildHeight(position)) return
        level.setBlock(position, AIR, PLACED_BY_WORLDGEN)
        val above = position.mutable().move(0, 1, 0)
        while (!level.isOutsideBuildHeight(above)) {
            val standing = level.getBlockState(above)
            val grewOnTheGround = !standing.isAir && standing.fluidState.isEmpty && standing.canBeReplaced()
            if (!grewOnTheGround) return
            level.setBlock(above, AIR, PLACED_BY_WORLDGEN)
            above.move(0, 1, 0)
        }
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
     * Whether a formation standing at a column is in [biome], read from the biome source at the ground
     * rather than from a chunk — pure, like [surfaceOf], so every chunk the formation crosses agrees.
     */
    private fun standsIn(
        biome: Identifier,
        level: WorldGenLevel,
        generator: ChunkGenerator,
        groundAt: (Int, Int) -> Int,
    ): (Int, Int) -> Boolean {
        val resolver = generator.biomeSource.createUncachedResolver(level.level.chunkSource.randomState())
        val wanted = ResourceKey.create(Registries.BIOME, biome)
        return { x, z ->
            val y = groundAt(x, z) - 1
            resolver.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z)).`is`(wanted)
        }
    }

    /**
     * Every formation reaching [chunk], laid where it crosses it — of anything that can take a block, so
     * what it draws can be checked without a world under it, as [SpilledSpring.spill] is.
     *
     * Returns how many blocks it laid, so a caller can tell a formation from nothing at all. [clear] is
     * where a [sunk] one cuts the ground away, and [standsAt] whether one may stand at its origin.
     */
    fun raise(
        worldSeed: Long,
        chunk: ChunkPos,
        groundAt: (Int, Int) -> Int,
        clear: (BlockPos) -> Unit = {},
        standsAt: (Int, Int) -> Boolean = ANYWHERE,
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
        // chunks a formation reaches but never actually touches. A sunk one asks each column it digs as well,
        // since its bank is that column's own ground.
        for (formation in standing) {
            if (!standsAt(formation.originX, formation.originZ)) continue
            // Re-seeded from where the formation stands, so every chunk laying part of it turns it the same
            // way — the pose belongs to the thing, not to the column asking about it.
            fun columnOf(x: Int, z: Int): Spans {
                val turning = origins.at(formation.originX, 0, formation.originZ)
                turning.nextInt(posed.size)
                return variation.sample(formation.template, x - formation.originX, z - formation.originZ, turning)
            }
            // Null where a sunk formation declined its site.
            val standsOn by lazy {
                if (sunk) sinkingInto(formation, groundAt, ::columnOf) else groundAt(formation.originX, formation.originZ)
            }
            columns@ for (x in chunk.minBlockX..chunk.maxBlockX) {
                for (z in chunk.minBlockZ..chunk.maxBlockZ) {
                    if (!formation.couldReach(x, z)) continue
                    val solid = columnOf(x, z)
                    if (solid.ranges.isEmpty()) continue
                    val anchor = standsOn ?: break@columns
                    // `getBaseHeight` answers the first block *above* the ground. A standing shape's 0 is that
                    // block; a sunk one's is [RECESS] under the ground's top block, so its mouth sits a step
                    // down from the ground around it — and stays there at every size, sizes being scaled
                    // about 0.
                    val base = if (sunk) anchor - 1 - RECESS else anchor
                    laid += layColumn(solid, base, x, z, lay)
                    if (sunk) cutAway(anchor - RECESS, groundAt(x, z), x, z, clear)
                }
            }
        }
        return laid
    }

    /**
     * Where a sunk formation's mouth goes, or null where the ground is too steep to dig it — vanilla's
     * surface lake, in the shape of a formation.
     *
     * The **lowest** ground under the mouth, with the ground standing higher cut away over it ([cutAway]),
     * so on a hillside the pit is a hollow dug into the slope with a bank on its uphill side rather than
     * standing proud of the ground below. A lake refuses a spot where its bowl would be open to the side;
     * this refuses one where the bank would be taller than [DEEPEST_CUT].
     *
     * Sampled on a coarse grid, since every chunk the formation crosses asks and all of them must reach the
     * same answer.
     */
    private fun sinkingInto(formation: Standing, groundAt: (Int, Int) -> Int, columnOf: (Int, Int) -> Spans): Int? {
        val step = maxOf(LEAST_SAMPLE_STEP, (formation.reach / SAMPLES_ACROSS_A_RADIUS).toInt())
        val reach = formation.reach.toInt()
        var lowest = Int.MAX_VALUE
        var highest = Int.MIN_VALUE
        for (dx in -reach..reach step step) {
            for (dz in -reach..reach step step) {
                val x = formation.originX + dx
                val z = formation.originZ + dz
                if (!formation.couldReach(x, z) || columnOf(x, z).ranges.isEmpty()) continue
                val ground = groundAt(x, z)
                lowest = minOf(lowest, ground)
                highest = maxOf(highest, ground)
            }
        }
        if (lowest == Int.MAX_VALUE) return null
        return lowest.takeIf { highest - lowest <= DEEPEST_CUT }
    }

    /** The ground over a sunk formation's mouth, from [anchor] up to where this column's own ground stood. */
    private fun cutAway(anchor: Int, ground: Int, x: Int, z: Int, clear: (BlockPos) -> Unit) {
        for (y in anchor..<ground) clear(BlockPos(x, y, z))
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
        put: (BlockPos, BlockState) -> Unit,
    ): Int {
        var laid = 0
        for (range in solid.ranges) {
            for (height in range) {
                val y = ground + height
                put(BlockPos(x, y, z), material.blockAt(x, y, z))
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
                ExtraCodecs.nonEmptyList(ExtraCodecs.compactListCodec(BlockState.CODEC)).fieldOf("substance")
                    .forGetter(Formation::substances),
                Codec.BOOL.optionalFieldOf("sunk", false).forGetter(Formation::sunk),
                Identifier.CODEC.optionalFieldOf("only_in").forGetter { Optional.ofNullable(it.onlyIn) },
            ).apply(instance) { shapes, variation, placement, seed, substances, sunk, onlyIn ->
                Formation(shapes, variation, placement, seed, substances, sunk, onlyIn.orElse(null))
            }
        }

        /** Vanilla's own flag for a block a feature lays: change it, and do not tell a neighbour. */
        private const val PLACED_BY_WORLDGEN = 2

        private val AIR: BlockState = Blocks.AIR.defaultBlockState()

        /** How wide a patch of one material runs in a formation — half the rock's `PATCHY_MINGLING`. */
        private const val FORMATION_MINGLING = 3.5

        /**
         * The tallest bank a sunk formation will cut on its uphill side. A surface lake's bowl is eight deep
         * with four of air over its fluid, so this is about what one of those tolerates before refusing.
         */
        private const val DEEPEST_CUT = 5

        /**
         * How far a sunk formation's mouth sits under the ground it was dug into. Flush, a pit read as a
         * patch of the terrain; a step down, it reads as a hole (Jonah, 2026-09-24).
         */
        private const val RECESS = 1

        /** Where a formation that names no biome may stand. */
        private val ANYWHERE: (Int, Int) -> Boolean = { _, _ -> true }

        /** How finely a sunk formation's mouth is sampled for the ground under it — see [sinkingInto]. */
        private const val SAMPLES_ACROSS_A_RADIUS = 6.0
        private const val LEAST_SAMPLE_STEP = 2

        private const val HALF_A_CHUNK = 8

        /** From a chunk's middle to its furthest corner, rounded up — so no covering formation is missed. */
        private const val CORNER_OF_A_CHUNK = 12.0
    }
}
