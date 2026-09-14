package co.voik.agesandtheart.worldgen.field

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import co.voik.agesandtheart.worldgen.VerticalWindow
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

    /** How thick a barrier stands — vanilla's `aquifer_barrier`, on its own seed and at its own octave. */
    private val barrierThickness = fieldNoise(seed xor BARRIER_SALT, BARRIER_FIRST_OCTAVE, listOf(1.0))

    /** The table's seed, folded to what [cellHash] takes. */
    private val jitterSalt = (seed xor (seed ushr Int.SIZE_BITS)).toInt()
    private val acrossStretch = horizontalScale.coerceAtLeast(SMALLEST_STRETCH)
    private val downStretch = verticalScale.coerceAtLeast(SMALLEST_STRETCH)

    /**
     * A fresh aquifer for one carving pass; caches the column, whose surface is the costly part.
     *
     * [uncut] is the rock as it stood before its caves were cut, where the shape cut any — the surface the sea
     * is judged from.
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
            val worldY = context.blockY()
            val column = columnAt(context.blockX(), context.blockZ())
            val here = column.substanceAt(worldY)
            // **Vanilla's barrier between two levels of water**: a block that would hold water back keeps its
            // rock, so where a pool's level steps from one column to the next — a cave floor crossing a multiple
            // of [PERCHED_BAND], the edge between two cells' nudges, or the noise crossing a threshold — the
            // water is held by stone rather than standing against open air. Null is what both the fill and a
            // carver read as "leave the rock".
            val heldBack = if (here.isAir) isBarrier(column, worldY) else footsABarrier(column, worldY)
            placedFluid = !here.isAir && !heldBack
            return if (heldBack) null else here
        }

        /**
         * Whether this dry block keeps its rock, which it does in two ways that reach differently.
         *
         * - **Water beside it at its own height**, as a block opened there would hold it: one block, and asked
         *   of what a hole *would* hold rather than of what stands there, because a carver opening that rock
         *   later asks the same question and has to be answered the same way.
         * - **Open water within a barrier's reach**, at its own height or anywhere above it up the same open,
         *   dry space — which is what gives a dam its noisy width and takes it down to the floor. Only water
         *   standing in open space counts: the lowest blocks of any rock mass based in a pool's band *would*
         *   hold water if opened, and carried down to the floor that walled off whole caves with nothing in them.
         * - **Water directly over it** — see [waterAbove].
         */
        private fun isBarrier(column: Column, worldY: Int): Boolean =
            waterBeside(column, worldY) || waterAbove(column, worldY) || holdsUpWater(column, worldY)

        /**
         * Whether water stands directly over this block — the shelf vanilla's barrier lays between two of its
         * cells stacked one on another. A block's branch is read at its own height and the sea's threshold
         * slides with depth, so the top of a tall room under the seabed can come out wet over a dry bottom, and
         * without a shelf that water stands on air.
         *
         * Open water only where this block is open itself: the rock over a dry room *would* hold water if
         * opened far more often than water stands there, and a shelf under every such roof would lower cave
         * ceilings across the world. Where this block is still rock a carver is asking, and it is answered as
         * the rock above would be if that were cut too.
         */
        private fun waterAbove(column: Column, worldY: Int): Boolean {
            val above = worldY + 1
            val wetAbove = column.holdsFluid(above)
            val carverIsAsking = !column.isOpen(worldY)
            return wetAbove && (column.isOpen(above) || carverIsAsking)
        }

        private fun waterBeside(column: Column, worldY: Int): Boolean =
            columnAt(column.x - 1, column.z).holdsFluid(worldY) || columnAt(column.x + 1, column.z).holdsFluid(worldY) ||
                columnAt(column.x, column.z - 1).holdsFluid(worldY) || columnAt(column.x, column.z + 1).holdsFluid(worldY)

        /**
         * Whether open water stands within a barrier's reach of this block, or of the open, dry space above it —
         * see [isBarrier]. **This is what takes a barrier down to the floor**, so a dam never overhangs the ground
         * beneath it. Walked upwards and remembered for every block passed, so a column costs one walk however
         * many of its blocks are asked about.
         */
        private fun holdsUpWater(column: Column, worldY: Int): Boolean {
            var top = worldY
            var holdsUp = false
            while (true) {
                val known = column.holdsUpKnownAt(top)
                if (known != null) {
                    holdsUp = known
                    break
                }
                if (openWaterWithinReach(column, top)) {
                    holdsUp = true
                    break
                }
                val above = top + 1
                // Nothing over the ground is held, and a roof or a pool ends the open space a barrier runs down.
                val openSpaceEnds = above > column.surface || !column.isOpen(above) || column.holdsFluid(above)
                if (openSpaceEnds) break
                top = above
            }
            for (passed in worldY..top) column.rememberHoldsUp(passed, holdsUp)
            return holdsUp
        }

        /**
         * Whether open water stands at this height within a barrier's reach of this column, along either axis.
         *
         * **The reach is vanilla's noisy thickness**: one block where [barrierThickness] runs low, up to
         * [THICKEST_BARRIER] where it runs high, so a dam's width wanders rather than running as one clean line.
         * The noise is only sampled once water is found further off than the one block every barrier needs.
         */
        private fun openWaterWithinReach(column: Column, worldY: Int): Boolean {
            for (step in THINNEST_BARRIER..THICKEST_BARRIER) {
                val waterThisFar = openWaterAt(column.x - step, worldY, column.z) ||
                    openWaterAt(column.x + step, worldY, column.z) ||
                    openWaterAt(column.x, worldY, column.z - step) ||
                    openWaterAt(column.x, worldY, column.z + step)
                if (waterThisFar) return step == THINNEST_BARRIER || step <= barrierReachAt(column.x, worldY, column.z)
            }
            return false
        }

        private fun openWaterAt(worldX: Int, worldY: Int, worldZ: Int): Boolean {
            val column = columnAt(worldX, worldZ)
            return column.isOpen(worldY) && column.holdsFluid(worldY)
        }

        private fun barrierReachAt(worldX: Int, worldY: Int, worldZ: Int): Int {
            val noise = barrierThickness.getValue(worldX.toDouble(), worldY.toDouble(), worldZ.toDouble())
            return when {
                noise > BARRIER_THICKEST_ABOVE -> THICKEST_BARRIER
                noise > BARRIER_THICKER_ABOVE -> THICKER_BARRIER
                else -> THINNEST_BARRIER
            }
        }

        /**
         * Whether this wet block carries a barrier down into its own pool: the water here gives way to air
         * within [BARRIER_FOOTING] blocks, and that air keeps its rock. Vanilla takes its barrier the same
         * distance below the lower of two levels, so a step between two pools is a wall standing in the lower
         * one rather than a lip of stone resting on its surface.
         */
        private fun footsABarrier(column: Column, worldY: Int): Boolean {
            for (above in worldY + 1..worldY + BARRIER_FOOTING) {
                // A roof over the water holds it in by itself.
                if (!column.isOpen(above)) return false
                if (column.holdsFluid(above)) continue
                return isBarrier(column, above)
            }
            return false
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
         * **Vanilla does not have this, and the reason is worth keeping.** Its aquifer takes each block's
         * level from the nearest point of a jittered 16×12×16 grid, so a block's level comes from somewhere
         * it is *near* rather than from the slice it happens to occupy. The horizontal half of that grid is
         * [perchedNudge]; the vertical half is the room.
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
            // **Capped by the ceiling of the space this point is in, not by the top of the column.**
            // The column's surface is the highest rock anywhere in it, which under a hill is the *hilltop* —
            // so a perched pocket in a big cave was filled to a level hundreds of blocks above its own roof.
            // Vanilla caps the same number at its `lowestPreliminarySurface`, which is a deliberately low
            // estimate; ours had taken the opposite extreme.
            val roomFor = rock.ceilingAbove(worldY)?.minus(1) ?: column.surface
            return minOf(column.surface, roomFor, middle + perchedNudge(worldX, worldZ, band))
        }

        /**
         * How far this band's level is nudged here, taken from the cell whose jittered point lies nearest —
         * vanilla's lookup, on the two horizontal axes because the band already comes from the room.
         *
         * Each [PERCHED_CELL]-wide cell holds one point, up to [PERCHED_JITTER] blocks into it at a seeded
         * offset, and the grid sits [PERCHED_GRID_SHIFT] blocks off the chunks'. So the ground sharing one
         * level is an irregular polygon rather than a square on chunk lines, which is what the barrier between
         * two levels traces. Stepped by [PERCHED_STEP] as vanilla's is, so two neighbouring pools agree or
         * differ by enough to be a wall between them rather than a lip — see [footsABarrier].
         */
        private fun perchedNudge(worldX: Int, worldZ: Int, band: Int): Int {
            val anchorX = floorDiv(worldX - PERCHED_GRID_SHIFT, PERCHED_CELL)
            val anchorZ = floorDiv(worldZ - PERCHED_GRID_SHIFT, PERCHED_CELL)
            val saltAcross = jitterSalt xor (band * JITTER_BAND_STRIDE)
            val saltAlong = saltAcross xor JITTER_Z_SALT
            var nearestX = anchorX
            var nearestZ = anchorZ
            var nearestDistance = Int.MAX_VALUE
            for (cellX in anchorX..anchorX + 1) {
                for (cellZ in anchorZ..anchorZ + 1) {
                    val towardsX = cellX * PERCHED_CELL + jitterInto(cellX, cellZ, saltAcross) - worldX
                    val towardsZ = cellZ * PERCHED_CELL + jitterInto(cellX, cellZ, saltAlong) - worldZ
                    val distance = towardsX * towardsX + towardsZ * towardsZ
                    if (distance < nearestDistance) {
                        nearestDistance = distance
                        nearestX = cellX
                        nearestZ = cellZ
                    }
                }
            }
            val spread = floodedness.getValue(nearestX.toDouble(), band.toDouble(), nearestZ.toDouble()) * PERCHED_SPREAD
            return Mth.quantize(spread, PERCHED_STEP)
        }

        /** How far into its cell a cell's point sits, `0..<PERCHED_JITTER` — vanilla's `nextInt(10)`. */
        private fun jitterInto(cellX: Int, cellZ: Int, salt: Int): Int =
            ((cellHash(cellX, cellZ, salt) + 0.5) * PERCHED_JITTER).toInt().coerceAtMost(PERCHED_JITTER - 1)

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
         *
         * **Rock hanging over open space holds none either.** Its base is a room's ceiling, not a floor: water
         * in it would drain into the room below rather than stand. Read as a floor, the lowest blocks of every
         * cave's roof came out wet, a carver cutting them found water sitting over the dry cave beneath, and the
         * barrier ringed each ceiling contour with stone to keep that water off the air. Only the column's
         * lowest mass, which stands on the world's floor, takes its base.
         */
        private fun roomFloorUnder(rock: Spans, worldY: Int): Int? {
            val buriedIn = rock.ranges.firstOrNull { worldY in it }
            if (buriedIn != null) {
                val overhangsARoom = buriedIn.first != rock.ranges.first().first
                return if (overhangsARoom) null else buriedIn.first
            }
            return rock.floorUnder(worldY)?.plus(1)
        }

        private fun columnAt(worldX: Int, worldZ: Int): Column {
            val key = (worldX.toLong() shl Int.SIZE_BITS) or (worldZ.toLong() and 0xFFFF_FFFFL)
            return columns.get(key) ?: Column(worldX, worldZ).also { columns.put(key, it) }
        }

        /** What decides the water in one column, read once a pass, and what has been decided in it so far. */
        private inner class Column(val x: Int, val z: Int) {
            // Kept whole rather than reduced to its top: a perched pool has to know the ceiling of the
            // room it is standing in, which no single height can answer. See [perchedLevel].
            val rock: Spans = field.columnSpans(x, z)

            // The ground before its caves were cut, as vanilla's aquifer reads its preliminary surface: a cave
            // open to the sky must not turn the column it opens in into seabed.
            val surface: Int = maxOf(
                rock.highestSolidY ?: seaLevel,
                uncut?.columnSpans(x, z)?.highestSolidY ?: Int.MIN_VALUE,
            )

            /** Where this column's own water actually stands, rather than how high it reaches. */
            val standingSpans: Spans? = standing?.columnSpans(x, z)

            /** And where each body of something else stands, in [carried]'s own order. */
            val carriedSpans: List<Spans> =
                if (carried.isEmpty()) emptyList() else carried.map { it.where.columnSpans(x, z) }

            /**
             * What each block would hold, as [decide]'s codes, [UNDECIDED] until asked. A barrier reads the
             * columns around the one being opened, so each block is asked about several times a pass.
             */
            private val substances = ByteArray(MEMO_HEIGHT)

            /** Whether each dry block holds up open water — see [holdsUpWater]. */
            private val holdingUp = ByteArray(MEMO_HEIGHT)

            fun isOpen(worldY: Int): Boolean = !rock.contains(worldY)

            fun holdsFluid(worldY: Int): Boolean = substanceCodeAt(worldY) != DRY

            /** What a block opened here would hold before any barrier: a fluid, or air. */
            fun substanceAt(worldY: Int): BlockState = when (val code = substanceCodeAt(worldY)) {
                DRY -> AIR
                HOLDS_FLUID -> fluid
                else -> carried[code - CARRIED_FIRST].fluid
            }

            fun holdsUpKnownAt(worldY: Int): Boolean? {
                val slot = worldY - MEMO_BOTTOM
                if (slot !in 0..<MEMO_HEIGHT) return null
                return when (holdingUp[slot]) {
                    HOLDS_UP -> true
                    HOLDS_NOTHING -> false
                    else -> null
                }
            }

            fun rememberHoldsUp(worldY: Int, holdsUp: Boolean) {
                val slot = worldY - MEMO_BOTTOM
                if (slot in 0..<MEMO_HEIGHT) holdingUp[slot] = if (holdsUp) HOLDS_UP else HOLDS_NOTHING
            }

            private fun substanceCodeAt(worldY: Int): Int {
                val slot = worldY - MEMO_BOTTOM
                if (slot !in 0..<MEMO_HEIGHT) return decide(worldY)
                if (substances[slot] == UNDECIDED) substances[slot] = decide(worldY).toByte()
                return substances[slot].toInt()
            }

            private fun decide(worldY: Int): Int {
                // **Water the shape poured is water, and no threshold gets a vote.** A carver cutting into a
                // river or into a chamber's lake must find it: this is a body of water somebody can see, not
                // groundwater to be judged wet or dry by a noise. It was read as a *level* alone, which meant
                // a lake deep under a roofed world was still put to the deep thresholds — where dry is the
                // common case — and most tunnels into one came out as air, leaving holes through the lake.
                // A body made of something else answers first, and answers with what it is made of.
                for (index in carriedSpans.indices) {
                    if (carriedSpans[index].contains(worldY)) return CARRIED_FIRST + index
                }
                if (standingSpans?.contains(worldY) == true) return HOLDS_FLUID
                val wetness = wetnessAt(x, worldY, z)
                return if (worldY < standingLevel(this, x, worldY, z, wetness)) HOLDS_FLUID else DRY
            }

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

        // Vanilla's jittered grid: the anchor is taken five blocks back, and a point sits `nextInt(10)` into its cell.
        private const val PERCHED_GRID_SHIFT = 5
        private const val PERCHED_JITTER = 10

        // Odd constants separating the jitter of one band, and of one axis, from another.
        private const val JITTER_BAND_STRIDE = 0x632B_E5AB
        private const val JITTER_Z_SALT = 0x5BD1_E995

        /** How far below the lower of two levels a barrier reaches — vanilla's `bottomBias`. */
        private const val BARRIER_FOOTING = 3

        // How thick a barrier stands, by [barrierThickness]: about half are one block, the rest two or three.
        private const val BARRIER_SALT = 0x6A09_E667_F3BC_C909L
        private const val BARRIER_FIRST_OCTAVE = -3
        private const val THINNEST_BARRIER = 1
        private const val THICKER_BARRIER = 2
        private const val THICKEST_BARRIER = 3
        private const val BARRIER_THICKER_ABOVE = 0.0
        private const val BARRIER_THICKEST_ABOVE = 0.4

        // What a block would hold, as a column remembers it.
        private const val UNDECIDED: Byte = 0
        private const val DRY = 1
        private const val HOLDS_FLUID = 2
        private const val CARRIED_FIRST = 3

        // Whether a dry block holds up open water, as a column remembers it.
        private const val HOLDS_NOTHING: Byte = 1
        private const val HOLDS_UP: Byte = 2

        // The heights a column remembers — an Age's own window; anything outside it is decided afresh.
        private val MEMO_BOTTOM = VerticalWindow.DEFAULT.minY
        private val MEMO_HEIGHT = VerticalWindow.DEFAULT.height

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
