package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.content.DeepWater
import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.Spans
import co.voik.agesandtheart.worldgen.field.StandingFluid
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.TerrainFill
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.levelgen.Aquifer
import net.minecraft.world.level.levelgen.Heightmap

/**
 * One chunk of an Age whose rock is **ours**, filled column by column from its field tree.
 *
 * Built fresh for each chunk and thrown away, which is what lets it hold the answers the fill would
 * otherwise ask for again per block: the band of columns, the aquifer, the structure adaptation, and the
 * sea as it stands.
 *
 * **The sea is read once here, where the fill used to read it per column.** [ColumnBand] already took a
 * single reading for everything it answers, so a chunk was already half-committed to one sea; taking the
 * whole chunk from one reading is what makes it wholly so. The only case in which that differs is a deluge
 * moving the waterline *while a single chunk is being filled*, and a chunk built from two waterlines is not
 * a thing worth preserving. It is a race rather than a design, and it is named here rather than left to be
 * discovered.
 *
 * **[abyssBelongsHere] is deliberately not given the same treatment.** It reaches back into the generator
 * and reads the live sea, exactly as it did before this class existed. Making it agree with the snapshot
 * would be an improvement and a *behaviour change*, and this move's whole claim is that no terrain wanders.
 */
internal class FieldFill(
    private val rock: AgeRock.Ours,
    private val fill: TerrainFill,
    private val window: VerticalWindow,
    private val sea: SeaFill,
    private val abyssLine: Int,
    private val water: Aquifer,
    private val adaptation: TerrainAdaptation?,
    private val abyssBelongsHere: (ChunkAccess, Int, Int) -> Boolean,
) {

    fun fillInto(chunk: ChunkAccess) {
        val chunkMinX = chunk.pos.minBlockX
        val chunkMinZ = chunk.pos.minBlockZ
        val oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG)
        val worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG)
        val cursor = BlockPos.MutableBlockPos()

        // **A band, not a chunk.** A fluid can only be told to move if we know whether its neighbours left
        // it anywhere to go, and the columns beside a chunk's edge are outside it — so this reads one
        // column of margin all round. Measured at +24% of the field's own cost against a budget in which
        // that field is a few milliseconds, which is what made it worth having over letting the walls
        // stand. Read once per column and not once per block: the answer cannot change going down one.
        val band = ColumnBand(chunkMinX, chunkMinZ, rock.field, sea, rock.hollows)
        // A second cursor: `DeepWater.airOpenedOver` walks back down the column, and sharing `cursor` with
        // it would move the position the fill is about to write to.
        val reach = BlockPos.MutableBlockPos()

        for (localX in 0..<16) {
            for (localZ in 0..<16) {
                val worldX = chunkMinX + localX
                val worldZ = chunkMinZ + localZ
                val at = band.indexOf(localX, localZ)
                val spans = band.spans(at)
                val seaHere = sea.blockAt(worldX, worldZ)
                // **Whether an abyss stands over this column at all**, asked once here rather than of
                // every block. Without it, any Age with a sea grew an abyss in its deepest caves, eighty
                // blocks under a waterline that never reached them.
                // The band answers the geometry and the water. Whether this column is *sea* at all is the
                // third question and neither of those can answer it — see `abyssBelongsIn`, which is what
                // keeps the plane from slicing a sheet of deep water through an Age's caves.
                val abyssal = !spans.contains(abyssLine) && band.fills(at, abyssLine) &&
                    abyssBelongsHere(chunk, worldX, worldZ)

                // **Whether any lava the shape carries is near enough to line**, asked once for the whole
                // column — see [MoltenLining]. False everywhere in an Age with no carried body, which is
                // what keeps the two questions below off the hot path of every ordinary world.
                val couldLine = band.carriesNear(localX, localZ)

                // Whether the block just below came out empty, so a fluid placed on top of nothing can be
                // told to fall. Nothing is below the window's floor, which is as good as open for this.
                var nothingBelow = true

                for (y in window.minY..<window.topY) {
                    // The field decides, unless a structure standing here has an opinion of its own.
                    val fieldHasRock = spans.contains(y)
                    val verdict = adaptation?.verdictAt(worldX, y, worldZ)
                    val isRock = verdict ?: fieldHasRock
                    val clearedByAStructure = verdict == false && fieldHasRock
                    // A hollow open to the sky under the waterline is the sea's, not the aquifer's.
                    val openToTheSea = band.hollowOpenToTheSea(at, y)
                    val askedTheAquifer = !isRock && !clearedByAStructure && band.carried(at, y) == null &&
                        band.hollow(at, y) && !openToTheSea
                    val state = when {
                        // What the rock *is*, which is vanilla's `default_block` and now ours — the surface
                        // system paints its skin over this afterwards, exactly as it does for vanilla.
                        // **Unless it is holding lava in**, in which case it is obsidian: see [MoltenLining]
                        // for why a volcano that demolishes its own caldera used to leave the lake in the air.
                        isRock -> MoltenLining.rockAt(
                            moltenAbove = couldLine && MoltenLining.isMolten(band.carried(at, y + 1)),
                            moltenBeside = couldLine && band.moltenBeside(localX, localZ, y),
                            otherwise = fill.blockAt(worldX, y, worldZ),
                        )
                        // Room a structure cut out of solid rock is the structure's, so it stays air. Below the
                        // waterline and outside anything marked dry, the sea would otherwise pour into it — in a
                        // chambered Age that is every piece of a D'ni city dug into the island or the wall.
                        clearedByAStructure -> null
                        // A body the shape carries, which answers before either of the two below it: a
                        // caldera's lava is neither groundwater nor the sea, and both of those would take
                        // the space and put the wrong substance in it.
                        band.carried(at, y) != null -> band.carried(at, y)
                        openToTheSea -> seaHere
                        // Inside the rock a cave system opened: the table answers, not the waterline. Asked
                        // before the sea, since this space is under it and the sea would otherwise take it.
                        band.hollow(at, y) -> heldBackFrom(
                            water.computeSubstance(worldX, y, worldZ, HOLLOW),
                            band,
                            localX,
                            localZ,
                            y,
                            worldX,
                            worldZ,
                        )
                        band.fills(at, y) -> seaHere
                        else -> null
                    }
                        // **Every water this column produced, not only the sea's.** The aquifer answers
                        // before the sea does, so a cave flooded from the water table under an abyss came
                        // out as ordinary water — which read as great pockets of plain sea scattered
                        // through the deep (Jonah, walked 2026-09-10). Wrapping the whole `when` is what
                        // makes the boundary one plane rather than one per source of water.
                        ?.let { if (abyssal) DeepWater.seaAt(y, abyssLine, it) else it }
                        ?.takeUnless { it.isAir }
                    if (state == null) {
                        // Air over the abyss takes the pressure out of the water under it — see
                        // `DeepWater.standsAt`. The fill runs bottom-up, so what this affects is already in
                        // the chunk and is rewritten rather than predicted.
                        if (abyssal && y <= abyssLine) DeepWater.airOpenedOver(chunk, reach, worldX, y, worldZ)
                        nothingBelow = true
                        continue
                    }
                    cursor.set(worldX, y, worldZ)
                    // Water the aquifer placed gets its first tick exactly where vanilla's would: where two of
                    // its cells meet with different water. **Anything else with anywhere to go is asked to go
                    // there.** Where a channel drops faster than its own surface does, the shape leaves water
                    // standing over a step or against a wall of open air — and no arrangement of *levels* can
                    // fix that, because the gap is where the water is moving. Marked for post-processing,
                    // vanilla gives the source its first tick on load and it finds its own way down.
                    val wantsToMove = if (askedTheAquifer) {
                        water.shouldScheduleFluidUpdate()
                    } else {
                        nothingBelow || band.openBeside(localX, localZ, y)
                    }
                    if (wantsToMove && !state.fluidState.isEmpty) chunk.markPosForPostProcessing(cursor)
                    nothingBelow = false
                    chunk.setBlockState(cursor, state)
                    oceanFloor.update(localX, y, localZ, state)
                    worldSurface.update(localX, y, localZ, state)
                }
            }
        }
    }

    /**
     * **A wall of rock where a dry cave meets the sea** — vanilla's aquifer barrier, in the shape this
     * generator's seams actually take.
     *
     * **Two authorities own the water, and the fault is on their border** (found with `/age probe`,
     * 2026-09-11). The aquifer owns every hollow of ours and may answer *dry*; the sea fills anything below
     * the waterline the aquifer does not own. Where one cave crosses that line, the cave side comes out a
     * pool at its own level — or nothing — and the other side comes out sea at the waterline, and the two
     * meet at a **face**. A walk finds that as a slab of sea jutting into a cave, or a curtain down the
     * middle of one, and then watches it pour: the fill marks a perched fluid for post-processing and
     * vanilla gives it its first tick on load.
     *
     * Three attempts inside the aquifer — per room, per cell, a continuous level — all missed, because none
     * of them is about the border.
     *
     * **Vanilla's answer is not to reconcile the two; it is to separate them.** Where two of its aquifers
     * disagree it computes a pressure between them and puts **stone** in the gap, so a player never sees
     * water standing against air — they see a wall, which is what a wall between two water tables looks
     * like. This is that rule with our own two authorities in place of two of its cells.
     *
     * **Only where the cave came out dry.** A cave the aquifer filled is already water and wants no wall;
     * what needs one is emptiness with a sea leaning on it. And only against the *sea* — a dry cave beside
     * another dry cave is just a cave.
     */
    private fun heldBackFrom(
        answer: BlockState?,
        band: ColumnBand,
        localX: Int,
        localZ: Int,
        y: Int,
        worldX: Int,
        worldZ: Int,
    ): BlockState? {
        // Null is the aquifer's own barrier between two levels of water, and the rock stays — as it does for
        // a carver handed the same answer.
        if (answer == null) return fill.blockAt(worldX, y, worldZ)
        // The aquifer put something here, so there is nothing to hold back.
        if (!answer.isAir) return answer
        if (!band.seaTouching(localX, localZ, y)) return answer
        return fill.blockAt(worldX, y, worldZ)
    }

    /**
     * A chunk's columns and one of margin all round, read once.
     *
     * The margin is the whole point: whether a fluid has somewhere to go is a question about its
     * *neighbours*, and a sixteenth of a chunk's columns have neighbours outside it. Reading a band costs a
     * quarter more than reading a chunk, where asking four extra columns per block would cost five times.
     *
     * Structure adaptation is deliberately not consulted for a neighbour. It is a local override on one
     * chunk's own rock, and letting it decide whether a river spills would make a village change the water
     * two chunks away.
     */
    private class ColumnBand(
        chunkMinX: Int,
        chunkMinZ: Int,
        field: TerrainField,
        private val seaFill: SeaFill,
        hollows: TerrainField?,
    ) {
        private val spans = arrayOfNulls<Spans>(SIDE * SIDE)
        private val dryness = arrayOfNulls<Spans>(SIDE * SIDE)
        private val wetness = arrayOfNulls<Spans>(SIDE * SIDE)
        private val hollowness = arrayOfNulls<Spans>(SIDE * SIDE)
        private val bodies = arrayOfNulls<List<Spans>>(SIDE * SIDE)

        init {
            for (bandX in 0..<SIDE) {
                for (bandZ in 0..<SIDE) {
                    val worldX = chunkMinX + bandX - MARGIN
                    val worldZ = chunkMinZ + bandZ - MARGIN
                    val at = bandX * SIDE + bandZ
                    spans[at] = field.columnSpans(worldX, worldZ)
                    dryness[at] = seaFill.drynessAt(worldX, worldZ)
                    wetness[at] = seaFill.wetnessAt(worldX, worldZ)
                    hollowness[at] = hollows?.columnSpans(worldX, worldZ) ?: Spans.EMPTY
                    bodies[at] = seaFill.carriedAt(worldX, worldZ)
                }
            }
        }

        /** Whether this level is inside the rock a cave system was cut from — see [hollows]. */
        fun hollow(at: Int, y: Int): Boolean = hollowness[at]!!.contains(y)

        /** What a body the shape carries puts here, if one reaches — see [StandingFluid]. */
        fun carried(at: Int, y: Int): BlockState? = seaFill.carriedAt(y, bodies[at]!!)

        fun indexOf(localX: Int, localZ: Int): Int = (localX + MARGIN) * SIDE + (localZ + MARGIN)

        fun spans(at: Int): Spans = spans[at]!!

        fun fills(at: Int, y: Int): Boolean = seaFill.fillsAt(y, dryness[at]!!, wetness[at]!!)

        /**
         * Whether this column or any beside it carries a body at all — the gate that keeps the lining
         * questions off every column of every Age that has no lava in it, which is almost all of them.
         * Asked once per column rather than once per block.
         */
        fun carriesNear(localX: Int, localZ: Int): Boolean =
            bodies[indexOf(localX, localZ)]!!.isNotEmpty() ||
                bodies[indexOf(localX - 1, localZ)]!!.isNotEmpty() ||
                bodies[indexOf(localX + 1, localZ)]!!.isNotEmpty() ||
                bodies[indexOf(localX, localZ - 1)]!!.isNotEmpty() ||
                bodies[indexOf(localX, localZ + 1)]!!.isNotEmpty()

        /** Whether a carried body puts lava at this level in any of the four columns beside this one. */
        fun moltenBeside(localX: Int, localZ: Int, y: Int): Boolean =
            isMolten(indexOf(localX - 1, localZ), y) || isMolten(indexOf(localX + 1, localZ), y) ||
                isMolten(indexOf(localX, localZ - 1), y) || isMolten(indexOf(localX, localZ + 1), y)

        private fun isMolten(at: Int, y: Int): Boolean = MoltenLining.isMolten(carried(at, y))

        /**
         * Whether the **sea** stands against this block — beside it, or over it.
         *
         * The sea's own space is what the aquifer does not own: not rock, not a hollow of ours, and under
         * the waterline — **or a hollow open to the sky under it**, which is the sea's too
         * ([hollowOpenToTheSea]). Leaving that out walled nothing between a roofed dry cave and an open
         * hollow beside it, and the sea stood against the air (walked 2026-10-01). Above is checked as well
         * as beside, because a sea lying on the roof of a dry cave falls into it the moment the chunk is
         * ticked.
         */
        fun seaTouching(localX: Int, localZ: Int, y: Int): Boolean =
            isSea(indexOf(localX, localZ), y + 1) ||
                isSea(indexOf(localX - 1, localZ), y) || isSea(indexOf(localX + 1, localZ), y) ||
                isSea(indexOf(localX, localZ - 1), y) || isSea(indexOf(localX, localZ + 1), y)

        private fun isSea(at: Int, y: Int): Boolean =
            !spans[at]!!.contains(y) && (!hollow(at, y) || hollowOpenToTheSea(at, y)) && carried(at, y) == null &&
                fills(at, y)

        /**
         * **A hollow with nothing over it, under the waterline, is the sea's** (Jonah, walked 2026-10-01: holes
         * in the shallows open to the sky, the sea walled round them). The aquifer decides whether a cell is
         * under the sea from the preliminary surface, which is smoothed over sixteen blocks and stands above
         * the water along a shore, so a cave opening up through the shallows read as inland and came out dry.
         * Enclosed caves are still the aquifer's, which is what it is for.
         */
        fun hollowOpenToTheSea(at: Int, y: Int): Boolean =
            hollow(at, y) && (spans[at]!!.highestSolidY?.let { y > it } ?: true) && fills(at, y)

        /** Whether any of the four columns beside this one left this level open. */
        fun openBeside(localX: Int, localZ: Int, y: Int): Boolean =
            isOpen(indexOf(localX - 1, localZ), y) || isOpen(indexOf(localX + 1, localZ), y) ||
                isOpen(indexOf(localX, localZ - 1), y) || isOpen(indexOf(localX, localZ + 1), y)

        private fun isOpen(at: Int, y: Int): Boolean =
            !spans[at]!!.contains(y) && !fills(at, y) && carried(at, y) == null

        private companion object {
            const val MARGIN = 1
            const val SIDE = 16 + 2 * MARGIN
        }
    }

    private companion object {
        /** What an aquifer is told about a block being *removed*, so it answers with a fluid or with air. */
        const val HOLLOW = -1.0
    }
}
