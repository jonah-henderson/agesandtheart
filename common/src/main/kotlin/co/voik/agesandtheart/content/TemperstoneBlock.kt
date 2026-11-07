package co.voik.agesandtheart.content

import co.voik.agesandtheart.worldgen.feature.TemperedGround
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState

/**
 * Temperstone, which binds to itself under a piston.
 *
 * A run of it moves as one the way slime does, but it sticks only to its own kind — the same unusually
 * strong bond that lets a climber's boots hold on to it.
 *
 * How it binds lives in [BondingBlock], which NeoForge's piston reads and Fabric's `PistonStructureResolverMixin`
 * answers for.
 */
class TemperstoneBlock(properties: BlockBehaviour.Properties) : BondingBlock(properties) {

    /**
     * **Heat that reaches tempered stone spoils it**, which is the second half of the rule the ground
     * teaches and was not happening at all.
     *
     * The bands are laid at generation against the lava as it stood then, and a lava sea settles after
     * that — so tempered stone ended up sitting against lava and stayed tempered for ever, which reads as
     * the rule being decorative (Jonah, 2026-09-09, walked). It also means a player who moves a block too
     * close now sees it spoil, where before only *raw* stone answered to heat and the finished material
     * was immune to the hazard it was made in.
     *
     * **Only ever forward.** `bakedAt` answers with whatever band a position is in, raw included, so this
     * takes the scorched answer and no other: tempering is a change you made to the material and taking
     * the heat away does not undo it.
     */
    override fun randomTick(state: BlockState, level: ServerLevel, at: BlockPos, random: RandomSource) {
        val spoiled = TemperedGround.bakedAt(level, at) ?: return
        if (!spoiled.`is`(AgeContent.SCORCHED_TEMPERSTONE_BLOCK)) return
        level.setBlockAndUpdate(at, spoiled)
    }

    companion object {
        /**
         * Whether this is the bonded stuff.
         *
         * The raw and scorched forms are excluded: one has not been tempered and the other was tempered
         * past use, and neither should carry the property tempering is for.
         */
        @JvmStatic
        fun bindsToItsOwnKind(state: BlockState): Boolean = state.`is`(AgeContent.TEMPERSTONE_BLOCK)
    }
}
