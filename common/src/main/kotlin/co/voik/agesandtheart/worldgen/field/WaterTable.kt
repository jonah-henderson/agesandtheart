package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Aquifer
import net.minecraft.world.level.levelgen.DensityFunction
import java.lang.Math.floorDiv
import kotlin.math.roundToInt

/**
 * Whether a hollow opened underground is flooded or dry — an Age's aquifer, mirroring the shape of
 * vanilla's rather than inventing one, because the behaviour is subtler than "water below a line".
 *
 * The decision is **three-way**, not a water height:
 * - **The sea**, where a *floodedness* noise runs high. Near the surface of a submerged column this is
 *   nearly always the answer, which is what stops a carver hollowing out ground beneath an ocean and
 *   leaving air with water resting on it.
 * - **A perched pocket** at a local level, where floodedness is middling.
 * - **Bone dry** otherwise — and deep underground this is the *common* case.
 *
 * The two thresholds slide with depth. Just under a submerged surface they sit low, so water almost
 * always wins; [dryingDepth] blocks further down they rise, so only strongly flooded rock holds water.
 * Under *land* they start deep immediately — which is why caves below a hill run dry even when they are
 * beneath sea level. Without that third branch every cave below the water line floods, however the
 * levels are tuned; the dryness is a threshold, not a height.
 *
 * We can do one thing vanilla cannot: its surface comes from `preliminarySurfaceLevel`, an estimate off
 * the noise router, while ours is read from the field tree and is exact.
 *
 * The class is the serialisable *description*; [aquiferFor] mints the short-lived per-carve object that
 * answers queries, since that one carries state and must not be shared between chunk workers.
 */
