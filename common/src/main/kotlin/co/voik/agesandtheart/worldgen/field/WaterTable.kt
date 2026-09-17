package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.levelgen.Aquifer
import net.minecraft.world.level.levelgen.DensityFunction
import net.minecraft.world.level.levelgen.PositionalRandomFactory
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import java.lang.Math.floorDiv
import kotlin.math.abs

/**
 * Whether a hollow opened underground holds water, lava or air — **vanilla's `NoiseBasedAquifer`, ported**,
 * with the few inputs a field Age has to supply for itself.
 *
 * Vanilla's model as it stands: a jittered grid of points, [CELL_WIDTH] × [CELL_HEIGHT] × [CELL_WIDTH], each
 * holding one [Aquifer.FluidStatus]. A block takes the status of the nearest point, and where it is nearly as
 * near a second point whose status differs, rock is left between them — a barrier, strong where the pressure
 * between the two levels is high and weak where the cave is wide open. So a pool is the bottom of one point's
 * region, bounded by the inclined faces it shares with its neighbours: a bowl rather than a column. A point's
 * status is the sea where the ground near it lies under the sea, and otherwise flooded to the sea, perched at a
 * level of its own, or dry, by a floodedness noise against thresholds that slide with the point's depth.
 *
 * What a field Age answers for itself, each where vanilla reads something we do not have:
 * - **The surface** is [surfaceAt] — the generator's own preliminary surface, the rock before its caves were
 *   cut, which is what vanilla's is.
 * - **How open a cave is** — vanilla reads its terrain density; [opennessAt] measures the distance to rock.
 * - **Deep dark**, which vanilla never floods, is asked of the biome source ([isDeepDark]) rather than of
 *   vanilla's erosion and depth, since our depth is our own.
 *
 * And what is ours by design: water the shape pours — rivers, lakes, a caldera's lava ([SeaFill.wet],
 * [SeaFill.carried]) — answers before any of this, and [floods] is the preset that drowns everything below the
 * waterline, which is vanilla's aquifer switched off.
 *
 * The class is the serialisable *description*; [aquiferFor] mints the short-lived per-pass object, which
 * carries caches and must not be shared between chunk workers.
 */
