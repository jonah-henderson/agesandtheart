package co.voik.agesandtheart.worldgen

import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

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
}