data class WaterTable(
    val fluid: BlockState,
    val seaLevel: Int,
    /** Over how many blocks below a submerged surface the sea gives way to dry rock. */
    val dryingDepth: Int,
    /** How far beneath the surface the sea stops automatically winning. */
    val surfaceMargin: Int,
    /** Blocks per unit of noise: how broad a wet or dry region is. */
    val horizontalScale: Double,
    /** Vertical stretch, so wetness varies with depth as well as across the map. */
    val verticalScale: Double,
    val seed: Long,
    val firstOctave: Int,
    val amplitudes: List<Double>,
    /**
     * Whether everything below the waterline simply floods, with no dry pockets anywhere.
     *
     * What `flooded_caves` always claimed and never did: it returned *no* table, and the generator's
     * substitute for an absent one is the same wandering three-way table `caves` gets — so the pair
     * differed by a noise seed and nothing else, which a walk found and could not explain.
     *
     * A flag rather than a tuned set of thresholds because it is a different *claim*, not an extreme of the
     * same one: "everything down there is underwater" has no wet-and-dry distribution to describe.
     */
    val floods: Boolean = false,
) {
    private val floodedness = fieldNoise(seed, firstOctave, amplitudes)
    private val acrossStretch = horizontalScale.coerceAtLeast(SMALLEST_STRETCH)
    private val downStretch = verticalScale.coerceAtLeast(SMALLEST_STRETCH)

    /** A fresh aquifer for one carving pass; caches the column, whose surface is the costly part. */
    fun aquiferFor(field: TerrainField): Aquifer = ColumnAquifer(field)

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

    private inner class ColumnAquifer(private val field: TerrainField) : Aquifer {
        private var placedFluid = false
        private var columnX = Int.MIN_VALUE
        private var columnZ = Int.MIN_VALUE
        private var columnSurface = 0
        private var columnSubmerged = false

        override fun computeSubstance(context: DensityFunction.FunctionContext, substance: Double): BlockState? {
            // Positive means solid: nothing is being removed here, so the block stands as it is.
            if (substance > 0.0) return null
            val worldX = context.blockX()
            val worldY = context.blockY()
            val worldZ = context.blockZ()
            readColumn(worldX, worldZ)

            val wet = worldY < standingLevel(worldX, worldY, worldZ)
            placedFluid = wet
            return if (wet) fluid else AIR
        }

        private fun standingLevel(worldX: Int, worldY: Int, worldZ: Int): Int {
            // No thresholds to consult: a flooded table says the same thing everywhere.
            if (floods) return seaLevel
            // 1 just beneath a submerged surface, falling to 0 [dryingDepth] blocks down. Land columns
            // start at 0, so rock under a hill is judged by the deep thresholds straight away.
            val nearness = if (columnSubmerged) {
                val depth = (columnSurface + surfaceMargin - worldY).toDouble()
                (1.0 - depth / dryingDepth).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val wetness = floodedness
                .getValue(worldX / acrossStretch, worldY / downStretch, worldZ / acrossStretch)
                .coerceIn(-1.0, 1.0)
            return when {
                wetness > slide(nearness, SEA_WHEN_SHALLOW, SEA_WHEN_DEEP) -> seaLevel
                wetness > slide(nearness, PERCHED_WHEN_SHALLOW, PERCHED_WHEN_DEEP) -> perchedLevel(worldX, worldY, worldZ)
                else -> BONE_DRY
            }
        }

        /** A pocket's own level: a band of the world, nudged by noise, never above the ground. */
        private fun perchedLevel(worldX: Int, worldY: Int, worldZ: Int): Int {
            val band = floorDiv(worldY, PERCHED_BAND)
            val middle = band * PERCHED_BAND + PERCHED_BAND / 2
            val nudge = floodedness.getValue(
                floorDiv(worldX, PERCHED_CELL).toDouble(),
                band.toDouble(),
                floorDiv(worldZ, PERCHED_CELL).toDouble(),
            ) * PERCHED_SPREAD
            return minOf(columnSurface, middle + nudge.roundToInt())
        }

        private fun readColumn(worldX: Int, worldZ: Int) {
            if (worldX == columnX && worldZ == columnZ) return
            columnX = worldX
            columnZ = worldZ
            columnSurface = field.columnSpans(worldX, worldZ).highestSolidY ?: seaLevel
            columnSubmerged = columnSurface < seaLevel
        }

        /**
         * Carving only ever asks about the handful of blocks it is removing, so flagging every drop it
         * places is cheap — and it lets water settle wherever a wet pocket meets a dry one.
         */
        override fun shouldScheduleFluidUpdate(): Boolean = placedFluid
    }

    companion object {
        /**
         * Several tables, each answering for its own territory — **hydrology divides** (Jonah's call,
         * design §3.4, reversing an earlier decision).
         *
         * The old rule kept one table for the whole Age, on the grounds that a stepped water level would
         * read as a bug rather than as impossible geometry. Three things retired that argument: a table is a
         * *threshold* consulted only where something is being carved, not a height, so a division cannot
         * produce a cliff of water; the seam is a knife edge 85% of the time now, so the boundary is visible
         * in the rock and a flooded gallery ending at a sheared face reads as deliberate; and
         * [Aquifer.shouldScheduleFluidUpdate] already lets water settle where a wet pocket meets a dry one.
         *
         * Without this, `caves` beside `flooded_caves` divided into territories identical by construction —
         * the resolver believed it had split the world and the ground was uniform.
         */
        fun aquiferFor(tables: List<WaterTable>, field: TerrainField, territories: RegionMap): Aquifer =
            tables.singleOrNull()?.aquiferFor(field)
                ?: RegionalAquifer(tables.map { it.aquiferFor(field) }, territories)

        private val AIR: BlockState = Blocks.AIR.defaultBlockState()

        /** Nothing sits below this, so it reads as "no water in this rock at all". */
        private const val BONE_DRY = -4096

        // How flooded rock must be to hold water, just under a submerged surface versus far below it.
        // Shallow values are low so the sea nearly always wins; deep values are high so dry is normal.
        private const val SEA_WHEN_SHALLOW = -0.3
        private const val SEA_WHEN_DEEP = 0.8
        private const val PERCHED_WHEN_SHALLOW = -0.8
        private const val PERCHED_WHEN_DEEP = 0.4

        private const val PERCHED_BAND = 40
        private const val PERCHED_CELL = 16
        private const val PERCHED_SPREAD = 10.0

        private fun slide(nearness: Double, whenShallow: Double, whenDeep: Double) =
            whenDeep + (whenShallow - whenDeep) * nearness

        const val DEFAULT_DRYING_DEPTH = 64
        const val DEFAULT_SURFACE_MARGIN = 8

        /**
         * An Age's default: vanilla-shaped, seeded per Age so two Ages are not wet in the same places.
         *
         * Takes the medium's representative substance rather than asking per column, because a table is
         * one answer for the whole Age by design — see `AgeGeneration.waterTableOf`. So an Age whose sea
         * is water beside lava has water in its rock throughout, which is the sane reading: the
         * impossible part is meant to be the surface, not the groundwater.
         */
        fun matching(ambient: AmbientMedium, seaLevel: Int, seed: Long = 0L) = WaterTable(
            fluid = ambient.representative.takeUnless { it.isAir } ?: Blocks.WATER.defaultBlockState(),
            seaLevel = seaLevel,
            dryingDepth = DEFAULT_DRYING_DEPTH,
            surfaceMargin = DEFAULT_SURFACE_MARGIN,
            horizontalScale = 96.0,
            verticalScale = 64.0,
            seed = seed,
            firstOctave = -3,
            amplitudes = listOf(1.0, 1.0),
        )

        val CODEC: MapCodec<WaterTable> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                BlockState.CODEC.fieldOf("fluid").forGetter(WaterTable::fluid),
                Codec.INT.fieldOf("sea_level").forGetter(WaterTable::seaLevel),
                Codec.INT.fieldOf("drying_depth").forGetter(WaterTable::dryingDepth),
                Codec.INT.fieldOf("surface_margin").forGetter(WaterTable::surfaceMargin),
                Codec.DOUBLE.fieldOf("horizontal_scale").forGetter(WaterTable::horizontalScale),
                Codec.DOUBLE.fieldOf("vertical_scale").forGetter(WaterTable::verticalScale),
                Codec.LONG.fieldOf("seed").forGetter(WaterTable::seed),
                Codec.INT.fieldOf("first_octave").forGetter(WaterTable::firstOctave),
                Codec.DOUBLE.listOf().fieldOf("amplitudes").forGetter(WaterTable::amplitudes),
                Codec.BOOL.optionalFieldOf("floods", false).forGetter(WaterTable::floods),
            ).apply(instance, ::WaterTable)
        }
    }
}
