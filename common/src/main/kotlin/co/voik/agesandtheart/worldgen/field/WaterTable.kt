package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.util.Mth
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Aquifer
import net.minecraft.world.level.levelgen.DensityFunction
import java.lang.Math.floorDiv

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

    /**
     * A fresh aquifer for one carving pass; caches the column, whose surface is the costly part.
     *
     * [uncut] is the rock as it stood before its caves were cut, where the shape cut any — see [readColumn].
     */
    fun aquiferFor(field: TerrainField, uncut: TerrainField? = null): Aquifer = ColumnAquifer(field, uncut)

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

    private inner class ColumnAquifer(private val field: TerrainField, private val uncut: TerrainField?) : Aquifer {
        private var placedFluid = false

        /** Every column this pass has read: the barrier asks about the columns beside the one being opened. */
        private val columns = Long2ObjectOpenHashMap<Column>()

        override fun computeSubstance(context: DensityFunction.FunctionContext, substance: Double): BlockState? {
            // Positive means solid: nothing is being removed here, so the block stands as it is.
            if (substance > 0.0) return null
            val worldX = context.blockX()
            val worldY = context.blockY()
            val worldZ = context.blockZ()
            val here = substanceAt(worldX, worldY, worldZ)
            // **Vanilla's barrier between two levels of water**: a block left dry beside water standing at its
            // height keeps its rock, so where a pool's level steps from one column to the next — a cave floor
            // crossing a multiple of [PERCHED_BAND], or the noise crossing a threshold — the water is held by
            // stone rather than standing against open air. Null is what both the fill and a carver read as
            // "leave the rock".
            val heldBack = if (here.isAir) waterBeside(worldX, worldY, worldZ) else footsABarrier(worldX, worldY, worldZ)
            placedFluid = !here.isAir && !heldBack
            return if (heldBack) null else here
        }

        /**
         * Whether this wet block carries a barrier down into its own pool: the water here gives way to air
         * within [BARRIER_FOOTING] blocks, and water beside that air stands higher. Vanilla takes its barrier
         * the same distance below the lower of two levels, so a step between two pools is a wall standing in
         * the lower one rather than a lip of stone resting on its surface.
         */
        private fun footsABarrier(worldX: Int, worldY: Int, worldZ: Int): Boolean {
            val rock = columnAt(worldX, worldZ).rock
            for (above in worldY + 1..worldY + BARRIER_FOOTING) {
                // A roof over the water holds it in by itself.
                if (rock.contains(above)) return false
                if (!holdsFluid(worldX, above, worldZ)) return waterBeside(worldX, above, worldZ)
            }
            return false
        }

        private fun waterBeside(worldX: Int, worldY: Int, worldZ: Int): Boolean =
            holdsFluid(worldX - 1, worldY, worldZ) || holdsFluid(worldX + 1, worldY, worldZ) ||
                holdsFluid(worldX, worldY, worldZ - 1) || holdsFluid(worldX, worldY, worldZ + 1)

        private fun holdsFluid(worldX: Int, worldY: Int, worldZ: Int): Boolean = !substanceAt(worldX, worldY, worldZ).isAir

        /** What a block opened here would hold before any barrier: a fluid, or air. */
        private fun substanceAt(worldX: Int, worldY: Int, worldZ: Int): BlockState {
            val column = columnAt(worldX, worldZ)
            // **Water the shape poured is water, and no threshold gets a vote.** A carver cutting into a
            // river or into a chamber's lake must find it: this is a body of water somebody can see, not
            // groundwater to be judged wet or dry by a noise. It was read as a *level* alone, which meant
            // a lake deep under a roofed world was still put to the deep thresholds — where dry is the
            // common case — and most tunnels into one came out as air, leaving holes through the lake.
            // A body made of something else answers first, and answers with what it is made of.
            for (index in column.carriedSpans.indices) {
                if (column.carriedSpans[index].contains(worldY)) return carried[index].fluid
            }
            if (column.standingSpans?.contains(worldY) == true) return fluid
            val wetness = wetnessAt(worldX, worldY, worldZ)
            return if (worldY < standingLevel(column, worldX, worldY, worldZ, wetness)) fluid else AIR
        }

        /** How flooded this point's rock is, on the noise the thresholds are read against. */
        private fun wetnessAt(worldX: Int, worldY: Int, worldZ: Int): Double = floodedness
            .getValue(worldX / acrossStretch, worldY / downStretch, worldZ / acrossStretch)
            .coerceIn(-1.0, 1.0)

        /**
         * How near the surface this point is, as the thresholds read it: 1 just beneath a submerged
         * surface, falling to 0 [dryingDepth] blocks down. Land columns start at 0, so rock under a hill is
         * judged by the deep thresholds straight away.
         */
        private fun nearnessAt(column: Column, worldY: Int): Double = if (column.isSubmerged) {
            val depth = (column.surface + surfaceMargin - worldY).toDouble()
            (1.0 - depth / dryingDepth).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        private fun standingLevel(column: Column, worldX: Int, worldY: Int, worldZ: Int, wetness: Double): Int {
            // No thresholds to consult: a flooded table says the same thing everywhere.
            if (floods) return column.waterY
            val nearness = nearnessAt(column, worldY)
            return when {
                wetness > slide(nearness, SEA_WHEN_SHALLOW, SEA_WHEN_DEEP) -> column.waterY
                wetness > slide(nearness, PERCHED_WHEN_SHALLOW, PERCHED_WHEN_DEEP) -> perchedLevel(column, worldX, worldY, worldZ)
                else -> BONE_DRY
            }
        }

        /**
         * How high groundwater stands in the **room** this point is in.
         *
         * **Per room, not per block — which is the whole of what was wrong** (Jonah, walked 2026-09-11).
         * The band used to come from the query's own `worldY`, so the answer changed as you moved up a
         * column: inside band *b* a block is wet while `y < 40b + 20`, which makes the bottom half of every
         * forty-block slice of the world water and the top half air, over and over, in every cave deep
         * enough to cross one. What a walk saw was *"a flat slab of water on a specific level"*, repeated —
         * and water standing against a cave roof, which then poured out of it. That is arithmetic showing
         * through, not geology.
         *
         * **Vanilla does not have this, and the reason is worth keeping.** Its aquifer resolves one fluid
         * level per *cell* of a jittered 16×12×16 grid and interpolates between the four nearest, so a
         * block's level comes from somewhere it is *near* rather than from the slice it happens to occupy.
         * Our copy took the arithmetic and dropped the grid.
         *
         * **The room is the cell that suits this generator.** We have the column's spans, so the cave a
         * point stands in is already known — and it is the honest unit, because a pool's surface has to be
         * one height for everybody standing in the same water. Taking the band from the room's **floor**
         * gives every block of one cave the same answer, keeps rooms at different depths on different bands
         * the way vanilla's stacked aquifers do, and cannot saw-tooth, there being one level per room.
         *
         * Still capped by the room's own ceiling and by the column's surface: a pool is bounded by what
         * holds it.
         */
        private fun perchedLevel(column: Column, worldX: Int, worldY: Int, worldZ: Int): Int {
            val rock = column.rock
            val roomFloor = roomFloorUnder(rock, worldY) ?: return BONE_DRY
            val band = floorDiv(roomFloor, PERCHED_BAND)
            val middle = band * PERCHED_BAND + PERCHED_BAND / 2
            val nudge = floodedness.getValue(
                floorDiv(worldX, PERCHED_CELL).toDouble(),
                band.toDouble(),
                floorDiv(worldZ, PERCHED_CELL).toDouble(),
            ) * PERCHED_SPREAD
            // **Capped by the ceiling of the space this point is in, not by the top of the column.**
            // The column's surface is the highest rock anywhere in it, which under a hill is the *hilltop* —
            // so a perched pocket in a big cave was filled to a level hundreds of blocks above its own roof.
            // Vanilla caps the same number at its `lowestPreliminarySurface`, which is a deliberately low
            // estimate; ours had taken the opposite extreme.
            val roomFor = rock.ceilingAbove(worldY)?.minus(1) ?: column.surface
            // Stepped as vanilla steps it, so two neighbouring pools either agree or differ by enough to be a
            // wall between them rather than a lip — see [footsABarrier].
            return minOf(column.surface, roomFor, middle + Mth.quantize(nudge, PERCHED_STEP))
        }

        /**
         * **What this water would stand on**, or null where nothing would hold it.
         *
         * Three cases, and only the first was written. A point in a **room** stands on that room's floor.
         * A point **inside rock** has no room, and the fix the band already carries has to reach it too:
         * the base of the mass it is buried in is one value for the whole mass, where the query's own
         * height is a different value every block. That fallback used to be `worldY` — the very reading
         * the band was rewritten to stop using — so a column of unbroken rock came out banded by height
         * and flooded from bedrock to the waterline, while the column beside it, broken anywhere at all,
         * read a real band and came out dry. Two neighbours, forty blocks apart, and the face between
         * them is what a walk found as *"a big wall of water next to an empty gap"* (Jonah, 2026-09-11).
         *
         * And a point over **nothing** holds no water, because a perched pool is water standing on
         * something. `floorUnder` answers null for both of the last two, which is why they are told apart
         * here rather than there.
         */
        private fun roomFloorUnder(rock: Spans?, worldY: Int): Int? {
            val buriedIn = rock?.ranges?.firstOrNull { worldY in it }
            if (buriedIn != null) return buriedIn.first
            return rock?.floorUnder(worldY)?.plus(1)
        }

        private fun columnAt(worldX: Int, worldZ: Int): Column {
            val key = (worldX.toLong() shl Int.SIZE_BITS) or (worldZ.toLong() and 0xFFFF_FFFFL)
            return columns.get(key) ?: Column(worldX, worldZ).also { columns.put(key, it) }
        }

        /** What decides the water in one column, read once a pass. */
        private inner class Column(worldX: Int, worldZ: Int) {
            // Kept whole rather than reduced to its top: a perched pool has to know the ceiling of the
            // room it is standing in, which no single height can answer. See [perchedLevel].
            val rock: Spans = field.columnSpans(worldX, worldZ)

            // The ground before its caves were cut, as vanilla's aquifer reads its preliminary surface: a cave
            // open to the sky must not turn the column it opens in into seabed.
            val surface: Int = maxOf(
                rock.highestSolidY ?: seaLevel,
                uncut?.columnSpans(worldX, worldZ)?.highestSolidY ?: Int.MIN_VALUE,
            )

            /** Where this column's own water actually stands, rather than how high it reaches. */
            val standingSpans: Spans? = standing?.columnSpans(worldX, worldZ)

            /** And where each body of something else stands, in [carried]'s own order. */
            val carriedSpans: List<Spans> =
                if (carried.isEmpty()) emptyList() else carried.map { it.where.columnSpans(worldX, worldZ) }

            /**
             * What water stands over this column — the sea, or higher where the shape carries its own.
             *
             * **The level has to travel with the branch.** Deciding that a river bed is submerged and then
             * answering with the *sea's* height leaves every block between the two dry, which is the same
             * hole by a longer road.
             */
            val waterY: Int = maxOf(seaLevel, standingSpans?.highestSolidY ?: Int.MIN_VALUE)

            // Under the sea, or under water the shape carries itself. Both are "there is water over this
            // ground"; only one of them is a level.
            val isSubmerged: Boolean = surface < waterY
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
        fun aquiferFor(tables: List<WaterTable>, field: TerrainField, uncut: TerrainField?, territories: RegionMap): Aquifer =
            tables.singleOrNull()?.aquiferFor(field, uncut)
                ?: RegionalAquifer(tables.map { it.aquiferFor(field, uncut) }, territories)

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
        private const val PERCHED_WHEN_SHALLOW = -0.8
        private const val PERCHED_WHEN_DEEP = 0.4


        private const val PERCHED_BAND = 40
        private const val PERCHED_CELL = 16
        private const val PERCHED_SPREAD = 10.0
        private const val PERCHED_STEP = 3

        /** How far below the lower of two levels a barrier reaches — vanilla's `bottomBias`. */
        private const val BARRIER_FOOTING = 3

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
