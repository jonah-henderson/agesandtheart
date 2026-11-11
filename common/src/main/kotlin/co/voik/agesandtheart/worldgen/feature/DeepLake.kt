package co.voik.agesandtheart.worldgen.feature

import java.util.Optional
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.LakeFeature
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Vanilla's lake with the water standing [waterLayers] deep rather than three: `deep lakes`, `shallow lakes`. The
 * bowl is vanilla's own, drawn from the same random, with its underwater layers stretched downward or trimmed from
 * the bottom; the four layers of air over the water and everything the lake lines, tests and freezes are vanilla's.
 * Unlike vanilla's, it gives up where it would hollow out under a tree a neighbouring chunk already grew.
 */
data class DeepLake(
    val fluid: Holder<BlockStateProvider>,
    val barrier: Holder<BlockStateProvider>,
    val canPlaceFeature: BlockPredicate,
    val canReplaceWithAirOrFluid: BlockPredicate,
    val canReplaceWithBarrier: BlockPredicate,
    val waterLayers: Int,
    /** Where it may stand against the sea — `high lakes` only up high, `low lakes` only near it — or null for anywhere. */
    val altitude: Double? = null,
) : Feature {

    override fun codec(): MapCodec<out Feature> = CODEC

    private val waterline = waterLayers + 1
    private val height = waterline + AIR_LAYERS

    private fun index(x: Int, z: Int, y: Int) = (x * WIDTH + z) * height + y

    /**
     * Whether a lake standing [aboveTheSea] blocks over the sea may stand there: anywhere without an [altitude]; above it,
     * at least that share of [HIGHEST_GROUND] up; below it, no further up than what is left of [LOW_GROUND].
     */
    private fun standsAtItsAltitude(aboveTheSea: Int): Boolean {
        val asked = altitude ?: return true
        return if (asked >= 0.0) aboveTheSea >= asked * HIGHEST_GROUND else aboveTheSea <= (1.0 + asked) * LOW_GROUND
    }

    // `liquid` and `isSolid` are deprecated, and vanilla's own lake still asks them.
    @Suppress("DEPRECATION")
    override fun place(level: WorldGenLevel, generator: ChunkGenerator, random: RandomSource, origin: BlockPos): Boolean {
        if (origin.y <= level.minY + waterline) return false
        if (!standsAtItsAltitude(origin.y - generator.seaLevel)) return false
        val corner = origin.offset(-WIDTH / 2, -waterline, -WIDTH / 2)
        val bowl = bowl(vanillaBowl(random), waterLayers)
        val fluidState = fluid.value().getState(level, random, corner)

        fun isWall(x: Int, z: Int, y: Int): Boolean {
            if (bowl[index(x, z, y)]) return false
            val beside = (x < WIDTH - 1 && bowl[index(x + 1, z, y)]) || (x > 0 && bowl[index(x - 1, z, y)]) ||
                (z < WIDTH - 1 && bowl[index(x, z + 1, y)]) || (z > 0 && bowl[index(x, z - 1, y)])
            val aboveOrBelow = (y < height - 1 && bowl[index(x, z, y + 1)]) || (y > 0 && bowl[index(x, z, y - 1)])
            return beside || aboveOrBelow
        }

        for (x in 0..<WIDTH) for (z in 0..<WIDTH) for (y in 0..<height) {
            if (!isWall(x, z, y)) continue
            val position = corner.offset(x, y, z)
            val state = level.getBlockState(position)
            val spillsOverTheTop = y >= waterline && state.liquid()
            val leaksBelow = y < waterline && !state.isSolid && state != fluidState
            if (spillsOverTheTop || leaksBelow || !canPlaceFeature.test(level, position)) return false
        }

        // A neighbour decorated first may have grown a tree where this bowl reaches, and hollowing under it would
        // leave it floating.
        for (x in 0..<WIDTH) for (z in 0..<WIDTH) for (y in 0..<height) {
            if (!bowl[index(x, z, y)]) continue
            val position = corner.offset(x, y, z)
            val hollowsATrunk = level.getBlockState(position).`is`(BlockTags.LOGS)
            val undercutsATrunk = level.getBlockState(position.above()).`is`(BlockTags.LOGS)
            if (hollowsATrunk || undercutsATrunk) return false
        }

        for (x in 0..<WIDTH) for (z in 0..<WIDTH) for (y in 0..<height) {
            if (!bowl[index(x, z, y)]) continue
            val position = corner.offset(x, y, z)
            if (!canReplaceWithAirOrFluid.test(level, position)) continue
            val isAir = y >= waterline
            level.setBlock(position, if (isAir) AIR else fluidState, PLACED_BY_WORLDGEN)
            if (isAir) {
                level.scheduleTick(position, AIR.block, 0)
                markAboveForPostProcessing(level, position)
            }
        }

        val barrierState = barrier.value().getState(level, random, corner)
        if (!barrierState.isAir) {
            for (x in 0..<WIDTH) for (z in 0..<WIDTH) for (y in 0..<height) {
                if (!isWall(x, z, y) || (y >= waterline && random.nextInt(2) == 0)) continue
                val position = corner.offset(x, y, z)
                if (level.getBlockState(position).isSolid && canReplaceWithBarrier.test(level, position)) {
                    level.setBlock(position, barrierState, PLACED_BY_WORLDGEN)
                    markAboveForPostProcessing(level, position)
                }
            }
        }

        if (fluidState.fluidState.`is`(FluidTags.WATER)) {
            for (x in 0..<WIDTH) for (z in 0..<WIDTH) {
                val position = corner.offset(x, waterline, z)
                val freezes = level.getBiome(position).value().shouldFreeze(level, position, false)
                if (freezes && canReplaceWithAirOrFluid.test(level, position)) {
                    level.setBlock(position, Blocks.ICE.defaultBlockState(), PLACED_BY_WORLDGEN)
                }
            }
        }
        return true
    }

    companion object {
        val CODEC: MapCodec<DeepLake> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BlockStateProvider.CODEC.fieldOf("fluid").forGetter(DeepLake::fluid),
                BlockStateProvider.CODEC.fieldOf("barrier").forGetter(DeepLake::barrier),
                BlockPredicate.CODEC.fieldOf("can_place_feature").forGetter(DeepLake::canPlaceFeature),
                BlockPredicate.CODEC.fieldOf("can_replace_with_air_or_fluid").forGetter(DeepLake::canReplaceWithAirOrFluid),
                BlockPredicate.CODEC.fieldOf("can_replace_with_barrier").forGetter(DeepLake::canReplaceWithBarrier),
                Codec.INT.fieldOf("water_layers").forGetter(DeepLake::waterLayers),
                Codec.DOUBLE.optionalFieldOf("altitude").forGetter { Optional.ofNullable(it.altitude) },
            ).apply(instance) { fluid, barrier, canPlace, canReplace, canBarrier, layers, altitude ->
                DeepLake(fluid, barrier, canPlace, canReplace, canBarrier, layers, altitude.orElse(null))
            }
        }

        /** [lake] holding its water [waterLayers] deep, standing at [altitude] where one is given. */
        @Suppress("DEPRECATION")
        fun of(lake: LakeFeature, waterLayers: Int, altitude: Double? = null) = DeepLake(
            lake.fluid(),
            lake.barrier(),
            lake.canPlaceFeature(),
            lake.canReplaceWithAirOrFluid(),
            lake.canReplaceWithBarrier(),
            waterLayers,
            altitude,
        )

        /** How many layers deep the water stands at [depth]: vanilla's three unsaid, one to [DEEPEST] at the ends. */
        fun waterLayersAt(depth: Double): Int {
            val toward = if (depth >= 0.0) DEEPEST.toDouble() / VANILLA_WATER_LAYERS else 1.0 / VANILLA_WATER_LAYERS
            return (VANILLA_WATER_LAYERS * toward.pow(abs(depth.coerceIn(-1.0, 1.0)))).roundToInt().coerceIn(1, DEEPEST)
        }

        /** Vanilla's bowl, drawn exactly as `LakeFeature` draws it: a 16 × 16 × 8 grid, indexed `(x * 16 + z) * 8 + y`. */
        fun vanillaBowl(random: RandomSource): BooleanArray {
            val grid = BooleanArray(WIDTH * WIDTH * VANILLA_HEIGHT)
            repeat(random.nextInt(4) + 4) {
                val xRadius = random.nextDouble() * 6.0 + 3.0
                val yRadius = random.nextDouble() * 4.0 + 2.0
                val zRadius = random.nextDouble() * 6.0 + 3.0
                val xCentre = random.nextDouble() * (16.0 - xRadius - 2.0) + 1.0 + xRadius / 2.0
                val yCentre = random.nextDouble() * (8.0 - yRadius - 4.0) + 2.0 + yRadius / 2.0
                val zCentre = random.nextDouble() * (16.0 - zRadius - 2.0) + 1.0 + zRadius / 2.0
                for (x in 1..<15) for (z in 1..<15) for (y in 1..<7) {
                    val xd = (x - xCentre) / (xRadius / 2.0)
                    val yd = (y - yCentre) / (yRadius / 2.0)
                    val zd = (z - zCentre) / (zRadius / 2.0)
                    if (xd * xd + yd * yd + zd * zd < 1.0) grid[(x * WIDTH + z) * VANILLA_HEIGHT + y] = true
                }
            }
            return grid
        }

        /**
         * [vanilla]'s bowl with [waterLayers] layers under its waterline: its three stretched over more, or its
         * widest kept where fewer. The empty bottom layer and the four of air keep their places.
         */
        fun bowl(vanilla: BooleanArray, waterLayers: Int): BooleanArray {
            val height = waterLayers + 1 + AIR_LAYERS
            fun vanillaLayer(y: Int): Int = when {
                y == 0 -> 0
                y > waterLayers -> VANILLA_WATERLINE + (y - waterLayers - 1)
                waterLayers <= VANILLA_WATER_LAYERS -> y + VANILLA_WATER_LAYERS - waterLayers
                else -> 1 + (y - 1) * VANILLA_WATER_LAYERS / waterLayers
            }
            val grid = BooleanArray(WIDTH * WIDTH * height)
            for (x in 0..<WIDTH) for (z in 0..<WIDTH) for (y in 0..<height) {
                grid[(x * WIDTH + z) * height + y] = vanilla[(x * WIDTH + z) * VANILLA_HEIGHT + vanillaLayer(y)]
            }
            return grid
        }

        /** How far over the sea a lake at an altitude of one must stand at the least. */
        private const val HIGHEST_GROUND = 48

        /** How far over the sea a lake at an altitude of nought may stand at the most, before it is pulled lower. */
        private const val LOW_GROUND = 32

        private const val WIDTH = 16
        private const val VANILLA_HEIGHT = 8
        private const val VANILLA_WATERLINE = 4
        const val VANILLA_WATER_LAYERS = VANILLA_WATERLINE - 1
        private const val AIR_LAYERS = VANILLA_HEIGHT - VANILLA_WATERLINE

        /** The most layers of water a lake holds, at `depth` 1. */
        const val DEEPEST = 16

        private val AIR: BlockState = Blocks.CAVE_AIR.defaultBlockState()

        /** Vanilla's own flag for a block a feature lays: change it, and do not tell a neighbour. */
        private const val PLACED_BY_WORLDGEN = 2
    }
}