data class WaterTable(
    val fluid: BlockState,
    val seaLevel: Int,
    val seed: Long,
    /**
     * Whether everything below the waterline simply floods, with no dry pockets anywhere — a different *claim*
     * rather than an extreme of the same one, and exactly vanilla's aquifer with aquifers disabled.
     */
    val floods: Boolean = false,
) {
    // Vanilla's four aquifer noises, at vanilla's octaves, each on its own seed from this table's.
    private val floodedness = fieldNoise(seed xor FLOODEDNESS_SALT, FLOODEDNESS_FIRST_OCTAVE, listOf(1.0))
    private val spread = fieldNoise(seed xor SPREAD_SALT, SPREAD_FIRST_OCTAVE, listOf(1.0))
    private val lavaPockets = fieldNoise(seed xor LAVA_SALT, LAVA_FIRST_OCTAVE, listOf(1.0))
    private val barrier = fieldNoise(seed xor BARRIER_SALT, BARRIER_FIRST_OCTAVE, listOf(1.0))

    /** Where each cell's point sits inside it — vanilla's positional random, per cell. */
    private val cellRandom: PositionalRandomFactory = XoroshiroRandomSource(seed xor CELL_SALT).forkPositional()

    /** The preliminary surface at a column — how high its ground stood before its caves were cut. */
    fun interface SurfaceAt {
        fun surfaceAt(worldX: Int, worldZ: Int): Int
    }

    /** Whether a point lies in deep dark, which vanilla's aquifer never floods. */
    fun interface DeepDarkAt {
        fun isDeepDark(worldX: Int, worldY: Int, worldZ: Int): Boolean
    }

    /**
     * A fresh aquifer for one pass: it caches cells and columns, and must not be shared between workers.
     *
     * [seaFill] is asked only for what the shape pours for itself — its water answers before the cells and raises
     * the sea's level over the columns it stands in, so a carver under a river finds the river, and its other
     * bodies answer with what they are made of, so one cutting into a caldera finds lava.
     */
    fun aquiferFor(field: TerrainField, surfaceAt: SurfaceAt, isDeepDark: DeepDarkAt?, seaFill: SeaFill): Aquifer =
        CellAquifer(field, surfaceAt, isDeepDark, seaFill)

    /**
     * One aquifer per territory, asked whichever owns the column being carved.
     *
     * [shouldScheduleFluidUpdate] forwards to whichever was last consulted rather than answering for itself,
     * because vanilla asks it immediately after each [Aquifer.computeSubstance] and means "did *that* call
     * place a drop" — answering for the wrong delegate would either leave water hanging unsettled or
     * schedule ticks for blocks nobody placed.
     */
    private class RegionalAquifer(private val byTerritory: List<Aquifer>, private val territories: RegionMap) : Aquifer {
        private var lastAsked: Aquifer = byTerritory.first()

        override fun computeSubstance(context: DensityFunction.FunctionContext, substance: Double): BlockState? {
            val here = byTerritory[territories.memberAt(context.blockX(), context.blockZ()).coerceIn(byTerritory.indices)]
            lastAsked = here
            return here.computeSubstance(context, substance)
        }

        override fun shouldScheduleFluidUpdate(): Boolean = lastAsked.shouldScheduleFluidUpdate()
    }

    private inner class CellAquifer(
        private val field: TerrainField,
        private val surfaceAt: SurfaceAt,
        private val isDeepDark: DeepDarkAt?,
        private val seaFill: SeaFill,
    ) : Aquifer {
        private var scheduleFluidUpdate = false

        /** Every column this pass has read — cells sample the surface up to three chunks from their point. */
        private val columns = Long2ObjectOpenHashMap<Column>()

        /** Each cell's point, packed as a block position; vanilla's `aquiferLocationCache`. */
        private val points = Long2LongOpenHashMap().apply { defaultReturnValue(NO_POINT) }

        /** Each cell's status; vanilla's `aquiferCache`. */
        private val statuses = Long2ObjectOpenHashMap<Aquifer.FluidStatus>()

        /** The barrier noise at the block being asked about, sampled at most once for it. */
        private var barrierHere = Double.NaN

        override fun computeSubstance(context: DensityFunction.FunctionContext, density: Double): BlockState? {
            scheduleFluidUpdate = false
            // Positive means solid: nothing is being removed here, so the block stands as it is.
            if (density > 0.0) return null
            val worldX = context.blockX()
            val worldY = context.blockY()
            val worldZ = context.blockZ()
            val column = columnAt(worldX, worldZ)
            pouredAt(column, worldY)?.let { return it }
            val sea = globalFluidAt(worldX, worldY, worldZ).at(worldY)
            if (floods || sea.`is`(Blocks.LAVA)) return sea
            // The fill hands every hollow the same number; how open the cave is here is what vanilla's density
            // says, and a carver cutting rock passes vanilla's own zero.
            val substance = if (column.isOpen(worldY)) opennessAt(column, worldX, worldY, worldZ) else density
            barrierHere = Double.NaN
            return fromTheNearestCells(worldX, worldY, worldZ, substance)
        }

        override fun shouldScheduleFluidUpdate(): Boolean = scheduleFluidUpdate

        /**
         * **Water the shape poured is water, and no threshold gets a vote.** A carver cutting into a river or
         * into a chamber's lake must find it: this is a body of water somebody can see, not groundwater. A body
         * made of something else answers first, and answers with what it is made of.
         */
        private fun pouredAt(column: Column, worldY: Int): BlockState? =
            seaFill.carriedAt(worldY, column.carriedSpans) ?: if (column.standingSpans.contains(worldY)) fluid else null

        /** Vanilla's `computeSubstance` from its cell search onward, line for line. */
        private fun fromTheNearestCells(worldX: Int, worldY: Int, worldZ: Int, substance: Double): BlockState? {
            val anchorX = floorDiv(worldX + SAMPLE_OFFSET_X, CELL_WIDTH)
            val anchorY = floorDiv(worldY + SAMPLE_OFFSET_Y, CELL_HEIGHT)
            val anchorZ = floorDiv(worldZ + SAMPLE_OFFSET_Z, CELL_WIDTH)
            var nearest = Int.MAX_VALUE
            var second = Int.MAX_VALUE
            var third = Int.MAX_VALUE
            var fourth = Int.MAX_VALUE
            var nearestCell = 0L
            var secondCell = 0L
            var thirdCell = 0L
            var fourthCell = 0L
            for (cellX in anchorX..anchorX + 1) {
                for (cellY in anchorY - 1..anchorY + 1) {
                    for (cellZ in anchorZ..anchorZ + 1) {
                        val cell = BlockPos.asLong(cellX, cellY, cellZ)
                        val point = pointOf(cell, cellX, cellY, cellZ)
                        val towardsX = BlockPos.getX(point) - worldX
                        val towardsY = BlockPos.getY(point) - worldY
                        val towardsZ = BlockPos.getZ(point) - worldZ
                        val distance = towardsX * towardsX + towardsY * towardsY + towardsZ * towardsZ
                        // Ties go to the later cell, as vanilla's `>=` sends them.
                        when {
                            nearest >= distance -> {
                                fourth = third; fourthCell = thirdCell
                                third = second; thirdCell = secondCell
                                second = nearest; secondCell = nearestCell
                                nearest = distance; nearestCell = cell
                            }
                            second >= distance -> {
                                fourth = third; fourthCell = thirdCell
                                third = second; thirdCell = secondCell
                                second = distance; secondCell = cell
                            }
                            third >= distance -> {
                                fourth = third; fourthCell = thirdCell
                                third = distance; thirdCell = cell
                            }
                            fourth >= distance -> {
                                fourth = distance; fourthCell = cell
                            }
                        }
                    }
                }
            }

            val first = statusOf(nearestCell)
            val firstAndSecond = similarity(nearest, second)
            val here = first.at(worldY)
            // Nowhere near a second cell: the nearest one's water, told to move only where it borders another.
            if (firstAndSecond <= 0.0) {
                scheduleFluidUpdate = firstAndSecond >= FLOWING_UPDATE_SIMILARITY && first != statusOf(secondCell)
                return here
            }
            // Water over the lava sea at the bottom of the world is left to meet it, which makes obsidian.
            val lavaBeneath = globalFluidAt(worldX, worldY - 1, worldZ).at(worldY - 1).`is`(Blocks.LAVA)
            if (here.`is`(Blocks.WATER) && lavaBeneath) {
                scheduleFluidUpdate = true
                return here
            }
            val secondStatus = statusOf(secondCell)
            if (substance + firstAndSecond * pressure(worldX, worldY, worldZ, first, secondStatus) > 0.0) return null
            val thirdStatus = statusOf(thirdCell)
            val firstAndThird = similarity(nearest, third)
            if (firstAndThird > 0.0) {
                val between = firstAndSecond * firstAndThird * pressure(worldX, worldY, worldZ, first, thirdStatus)
                if (substance + between > 0.0) return null
            }
            val secondAndThird = similarity(second, third)
            if (secondAndThird > 0.0) {
                val between = firstAndSecond * secondAndThird * pressure(worldX, worldY, worldZ, secondStatus, thirdStatus)
                if (substance + between > 0.0) return null
            }
            val firstMeetsSecond = first != secondStatus
            val secondMeetsThird = secondAndThird >= FLOWING_UPDATE_SIMILARITY && secondStatus != thirdStatus
            val firstMeetsThird = firstAndThird >= FLOWING_UPDATE_SIMILARITY && first != thirdStatus
            scheduleFluidUpdate = if (firstMeetsSecond || secondMeetsThird || firstMeetsThird) {
                true
            } else {
                val fourthIsNear = firstAndThird >= FLOWING_UPDATE_SIMILARITY && similarity(nearest, fourth) >= FLOWING_UPDATE_SIMILARITY
                fourthIsNear && first != statusOf(fourthCell)
            }
            return here
        }

        /** Vanilla's `calculatePressure`: how hard two statuses push on the rock between them, at this height. */
        private fun pressure(
            worldX: Int,
            worldY: Int,
            worldZ: Int,
            one: Aquifer.FluidStatus,
            other: Aquifer.FluidStatus,
        ): Double {
            val oneHere = one.at(worldY)
            val otherHere = other.at(worldY)
            val lavaMeetsWater = (oneHere.`is`(Blocks.LAVA) && otherHere.`is`(Blocks.WATER)) ||
                (oneHere.`is`(Blocks.WATER) && otherHere.`is`(Blocks.LAVA))
            if (lavaMeetsWater) return LAVA_MEETS_WATER_PRESSURE
            val levelGap = abs(one.fluidLevel - other.fluidLevel)
            if (levelGap == 0) return 0.0
            val averageLevel = 0.5 * (one.fluidLevel + other.fluidLevel)
            val aboveTheAverage = worldY + 0.5 - averageLevel
            val towardsTheMiddle = levelGap / 2.0 - abs(aboveTheAverage)
            val gradient = if (aboveTheAverage > 0.0) {
                val centre = towardsTheMiddle
                if (centre > 0.0) centre / ABOVE_INSIDE_FALLOFF else centre / ABOVE_OUTSIDE_FALLOFF
            } else {
                val centre = BELOW_BIAS + towardsTheMiddle
                if (centre > 0.0) centre / BELOW_INSIDE_FALLOFF else centre / BELOW_OUTSIDE_FALLOFF
            }
            val noise = if (gradient < -NOISY_GRADIENT || gradient > NOISY_GRADIENT) 0.0 else barrierNoiseAt(worldX, worldY, worldZ)
            return PRESSURE_AMPLITUDE * (noise + gradient)
        }

        private fun barrierNoiseAt(worldX: Int, worldY: Int, worldZ: Int): Double {
            if (barrierHere.isNaN()) barrierHere = barrier.getValue(worldX.toDouble(), worldY * BARRIER_Y_SCALE, worldZ.toDouble())
            return barrierHere
        }

        /** Where a cell's point sits: `nextInt(10)`, `nextInt(9)`, `nextInt(10)` into the cell, as vanilla draws it. */
        private fun pointOf(cell: Long, cellX: Int, cellY: Int, cellZ: Int): Long {
            val known = points.get(cell)
            if (known != NO_POINT) return known
            val random = cellRandom.at(cellX, cellY, cellZ)
            val pointX = cellX * CELL_WIDTH + random.nextInt(JITTER_ACROSS)
            val pointY = cellY * CELL_HEIGHT + random.nextInt(JITTER_UP)
            val pointZ = cellZ * CELL_WIDTH + random.nextInt(JITTER_ACROSS)
            return BlockPos.asLong(pointX, pointY, pointZ).also { points.put(cell, it) }
        }

        private fun statusOf(cell: Long): Aquifer.FluidStatus {
            statuses.get(cell)?.let { return it }
            val point = points.get(cell)
            return statusAt(BlockPos.getX(point), BlockPos.getY(point), BlockPos.getZ(point)).also { statuses.put(cell, it) }
        }

        /**
         * Vanilla's `computeFluid` for one cell's point: the sea if the ground near it lies under the sea,
         * otherwise a level of its own. The surface is read at thirteen columns up to three chunks off, and the
         * lowest of them caps any level the point stands at.
         */
        private fun statusAt(pointX: Int, pointY: Int, pointZ: Int): Aquifer.FluidStatus {
            val sea = globalFluidAt(pointX, pointY, pointZ)
            var lowestSurface = Int.MAX_VALUE
            val cellTop = pointY + CELL_HEIGHT
            val cellBottom = pointY - CELL_HEIGHT
            var surfaceHereIsUnderTheSea = false
            for (sample in SURFACE_SAMPLES) {
                val sampleX = pointX + sample.first * CHUNK_WIDTH
                val sampleZ = pointZ + sample.second * CHUNK_WIDTH
                val surface = columnAt(sampleX, sampleZ).surface
                val raisedSurface = surface + SURFACE_MARGIN
                val isThePointsOwn = sample.first == 0 && sample.second == 0
                if (isThePointsOwn && cellBottom > raisedSurface) return sea
                val cellReachesAboveIt = cellTop > raisedSurface
                if (cellReachesAboveIt || isThePointsOwn) {
                    val seaThere = globalFluidAt(sampleX, raisedSurface, sampleZ)
                    if (!seaThere.at(raisedSurface).isAir) {
                        if (isThePointsOwn) surfaceHereIsUnderTheSea = true
                        if (cellReachesAboveIt) return seaThere
                    }
                }
                lowestSurface = minOf(lowestSurface, surface)
            }
            val level = levelAt(pointX, pointY, pointZ, sea, lowestSurface, surfaceHereIsUnderTheSea)
            return Aquifer.FluidStatus(level, fluidTypeAt(pointX, pointY, pointZ, sea, level))
        }

        /** Vanilla's `computeSurfaceLevel`: flooded to the sea, perched at a level of its own, or dry. */
        private fun levelAt(
            pointX: Int,
            pointY: Int,
            pointZ: Int,
            sea: Aquifer.FluidStatus,
            lowestSurface: Int,
            underTheSea: Boolean,
        ): Int {
            if (isDeepDark?.isDeepDark(pointX, pointY, pointZ) == true) return DimensionType.WAY_BELOW_MIN_Y
            val belowTheSurface = (lowestSurface + SURFACE_MARGIN - pointY).toDouble()
            val nearness = if (underTheSea) Mth.clampedMap(belowTheSurface, 0.0, FLOODEDNESS_MAX_DEPTH, 1.0, 0.0) else 0.0
            val wetness = Mth.clamp(
                floodedness.getValue(pointX.toDouble(), pointY * FLOODEDNESS_Y_SCALE, pointZ.toDouble()),
                -1.0,
                1.0,
            )
            val fullyFloodedAbove = Mth.map(nearness, 1.0, 0.0, FULLY_FLOODED_WHEN_SHALLOW, FULLY_FLOODED_WHEN_DEEP)
            val partlyFloodedAbove = Mth.map(nearness, 1.0, 0.0, PARTLY_FLOODED_WHEN_SHALLOW, PARTLY_FLOODED_WHEN_DEEP)
            return when {
                wetness > fullyFloodedAbove -> sea.fluidLevel
                wetness > partlyFloodedAbove -> perchedLevelAt(pointX, pointY, pointZ, lowestSurface)
                else -> DimensionType.WAY_BELOW_MIN_Y
            }
        }

        /** Vanilla's `computeRandomizedFluidSurfaceLevel`: the middle of a forty-block band, spread by noise. */
        private fun perchedLevelAt(pointX: Int, pointY: Int, pointZ: Int, lowestSurface: Int): Int {
            val cellX = floorDiv(pointX, LEVEL_CELL_WIDTH)
            val cellY = floorDiv(pointY, LEVEL_CELL_HEIGHT)
            val cellZ = floorDiv(pointZ, LEVEL_CELL_WIDTH)
            val middle = cellY * LEVEL_CELL_HEIGHT + LEVEL_CELL_HEIGHT / 2
            val spreadHere = spread.getValue(cellX.toDouble(), cellY * SPREAD_Y_SCALE, cellZ.toDouble()) * LEVEL_SPREAD
            return minOf(lowestSurface, middle + Mth.quantize(spreadHere, LEVEL_STEP))
        }

        /** Vanilla's `computeFluidType`: deep perched water is lava in pockets. */
        private fun fluidTypeAt(pointX: Int, pointY: Int, pointZ: Int, sea: Aquifer.FluidStatus, level: Int): BlockState {
            val deepEnough = level <= LAVA_POCKETS_AT_OR_BELOW && level != DimensionType.WAY_BELOW_MIN_Y
            if (!deepEnough || sea.fluidType == LAVA) return sea.fluidType
            val pocket = lavaPockets.getValue(
                floorDiv(pointX, LAVA_CELL_WIDTH).toDouble(),
                floorDiv(pointY, LAVA_CELL_HEIGHT).toDouble(),
                floorDiv(pointZ, LAVA_CELL_WIDTH).toDouble(),
            )
            return if (abs(pocket) > LAVA_POCKET_THRESHOLD) LAVA else sea.fluidType
        }

        /**
         * Vanilla's fluid picker: lava below [LAVA_SEA_LEVEL], the sea below its level above that. Raised over a
         * column to the water the shape carries there, which is how a river bed counts as under water.
         */
        private fun globalFluidAt(worldX: Int, worldY: Int, worldZ: Int): Aquifer.FluidStatus =
            if (worldY < minOf(LAVA_SEA_LEVEL, seaLevel)) LAVA_SEA else columnAt(worldX, worldZ).sea

        /**
         * How far inside open space this point is, as a density: a block off rock is just under zero, and it
         * falls by [OPENNESS_PER_BLOCK] a block to [OPENNESS_REACH] blocks off. Vanilla reads its terrain density
         * here, which runs the same way — a barrier stands easily against a cave's wall and only under pressure
         * across the open middle of one.
         */
        private fun opennessAt(column: Column, worldX: Int, worldY: Int, worldZ: Int): Double {
            var toRock = OPENNESS_REACH
            for (step in 1..<OPENNESS_REACH) {
                if (!column.isOpen(worldY + step) || !column.isOpen(worldY - step)) {
                    toRock = step
                    break
                }
            }
            for ((acrossX, acrossZ) in HORIZONTALLY) {
                for (step in 1..<toRock) {
                    if (!columnAt(worldX + acrossX * step, worldZ + acrossZ * step).isOpen(worldY)) {
                        toRock = step
                        break
                    }
                }
            }
            return -toRock * OPENNESS_PER_BLOCK
        }

        private fun similarity(nearer: Int, further: Int): Double = 1.0 - (further - nearer) / SIMILARITY_RANGE

        private fun columnAt(worldX: Int, worldZ: Int): Column {
            val key = (worldX.toLong() shl Int.SIZE_BITS) or (worldZ.toLong() and 0xFFFF_FFFFL)
            return columns.get(key) ?: Column(worldX, worldZ).also { columns.put(key, it) }
        }

        /** One column, each part read only if something asks: most columns a pass touches are surface samples. */
        private inner class Column(val x: Int, val z: Int) {
            val rock: Spans by lazy(LazyThreadSafetyMode.NONE) { field.columnSpans(x, z) }

            val surface: Int by lazy(LazyThreadSafetyMode.NONE) { surfaceAt.surfaceAt(x, z) }

            /** Where this column's own water actually stands, rather than how high it reaches. */
            val standingSpans: Spans by lazy(LazyThreadSafetyMode.NONE) { seaFill.wetnessAt(x, z) }

            /** And where each body of something else stands, in [SeaFill.carried]'s own order. */
            val carriedSpans: List<Spans> by lazy(LazyThreadSafetyMode.NONE) { seaFill.carriedAt(x, z) }

            /** The sea over this column: the waterline, or higher where the shape carries water of its own. */
            val sea: Aquifer.FluidStatus by lazy(LazyThreadSafetyMode.NONE) {
                val carriedTop = standingSpans.highestSolidY?.plus(1) ?: seaLevel
                Aquifer.FluidStatus(maxOf(seaLevel, carriedTop), fluid)
            }

            fun isOpen(worldY: Int): Boolean = !rock.contains(worldY)
        }
    }

    companion object {
        /**
         * Several tables, each answering for its own territory — **hydrology divides** (design §3.4).
         *
         * A division cannot produce a cliff of water, because a table is a *threshold* consulted only
         * where something is carved rather than a height; the seam is visible in the rock anyway; and
         * [Aquifer.shouldScheduleFluidUpdate] lets water settle where a wet pocket meets a dry one.
         *
         * Without this, `caves` beside `flooded_caves` divided into territories identical by construction.
         */
        fun aquiferFor(
            tables: List<WaterTable>,
            field: TerrainField,
            surfaceAt: SurfaceAt,
            isDeepDark: DeepDarkAt?,
            seaFill: SeaFill,
            territories: RegionMap,
        ): Aquifer = tables.singleOrNull()?.aquiferFor(field, surfaceAt, isDeepDark, seaFill)
            ?: RegionalAquifer(tables.map { it.aquiferFor(field, surfaceAt, isDeepDark, seaFill) }, territories)

        private val LAVA: BlockState = Blocks.LAVA.defaultBlockState()

        // Vanilla's lava sea: below y -54 every aquifer answers lava.
        private const val LAVA_SEA_LEVEL = -54
        private val LAVA_SEA = Aquifer.FluidStatus(LAVA_SEA_LEVEL, LAVA)

        // The cell grid: one point per 16 x 12 x 16, drawn up to 10, 9 and 10 blocks into its cell, and the
        // neighbourhood searched from a block offset by vanilla's -5, +1, -5.
        private const val CELL_WIDTH = 16
        private const val CELL_HEIGHT = 12
        private const val JITTER_ACROSS = 10
        private const val JITTER_UP = 9
        private const val SAMPLE_OFFSET_X = -5
        private const val SAMPLE_OFFSET_Y = 1
        private const val SAMPLE_OFFSET_Z = -5
        private const val NO_POINT = Long.MAX_VALUE

        /** Two points closer than this in squared distance are near enough for rock to stand between them. */
        private const val SIMILARITY_RANGE = 25.0

        /** Vanilla's `FLOWING_UPDATE_SIMULARITY`: the similarity of points 10 and 12 blocks off. */
        private const val FLOWING_UPDATE_SIMILARITY = 1.0 - (12 * 12 - 10 * 10) / SIMILARITY_RANGE

        // The thirteen surface samples around a point, in chunks: its own column, and a spread to the west.
        private val SURFACE_SAMPLES = listOf(
            0 to 0, -2 to -1, -1 to -1, 0 to -1, 1 to -1, -3 to 0, -2 to 0, -1 to 0, 1 to 0,
            -2 to 1, -1 to 1, 0 to 1, 1 to 1,
        )
        private const val CHUNK_WIDTH = 16

        /** Vanilla's `adjustSurfaceLevel`: the surface is taken eight blocks higher than the preliminary one. */
        private const val SURFACE_MARGIN = 8

        /** How far below a submerged surface the sea's thresholds slide from shallow to deep. */
        private const val FLOODEDNESS_MAX_DEPTH = 64.0

        // Vanilla's thresholds, just under a submerged surface and far below one or under land.
        private const val FULLY_FLOODED_WHEN_SHALLOW = -0.3
        private const val FULLY_FLOODED_WHEN_DEEP = 0.8
        private const val PARTLY_FLOODED_WHEN_SHALLOW = -0.8
        private const val PARTLY_FLOODED_WHEN_DEEP = 0.4

        // A perched level: the middle of a forty-block band, spread by up to ten and stepped by three.
        private const val LEVEL_CELL_WIDTH = 16
        private const val LEVEL_CELL_HEIGHT = 40
        private const val LEVEL_SPREAD = 10.0
        private const val LEVEL_STEP = 3

        // Deep perched water turns to lava where a coarse noise runs far from zero.
        private const val LAVA_CELL_WIDTH = 64
        private const val LAVA_CELL_HEIGHT = 40
        private const val LAVA_POCKETS_AT_OR_BELOW = -10
        private const val LAVA_POCKET_THRESHOLD = 0.3

        // Vanilla's `calculatePressure`, number for number.
        private const val LAVA_MEETS_WATER_PRESSURE = 2.0
        private const val ABOVE_INSIDE_FALLOFF = 1.5
        private const val ABOVE_OUTSIDE_FALLOFF = 2.5
        private const val BELOW_BIAS = 3.0
        private const val BELOW_INSIDE_FALLOFF = 3.0
        private const val BELOW_OUTSIDE_FALLOFF = 10.0
        private const val NOISY_GRADIENT = 2.0
        private const val PRESSURE_AMPLITUDE = 2.0

        // How a cave's openness stands in for vanilla's density: see `CellAquifer.opennessAt`.
        private const val OPENNESS_REACH = 8
        private const val OPENNESS_PER_BLOCK = 0.1
        private val HORIZONTALLY = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

        // Vanilla's aquifer noises: octaves as `NoiseData` registers them, vertical scales as the router reads them.
        private const val FLOODEDNESS_FIRST_OCTAVE = -7
        private const val SPREAD_FIRST_OCTAVE = -5
        private const val LAVA_FIRST_OCTAVE = -1
        private const val BARRIER_FIRST_OCTAVE = -3
        private const val FLOODEDNESS_Y_SCALE = 0.67
        private const val SPREAD_Y_SCALE = 0.7142857142857143
        private const val BARRIER_Y_SCALE = 0.5

        // Seeds separating the noises and the cell points from one another.
        private const val FLOODEDNESS_SALT = 0x3C6E_F372_FE94_F82BL
        private const val SPREAD_SALT = 0x5851_F42D_4C95_7F2DL
        private const val LAVA_SALT = 0x14057B7E_F767_814FL
        private const val BARRIER_SALT = 0x6A09_E667_F3BC_C909L
        private const val CELL_SALT = 0x2545_F491_4F6C_DD1DL

        /**
         * An Age's default: vanilla's aquifer, seeded per Age so two Ages are not wet in the same places.
         * Takes the sea's *representative* substance rather than asking per column, so an Age whose sea is
         * water beside lava has water in its rock throughout — the impossible part is meant to be the surface,
         * not the groundwater.
         */
        fun matching(seaFill: SeaFill, seaLevel: Int, seed: Long = 0L) = WaterTable(
            fluid = seaFill.representative.takeUnless { it.isAir } ?: Blocks.WATER.defaultBlockState(),
            seaLevel = seaLevel,
            seed = seed,
        )

        val CODEC: MapCodec<WaterTable> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BlockState.CODEC.fieldOf("fluid").forGetter(WaterTable::fluid),
                Codec.INT.fieldOf("sea_level").forGetter(WaterTable::seaLevel),
                Codec.LONG.fieldOf("seed").forGetter(WaterTable::seed),
                Codec.BOOL.optionalFieldOf("floods", false).forGetter(WaterTable::floods),
            ).apply(instance, ::WaterTable)
        }
    }
}
