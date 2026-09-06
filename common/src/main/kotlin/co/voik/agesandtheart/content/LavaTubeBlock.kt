package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState

/**
 * The vent in a caldera floor: it wells lava up, and it is what throws (design §7.1.2).
 *
 * Blast-resistant on purpose. A volcano's projectiles crater the ground they land on, and a volcano that
 * could destroy its own vents would quietly switch itself off — the decision to stop one belongs to
 * whoever is standing there, so mining the tubes is the permanent answer and plugging them the reversible
 * one.
 *
 * **The pour chains its own ticks.** A random tick is far too sparse to fill a crater and too precious to
 * spend on one, so it throws once and hands the filling to a scheduled tick, which keeps rescheduling
 * itself for as long as it is still finding somewhere to put lava. A full caldera pours nothing and the
 * chain ends, so a finished volcano costs a visit every few seconds and nothing else.
 */
class LavaTubeBlock(properties: BlockBehaviour.Properties) : Block(properties) {

    /**
     * Most lava tubes in an Age are buried and inert, so this leaves early on the cheapest question there
     * is: a cluster with stone over it wells nothing and throws nothing until something digs it out.
     */
    override fun randomTick(state: BlockState, level: ServerLevel, at: BlockPos, random: RandomSource) {
        if (LavaTubes.plugged(level, at)) return
        LavaTubes.erupt(level, at)
        keepFilling(level, at)
    }

    override fun tick(state: BlockState, level: ServerLevel, at: BlockPos, random: RandomSource) {
        if (LavaTubes.pour(level, at) > NOTHING_LEFT_TO_DO) keepFilling(level, at)
    }

    private fun keepFilling(level: ServerLevel, at: BlockPos) {
        if (level.blockTicks.hasScheduledTick(at, this)) return
        level.scheduleTick(at, this, FILL_DELAY)
    }

    private companion object {
        /** Short, because a crater is thousands of blocks and a lake that creeps in reads as a bug. */
        const val FILL_DELAY = 2

        const val NOTHING_LEFT_TO_DO = 0
    }
}
