package co.voik.agesandtheart.content

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.AmethystClusterBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties

/**
 * A rime crystal, in one of [RimeColour]'s eight (design §7.1.2).
 *
 * **One block per colour rather than one block with a colour**, and redstone is what settles it. A
 * comparator reads `getAnalogOutputSignal(BlockState, …)` — the *state* — so a colour kept in a data
 * component would need a block entity to hold it, and one `cliffs` Age was measured holding **4807
 * crystals**. That is 4807 block entities registering, ticking and serialising, for a number that never
 * changes. A block that simply *is* its colour costs nothing.
 *
 * **Comparator-readable, not a power source.** A crystal that emitted on its own would make every cliff
 * face in the Age a live signal, updating its neighbours for scenery. Reading it with a comparator is
 * vanilla's own idiom for "what is this block" — chests, cakes, cauldrons, jukeboxes — and leaves it inert
 * until somebody builds something out of it.
 */
class RimeCrystalBlock(val colour: RimeColour, properties: BlockBehaviour.Properties) :
    AmethystClusterBlock(CRYSTAL_HEIGHT, CRYSTAL_WIDTH, properties) {

    // Typed as the parent's own: `codec()` is invariant, so a narrower return is not an override.
    override fun codec(): MapCodec<AmethystClusterBlock> = CODEC

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        builder.add(BlockStateProperties.POWERED)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val placed = super.getStateForPlacement(context) ?: return null
        return placed.setValue(BlockStateProperties.POWERED, context.level.hasNeighborSignal(context.clickedPos))
    }

    /**
     * Lit while something is powering it.
     *
     * The brightness itself is on the block's properties, since `lightLevel` is a property of the *block*
     * and reads the state — see [AgeContent.RIME_CRYSTAL_BLOCKS]. All that is needed here is keeping the
     * state true, which nothing else will do: a crystal is not a redstone component and gets no updates of
     * its own accord.
     */
    override fun neighborChanged(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        block: Block,
        orientation: net.minecraft.world.level.redstone.Orientation?,
        movedByPiston: Boolean,
    ) {
        if (level.isClientSide) return
        val powered = level.hasNeighborSignal(pos)
        if (powered == state.getValue(BlockStateProperties.POWERED)) return
        level.setBlock(pos, state.setValue(BlockStateProperties.POWERED, powered), UPDATE_CLIENTS)
    }

    /** Its colour, as a number — see [RimeColour] for why it is contiguous from one. */
    override fun hasAnalogOutputSignal(state: BlockState): Boolean = true

    override fun getAnalogOutputSignal(state: BlockState, level: Level, pos: BlockPos, direction: Direction): Int =
        colour.signal

    companion object {
        /**
         * Shared by all eight, and unusable for reading one back — which is why the block is only ever
         * built from [AgeContent] and never from a datapack. `simpleCodec` cannot carry the colour, and
         * inventing a codec that could would be a serialisation format for something that is already
         * decided by which block you are looking at.
         */
        val CODEC: MapCodec<AmethystClusterBlock> = simpleCodec { AmethystClusterBlock(CRYSTAL_HEIGHT, CRYSTAL_WIDTH, it) }

        /**
         * **Deliberately larger than the amethyst cluster it is shaped like**, which is `7 × 3` — a spike
         * three sixteenths wide, standing seven out of the face.
         *
         * Vanilla can afford that because an amethyst cluster is a rarity you break once. These grow in
         * fields down a cliff and are *harvested*, so a hitbox you have to hunt for is felt every time
         * (Jonah, walked 2026-09-08). This still stops well short of the block behind it, so a crystal on
         * a wall can be mined without taking the wall with it.
         */
        const val CRYSTAL_HEIGHT = 11.0f
        const val CRYSTAL_WIDTH = 6.0f

        /** What one gives off standing on a cliff, and what it gives off with a signal on it. */
        const val RESTING_GLOW = 4
        /**
         * **Vanilla's brightest, and it pays for the colour.** A tint is a multiply and a multiply can only
         * darken — a wall taking a strong red loses most of its green and blue, so the surface comes out
         * dimmer the more colour it takes. The light engine is where the brightness comes back from, so a
         * crystal meant to cast a saturated colour has to be a bright source or the corner reads as a dark
         * stain rather than as something lit.
         */
        const val POWERED_GLOW = 15
    }
}
