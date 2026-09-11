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
 * The decision is **three-way, not a water height**: the sea where a *floodedness* noise runs high, a
 * perched pocket where it is middling, bone dry otherwise — and deep underground dry is the common case.
 *
 * The two thresholds slide with depth: low just under a submerged surface so water almost always wins,
 * rising [dryingDepth] blocks down so only strongly flooded rock holds water, and starting deep under
 * *land*, which is why caves below a hill run dry even beneath sea level. **Without that third branch
 * every cave below the waterline floods however the levels are tuned** — dryness is a threshold, not a
 * height.
 *
 * The class is the serialisable *description*; [aquiferFor] mints the short-lived per-carve object, which
 * carries state and must not be shared between chunk workers.
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
     * Whether everything below the waterline simply floods, with no dry pockets anywhere. A flag rather
     * than a tuned set of thresholds, because it is a different *claim* and not an extreme of the same
     * one: "everything down there is underwater" has no wet-and-dry distribution to describe.
     */
    val floods: Boolean = false,
    /**
     * Water the shape carries itself — the same field `SeaFill.wet` pours. A column under a river is
     * **submerged** too, however far over the waterline its bed lies, and a carver cutting into one must
     * find water rather than air or it opens a hole in the river.
     */
    val standing: TerrainField? = null,
    /**
     * Bodies the shape carries that are made of something else — the same [StandingFluid]s [SeaFill]
     * fills, so a carver cutting into a caldera's lava lake finds lava rather than a hole in it.
     */
    val carried: List<StandingFluid> = emptyList(),
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

        /**
         * What water stands over this column — the sea, or higher where the shape carries its own.
         *
         * **The level has to travel with the branch.** Deciding that a river bed is submerged and then
         * answering with the *sea's* height leaves every block between the two dry, which is the same hole
         * by a longer road.
         */
        private var columnWaterY = 0

        /** Where this column's own water actually stands, rather than how high it reaches — see below. */
        private var columnStanding: Spans? = null

        /** And where each body of something else stands, in [carried]'s own order. */
        private var columnCarried: List<Spans> = emptyList()

        override fun computeSubstance(context: DensityFunction.FunctionContext, substance: Double): BlockState? {
            // Positive means solid: nothing is being removed here, so the block stands as it is.
            if (substance > 0.0) return null
            val worldX = context.blockX()
            val worldY = context.blockY()
            val worldZ = context.blockZ()
            readColumn(worldX, worldZ)

            // **Water the shape poured is water, and no threshold gets a vote.** A carver cutting into a
            // river or into a chamber's lake must find it: this is a body of water somebody can see, not
            // groundwater to be judged wet or dry by a noise. It was read as a *level* alone, which meant
            // a lake deep under a roofed world was still put to the deep thresholds — where dry is the
            // common case — and most tunnels into one came out as air, leaving holes through the lake.
            // A body made of something else answers first, and answers with what it is made of.
            for (index in columnCarried.indices) {
                if (!columnCarried[index].contains(worldY)) continue
                placedFluid = true
                return carried[index].fluid
            }
            if (columnStanding?.contains(worldY) == true) {
                placedFluid = true
                return fluid
            }
            val wet = worldY < standingLevel(worldX, worldY, worldZ)
            placedFluid = wet
            return if (wet) fluid else AIR
        }

        private fun standingLevel(worldX: Int, worldY: Int, worldZ: Int): Int {
            // No thresholds to consult: a flooded table says the same thing everywhere.
            if (floods) return columnWaterY
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
            val seaAt = slide(nearness, SEA_WHEN_SHALLOW, SEA_WHEN_DEEP)
            val dryAt = slide(nearness, PERCHED_WHEN_SHALLOW, PERCHED_WHEN_DEEP)

            // Rock so flooded it simply stands at the water's own level — the sea, or whatever the shape
            // carries over this column. Near a submerged surface `seaAt` drops below the noise's own floor,
            // which is what makes a seabed always flood however the rest is tuned.
            if (wetness > seaAt) return brimOf(worldY)
            // And rock too dry to hold any.
            if (wetness <= dryAt || seaAt <= dryAt) return BONE_DRY

            // **Everything between is a shoreline rather than a threshold**, which is the whole of the
            // departure from vanilla here (Jonah, 2026-09-11). Vanilla's middle case is a *perched pocket*
            // at its own banded level, and the edge of one meets dry rock at a face — two neighbouring
            // columns either side of the threshold coming out full and empty. A walk found that as "a giant
            // curtain of water down the middle of the cave", and porting vanilla's cell grid did not help,
            // because the decision is binary however it is arranged in space.
            //
            // So the level rises *continuously* with how flooded the rock is: nothing at the dry end, the
            // water's own level at the wet end, and a pool part-way up the room between. Neighbouring
            // columns read a smooth noise a block apart, so their levels differ by a block — which is a
            // shoreline running up the cave floor, and is what water actually looks like.
            val floor = roomFloor(worldY)
            val brim = brimOf(worldY)
            if (brim <= floor) return BONE_DRY
            val reach = (wetness - dryAt) / (seaAt - dryAt)
            return floor + ((brim - floor) * reach).roundToInt()
        }

        /** The floor of the room this point is in — where a pool of its water would rest. */
        private fun roomFloor(worldY: Int): Int = columnSpans?.floorUnder(worldY)?.plus(1) ?: worldY

        /**
         * As high as water may stand here at all: the water's own level, never over the ground, and never
         * above the ceiling of the room it is in.
         *
         * **The ceiling is the one a walk had to teach.** It was the column's *surface* alone, which under a
         * hill is the summit — so a pool in a cave was levelled a hundred blocks above its own roof, filled
         * the cave to the brim and poured out of it. Vanilla caps the same figure at a deliberately low
         * estimate of the surface; ours had taken the opposite extreme.
         */
        private fun brimOf(worldY: Int): Int {
            // **The ground caps this only where the ground is above the water.** Under the sea the water's
            // own level stands *over* the seabed by definition, so capping at the surface there says water
            // may never rise above the seabed — and a carve into it comes out dry, which is the hole in the
            // ocean this class exists to prevent. Caught by `WaterTableCheck` the moment it was written.
            val overhead = if (columnSubmerged) columnWaterY else minOf(columnWaterY, columnSurface)
            // No rock above means nothing to hold water down: the room is open to whatever is over it.
            val roomFor = columnSpans?.ceilingAbove(worldY)?.minus(1) ?: Int.MAX_VALUE
            return minOf(overhead, roomFor)
        }

        private var columnSpans: Spans? = null

        private fun readColumn(worldX: Int, worldZ: Int) {
            if (worldX == columnX && worldZ == columnZ) return
            columnX = worldX
            columnZ = worldZ
            // Kept whole rather than reduced to its top: a perched pool has to know the ceiling of the
            // room it is standing in, which no single height can answer. See [perchedLevel].
            val rock = field.columnSpans(worldX, worldZ)
            columnSpans = rock
            columnSurface = rock.highestSolidY ?: seaLevel
            // Under the sea, or under water the shape carries itself. Both are "there is water over this
            // ground"; only one of them is a level.
            columnStanding = standing?.columnSpans(worldX, worldZ)
            columnCarried = if (carried.isEmpty()) emptyList() else carried.map { it.where.columnSpans(worldX, worldZ) }
            val carried = columnStanding?.highestSolidY ?: Int.MIN_VALUE
            columnWaterY = maxOf(seaLevel, carried)
            columnSubmerged = columnSurface < columnWaterY
        }

        /**
         * Carving only ever asks about the handful of blocks it is removing, so flagging every drop it
         * places is cheap — and it lets water settle wherever a wet pocket meets a dry one.
         */
        override fun shouldScheduleFluidUpdate(): Boolean = placedFluid
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
        fun aquiferFor(tables: List<WaterTable>, field: TerrainField, territories: RegionMap): Aquifer =
            tables.singleOrNull()?.aquiferFor(field)
                ?: RegionalAquifer(tables.map { it.aquiferFor(field) }, territories)

        private val AIR: BlockState = Blocks.AIR.defaultBlockState()

        /** Nothing sits below this, so it reads as "no water in this rock at all". */
        private const val BONE_DRY = -4096

        // How flooded rock must be to hold water, just under a submerged surface versus far below it.
        //
        // **The shallow one is past the noise's own floor, so it is not a threshold at all**: rock just
        // under water always holds water. It used to be -0.3, which let two seabed blocks in five come out
        // dry — and a carver cutting there put an air pocket in the ocean. What keeps caves under *land*
        // dry is the branch above, which starts a column at the deep value unless something is over it.
        //
        // **Past the floor at the surface itself, not at the margin.** `nearness` is measured from
        // [surfaceMargin] *above* the ground, so it never reaches one — at the surface it is about 0.875,
        // and a value that only clears the noise at 1.0 still leaves one block in fourteen dry.
        private const val SEA_WHEN_SHALLOW = -1.6
        private const val SEA_WHEN_DEEP = 0.8
        // **Where a shoreline begins rather than where a pocket does.** These were vanilla's
        // partially-flooded thresholds, below which rock held nothing and above which it held a perched
        // pocket at a banded level. They are now the dry end of a ramp: at this floodedness a room holds no
        // water, and by [SEA_WHEN_DEEP] it holds all it can.
        private const val PERCHED_WHEN_SHALLOW = -0.8
        private const val PERCHED_WHEN_DEEP = 0.4

        private fun slide(nearness: Double, whenShallow: Double, whenDeep: Double) =
            whenDeep + (whenShallow - whenDeep) * nearness

        const val DEFAULT_DRYING_DEPTH = 64
        const val DEFAULT_SURFACE_MARGIN = 8

        /**
         * An Age's default: vanilla-shaped, seeded per Age so two Ages are not wet in the same places.
         * Takes the sea's *representative* substance rather than asking per column, so an Age whose sea is
         * water beside lava has water in its rock throughout — the impossible part is meant to be the
         * surface, not the groundwater.
         */
        fun matching(seaFill: SeaFill, seaLevel: Int, seed: Long = 0L) = WaterTable(
            fluid = seaFill.representative.takeUnless { it.isAir } ?: Blocks.WATER.defaultBlockState(),
            seaLevel = seaLevel,
            dryingDepth = DEFAULT_DRYING_DEPTH,
            surfaceMargin = DEFAULT_SURFACE_MARGIN,
            horizontalScale = 96.0,
            verticalScale = 64.0,
            seed = seed,
            firstOctave = -3,
            amplitudes = listOf(1.0, 1.0),
            // Whatever the shape pours for itself, so a carver under a river finds the river.
            standing = seaFill.wet,
            carried = seaFill.carried,
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
                TerrainField.CODEC.optionalFieldOf("standing")
                    .forGetter { table -> java.util.Optional.ofNullable(table.standing) },
                StandingFluid.codec(TerrainField.CODEC).codec().listOf().optionalFieldOf("carried", emptyList())
                    .forGetter(WaterTable::carried),
            ).apply(instance) { fluid, level, drying, margin, across, down, seed, octave, amplitudes, floods, standing, carried ->
                WaterTable(
                    fluid, level, drying, margin, across, down, seed, octave, amplitudes, floods,
                    standing.orElse(null), carried,
                )
            }
        }
    }
}
