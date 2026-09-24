package co.voik.agesandtheart.worldgen.feature

import co.voik.agesandtheart.worldgen.field.fieldNoise
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.tags.TagKey
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.material.rule.OreVeinRule
import net.minecraft.world.level.levelgen.synth.Noise
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * An ore vein as vanilla lays its copper and iron ones: a long ribbon of ore and filler rock that follows
 * the ridges of two noises, wherever a third, very coarse one says veins run at all.
 *
 * **Vanilla's is not a feature.** It is a material rule inside its own noise fill (`OreVeinRule`), so it
 * reaches no Age built on our rock and nothing could mint one. This is the same arithmetic run as a
 * feature: like [Formation] it runs in every chunk and asks the noise at each block, so a vein crosses
 * chunk borders without a neighbour being consulted. The constants and noise scales are vanilla's own
 * (`NoiseRouterData.registerOreVeins`, `OreVeinRule`), and so is sampling the three shaping noises at
 * the corners of four-by-eight-by-four cells and interpolating between, which is what makes it cheap.
 *
 * One vein type rather than vanilla's two: vanilla splits copper and iron on the sign of the coarse
 * noise, and this takes either sign, so a writer's veins run as often as vanilla's two kinds together.
 */
data class OreVein(
    val ore: BlockState,
    /** The ore's own raw block, which turns up now and then in a vein — see [rawOreChance]. */
    val rawOre: BlockState,
    /** The rock the ore is strung through: tuff in vanilla's iron veins, granite in its copper ones. */
    val filler: BlockState,
    val rawOreChance: Float,
    val minY: Int,
    val maxY: Int,
    /** What a vein may cut through. */
    val cuts: TagKey<Block>,
    /** And an Age's own rock where [cuts] does not reach it — see `FeatureShape.withShape`. */
    val alsoCuts: List<Block> = emptyList(),
    /** How far the noises are stretched: two is veins twice as long and twice as thick. */
    val stretch: Double = 1.0,
    /**
     * How much of the ground veins may run through, against vanilla's own: `teeming veins` is four times
     * as much of it. Read through [VEININESS_ADMITS], so it moves the one threshold that decides where veins
     * run rather than laying the vein pass again over the same blocks.
     */
    val abundance: Double = 1.0,
    /** Keeps one kind of vein's layout from being another's. */
    val seed: Long,
) : Feature {

    /** The coarse noise's threshold that admits [abundance] times vanilla's share of the ground. */
    private val threshold: Double = thresholdAdmitting(VANILLA_SHARE * abundance)

    /**
     * This vein moved to the band vanilla's copper veins stand in, strung through granite, with a deepslate
     * ore swapped for its stone variant — what a **shallow** vein is. Vanilla's iron vein is the pattern, so
     * a vein asked to sit deep keeps it.
     */
    fun inTheCopperBand(): OreVein = copy(
        minY = OreVeinRule.VeinType.COPPER.minY,
        maxY = OreVeinRule.VeinType.COPPER.maxY,
        filler = Blocks.GRANITE.defaultBlockState(),
        ore = stoneVariantOf(ore.block).defaultBlockState(),
        rawOre = stoneVariantOf(rawOre.block).defaultBlockState(),
    )

    override fun codec(): MapCodec<out Feature> = CODEC

    override fun place(level: WorldGenLevel, generator: ChunkGenerator, random: RandomSource, origin: BlockPos): Boolean {
        val chunk = ChunkPos(SectionPos.blockToSectionCoord(origin.x), SectionPos.blockToSectionCoord(origin.z))
        val laid = lay(level.seed, chunk, max(minY, level.minY), min(maxY, level.maxY), level::getBlockState) { at, state ->
            level.setBlock(at, state, PLACED_BY_WORLDGEN)
        }
        return laid > 0
    }

    /**
     * The part of every vein that crosses [chunk] between [lowY] and [highY], read from [standingAt] and
     * written through [put] — of anything that can answer and take a block, so what it lays can be checked
     * without a world under it, as `Formation.raise` is. Returns how many blocks it laid.
     */
    fun lay(
        worldSeed: Long,
        chunk: ChunkPos,
        lowY: Int,
        highY: Int,
        standingAt: (BlockPos) -> BlockState,
        put: (BlockPos, BlockState) -> Unit,
    ): Int {
        if (lowY > highY) return 0
        val salt = worldSeed xor seed
        val scale = stretch.coerceAtLeast(SMALLEST_STRETCH)
        val veininess = CellGrid(fieldNoise(salt xor VEININESS_SALT, VEININESS_OCTAVE, ONE_OCTAVE), VEININESS_SCALE / scale, chunk, lowY, highY)
        val ridgeA = CellGrid(fieldNoise(salt xor RIDGE_A_SALT, RIDGE_OCTAVE, ONE_OCTAVE), RIDGE_SCALE / scale, chunk, lowY, highY)
        val ridgeB = CellGrid(fieldNoise(salt xor RIDGE_B_SALT, RIDGE_OCTAVE, ONE_OCTAVE), RIDGE_SCALE / scale, chunk, lowY, highY)
        val gap = fieldNoise(salt xor GAP_SALT, GAP_OCTAVE, ONE_OCTAVE)
        val positional = XoroshiroRandomSource(salt).forkPositional()

        val cursor = BlockPos.MutableBlockPos()
        var laid = 0
        for (localX in 0..<BLOCKS_ACROSS) {
            for (localZ in 0..<BLOCKS_ACROSS) {
                for (y in lowY..highY) {
                    val strength = abs(veininess.at(localX, y, localZ))
                    // Nothing within a band this far off threshold can pass, so most blocks stop here.
                    if (strength < threshold - MOST_EDGE_ROUNDOFF) continue
                    val fromTheBandEdge = min(maxY - y, y - minY).toDouble()
                    val edgeRoundoff = Mth.clampedMap(fromTheBandEdge, 0.0, EDGE_ROUNDOFF_BEGINS, -MOST_EDGE_ROUNDOFF, 0.0)
                    if (strength - threshold + edgeRoundoff <= 0.0) continue
                    val ridge = RIDGE_WIDTH - max(abs(ridgeA.at(localX, y, localZ)), abs(ridgeB.at(localX, y, localZ)))
                    if (ridge < 0.0) continue

                    val worldX = chunk.minBlockX + localX
                    val worldZ = chunk.minBlockZ + localZ
                    val roll = positional.at(worldX, y, worldZ)
                    if (roll.nextFloat() > VEIN_SOLIDNESS) continue
                    cursor.set(worldX, y, worldZ)
                    if (!canCut(standingAt(cursor))) continue

                    val richness = Mth.clampedMap(strength, threshold, threshold + RICHENS_OVER, LEAST_RICHNESS, MOST_RICHNESS)
                    val inAGap = GAP_OFFSET - gap.get(worldX.toDouble(), y.toDouble(), worldZ.toDouble()) >= 0.0
                    val laidHere = when {
                        roll.nextFloat() >= richness || inAGap -> filler
                        roll.nextFloat() < rawOreChance -> rawOre
                        else -> ore
                    }
                    put(cursor.immutable(), laidHere)
                    laid++
                }
            }
        }
        return laid
    }

    private fun canCut(standing: BlockState): Boolean = standing.`is`(cuts) || standing.block in alsoCuts

    /**
     * One noise sampled at the corners of vanilla's cells across a chunk and read back by interpolation —
     * vanilla's `yLimitedInterpolatable`, over just the band a vein may stand in.
     */
    private class CellGrid(noise: Noise, private val scale: Double, chunk: ChunkPos, lowY: Int, highY: Int) {
        private val baseY = Math.floorDiv(lowY, CELL_HEIGHT) * CELL_HEIGHT
        private val layers = Math.floorDiv(highY - baseY, CELL_HEIGHT) + 2
        private val corners = DoubleArray(CORNERS_ACROSS * CORNERS_ACROSS * layers) { index ->
            val cornerX = index / (CORNERS_ACROSS * layers)
            val cornerZ = index / layers % CORNERS_ACROSS
            val layer = index % layers
            noise.get(
                (chunk.minBlockX + cornerX * CELL_WIDTH) * scale,
                (baseY + layer * CELL_HEIGHT) * scale,
                (chunk.minBlockZ + cornerZ * CELL_WIDTH) * scale,
            ).toDouble()
        }

        private fun corner(cornerX: Int, layer: Int, cornerZ: Int): Double =
            corners[(cornerX * CORNERS_ACROSS + cornerZ) * layers + layer]

        fun at(localX: Int, y: Int, localZ: Int): Double {
            val cellX = localX / CELL_WIDTH
            val cellZ = localZ / CELL_WIDTH
            val layer = (y - baseY) / CELL_HEIGHT
            return Mth.lerp3(
                (localX % CELL_WIDTH) / CELL_WIDTH.toDouble(),
                ((y - baseY) % CELL_HEIGHT) / CELL_HEIGHT.toDouble(),
                (localZ % CELL_WIDTH) / CELL_WIDTH.toDouble(),
                corner(cellX, layer, cellZ), corner(cellX + 1, layer, cellZ),
                corner(cellX, layer + 1, cellZ), corner(cellX + 1, layer + 1, cellZ),
                corner(cellX, layer, cellZ + 1), corner(cellX + 1, layer, cellZ + 1),
                corner(cellX, layer + 1, cellZ + 1), corner(cellX + 1, layer + 1, cellZ + 1),
            )
        }
    }

    companion object {
        val CODEC: MapCodec<OreVein> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BlockState.CODEC.fieldOf("ore").forGetter(OreVein::ore),
                BlockState.CODEC.fieldOf("raw_ore").forGetter(OreVein::rawOre),
                BlockState.CODEC.fieldOf("filler").forGetter(OreVein::filler),
                Codec.floatRange(0.0f, 1.0f).optionalFieldOf("raw_ore_chance", VANILLA_RAW_ORE_CHANCE)
                    .forGetter(OreVein::rawOreChance),
                Codec.INT.fieldOf("min_y").forGetter(OreVein::minY),
                Codec.INT.fieldOf("max_y").forGetter(OreVein::maxY),
                TagKey.hashedCodec(Registries.BLOCK).fieldOf("cuts").forGetter(OreVein::cuts),
                BuiltInRegistries.BLOCK.byNameCodec().listOf().optionalFieldOf("also_cuts", emptyList())
                    .forGetter(OreVein::alsoCuts),
                Codec.DOUBLE.optionalFieldOf("stretch", 1.0).forGetter(OreVein::stretch),
                Codec.DOUBLE.optionalFieldOf("abundance", 1.0).forGetter(OreVein::abundance),
                Codec.LONG.fieldOf("seed").forGetter(OreVein::seed),
            ).apply(instance, ::OreVein)
        }

        /**
         * The raw block that goes with each of vanilla's ores, so a minted `gold_ore veins` is flecked with
         * raw gold rather than keeping the pattern's raw iron. Any other substance is its own raw block.
         */
        fun rawBlockOf(ore: Block): Block = RAW_BLOCKS[ore] ?: ore

        /** `deepslate_iron_ore` → `iron_ore`, for any of vanilla's ores with both; anything else is itself. */
        private fun stoneVariantOf(block: Block): Block {
            val id = BuiltInRegistries.BLOCK.getKey(block)
            if (!id.path.startsWith(DEEPSLATE_PREFIX)) return block
            val stone = id.withPath(id.path.removePrefix(DEEPSLATE_PREFIX))
            return BuiltInRegistries.BLOCK.getOptional(stone).orElse(block)
        }

        private const val DEEPSLATE_PREFIX = "deepslate_"

        /**
         * The threshold at which vanilla's coarse vein noise admits [share] of the ground, read off
         * [VEININESS_ADMITS] and interpolated between its rows.
         */
        fun thresholdAdmitting(share: Double): Double {
            val wanted = share.coerceIn(VEININESS_ADMITS.last().second, VEININESS_ADMITS.first().second)
            // Rows run from the loosest threshold to the strictest, so the first pair whose stricter row admits
            // no more than is wanted is the pair the answer lies between.
            val (looser, stricter) = VEININESS_ADMITS.zipWithNext().first { (_, stricter) -> wanted >= stricter.second }
            return Mth.clampedMap(wanted, looser.second, stricter.second, looser.first, stricter.first)
        }

        /**
         * How much of the ground the coarse noise leaves above each threshold — **measured** (2026-09-22),
         * 230,400 samples over eight seeds at vanilla's octave and scale, so an amount can scale the share
         * of ground veins run through rather than guess at a threshold. Vanilla's 0.4 is [VANILLA_SHARE].
         */
        private val VEININESS_ADMITS = listOf(
            0.05 to 0.878,
            0.1 to 0.759,
            0.15 to 0.646,
            0.2 to 0.540,
            0.25 to 0.444,
            0.3 to 0.358,
            0.35 to 0.283,
            0.4 to 0.218,
            0.5 to 0.121,
            0.6 to 0.059,
            0.7 to 0.025,
            0.8 to 0.008,
        )
        private const val VANILLA_SHARE = 0.218

        private val RAW_BLOCKS: Map<Block, Block> = mapOf(
            Blocks.IRON_ORE to Blocks.RAW_IRON_BLOCK,
            Blocks.DEEPSLATE_IRON_ORE to Blocks.RAW_IRON_BLOCK,
            Blocks.COPPER_ORE to Blocks.RAW_COPPER_BLOCK,
            Blocks.DEEPSLATE_COPPER_ORE to Blocks.RAW_COPPER_BLOCK,
            Blocks.GOLD_ORE to Blocks.RAW_GOLD_BLOCK,
            Blocks.DEEPSLATE_GOLD_ORE to Blocks.RAW_GOLD_BLOCK,
            Blocks.NETHER_GOLD_ORE to Blocks.RAW_GOLD_BLOCK,
        )

        private const val PLACED_BY_WORLDGEN = 2
        private const val BLOCKS_ACROSS = 16
        private const val SMALLEST_STRETCH = 0.1

        // Vanilla's cell, and the corners one chunk's worth of cells needs.
        private const val CELL_WIDTH = 4
        private const val CELL_HEIGHT = 8
        private const val CORNERS_ACROSS = BLOCKS_ACROSS / CELL_WIDTH + 1

        // Vanilla's noises: `ore_veininess` at octave -8 read at 1.5, `ore_vein_a`/`_b` at -7 read at 4, and
        // `ore_gap` at -5 read at 1. Each is one octave.
        private val ONE_OCTAVE = listOf(1.0)
        private const val VEININESS_OCTAVE = -8
        private const val VEININESS_SCALE = 1.5
        private const val RIDGE_OCTAVE = -7
        private const val RIDGE_SCALE = 4.0
        private const val GAP_OCTAVE = -5

        // `OreVeinRule`'s and `NoiseRouterData`'s constants. Richness climbs over the 0.2 above the threshold
        // that vanilla's climbs over between 0.4 and 0.6.
        private const val MOST_EDGE_ROUNDOFF = 0.2
        private const val EDGE_ROUNDOFF_BEGINS = 20.0
        private const val RIDGE_WIDTH = 0.08
        private const val VEIN_SOLIDNESS = 0.7f
        private const val RICHENS_OVER = 0.2
        private const val LEAST_RICHNESS = 0.1
        private const val MOST_RICHNESS = 0.3
        private const val GAP_OFFSET = -0.3
        private const val VANILLA_RAW_ORE_CHANCE = 0.02f

        private const val VEININESS_SALT = 0x0E1_9E1AL
        private const val RIDGE_A_SALT = 0x0E1_9E1BL
        private const val RIDGE_B_SALT = 0x0E1_9E1CL
        private const val GAP_SALT = 0x0E1_9E1DL
    }
}
