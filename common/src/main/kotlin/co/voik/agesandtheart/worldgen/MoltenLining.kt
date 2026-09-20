package co.voik.agesandtheart.worldgen

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess

/**
 * **The obsidian a body of lava the shape carries is held in** — a crater lake's bowl and a magma chamber's.
 *
 * **A volcano demolishes its own caldera, which is the point of it, and used to leave the lake behind in the
 * air** (Jonah, walked 2026-09-11). The bombs blow the rim and the flanks apart; the lava is *source* blocks
 * laid at generation and does not flow unless something disturbs it, so what survived an hour of eruption
 * was a level sheet of lava with nothing under it. Stone has a blast resistance of 6 and obsidian 1200, so
 * lining the bowl is the whole fix.
 *
 * **Under and beside, which together trace the bowl rather than its floor.** Jonah's own framing, and it is
 * better than the shell it replaced: a caldera is a cone, so on the sloped wall *every* rock block has lava
 * standing over it up to the lake's surface — asking "is there lava above this" therefore paints the floor
 * and the walls in one rule, with no second shape to build and keep in step. `beside` is what closes the
 * last course at the waterline, where the block level with the lake's top surface has air above it.
 *
 * **Only lava.** A body of water the shape carries — a river, a pool — has no reason to be held in obsidian,
 * and doing it by substance rather than by name keeps the rule from having to know which body it is looking
 * at.
 */
object MoltenLining {

    /**
     * Lazily, because this file is reachable from an offline check and `Blocks` needs a bootstrapped
     * registry — the same trap `Collapse` records.
     */
    private val OBSIDIAN: BlockState by lazy { Blocks.OBSIDIAN.defaultBlockState() }

    /** Whether [carried] is a body worth being held in — see the class note. */
    fun isMolten(carried: BlockState?): Boolean = carried != null && carried.`is`(Blocks.LAVA)

    /**
     * What a rock block touching such a body is made of, given what it would otherwise have been.
     *
     * Takes the ordinary answer rather than returning null for it, so a caller reads as one expression and
     * cannot forget the other half.
     */
    fun rockAt(moltenAbove: Boolean, moltenBeside: Boolean, otherwise: BlockState): BlockState =
        if (moltenAbove || moltenBeside) OBSIDIAN else otherwise

    /**
     * **Lava a carver has just opened is told it may move.**
     *
     * A carver runs *after* the fill and writes its air straight into the chunk, firing no neighbour
     * updates — generation never does. So a caldera whose wall a cave happened to cut through kept a level
     * sheet of lava standing over the hole, because a source block does nothing until something asks it to
     * (Jonah, walked 2026-09-11: *"it did leave some lava that should have been flowing suspended"*).
     *
     * **Marked rather than moved**, which is the same answer the fill already gives its own perched fluids:
     * `markPosForPostProcessing` has vanilla give the block its first tick when the chunk loads, and it then
     * finds its own way down. Nothing here decides where the lava goes.
     *
     * **Lava only, and that is what makes the sweep affordable.** It is rare — crater lakes and magma
     * chambers — so almost every section is skipped outright by the same `maybeHas` test
     * `DeepWater.settleTheAbyss` uses, and an Age with none pays one predicate per section. Water is left
     * alone deliberately: a sea is most of the volume of a wet Age, and vanilla's carvers already stop at
     * it rather than cutting it open.
     *
     * A neighbour outside this chunk is not looked at. It cannot be read reliably here, and the chunk it
     * belongs to runs this same sweep over its own side of the boundary.
     *
     * Here rather than on the chunk generator: "lava only, and mark rather than move" is a rule about what
     * a lining is, and a generator should not be the thing that knows it.
     */
    fun markLavaOpenedIn(chunk: ChunkAccess) {
        val at = BlockPos.MutableBlockPos()
        val beside = BlockPos.MutableBlockPos()
        val lowest = chunk.minY
        val highest = chunk.minY + chunk.height - 1
        for (index in chunk.minSectionY..chunk.maxSectionY) {
            val section = chunk.getSection(chunk.getSectionIndexFromSectionY(index))
            if (section.hasOnlyAir()) continue
            if (!section.maybeHas { isMolten(it) }) continue
            // The section's own *block* floor. `index` is already a section Y here, so this is the shift
            // and nothing else — round-tripping it through the index would hand back the section Y again
            // and scan sixteen blocks starting at y = -4.
            val floor = index shl SECTION_TO_BLOCKS
            for (y in maxOf(floor, lowest)..minOf(floor + BLOCKS_PER_SECTION - 1, highest)) {
                for (localX in 0..<BLOCKS_PER_SECTION) {
                    for (localZ in 0..<BLOCKS_PER_SECTION) {
                        at.set(chunk.pos.minBlockX + localX, y, chunk.pos.minBlockZ + localZ)
                        if (!isMolten(chunk.getBlockState(at))) continue
                        if (opensOnto(chunk, beside, at, localX, localZ, lowest, highest)) {
                            chunk.markPosForPostProcessing(at)
                        }
                    }
                }
            }
        }
    }

    /** Whether any neighbour of [at] inside this chunk is open air for the lava to run into. */
    private fun opensOnto(
        chunk: ChunkAccess,
        cursor: BlockPos.MutableBlockPos,
        at: BlockPos,
        localX: Int,
        localZ: Int,
        lowest: Int,
        highest: Int,
    ): Boolean {
        if (at.y > lowest && chunk.getBlockState(cursor.setWithOffset(at, Direction.DOWN)).isAir) return true
        if (at.y < highest && chunk.getBlockState(cursor.setWithOffset(at, Direction.UP)).isAir) return true
        if (localX > 0 && chunk.getBlockState(cursor.setWithOffset(at, Direction.WEST)).isAir) return true
        if (localX < BLOCKS_PER_SECTION - 1 && chunk.getBlockState(cursor.setWithOffset(at, Direction.EAST)).isAir) {
            return true
        }
        if (localZ > 0 && chunk.getBlockState(cursor.setWithOffset(at, Direction.NORTH)).isAir) return true
        return localZ < BLOCKS_PER_SECTION - 1 &&
            chunk.getBlockState(cursor.setWithOffset(at, Direction.SOUTH)).isAir
    }

    /** A section is sixteen blocks across, and sixteen tall — so its Y shifted by four is its block floor. */
    private const val BLOCKS_PER_SECTION = 16
    private const val SECTION_TO_BLOCKS = 4
}
