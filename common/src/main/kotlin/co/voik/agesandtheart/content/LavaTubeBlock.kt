package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.redstone.Orientation

/**
 * The vent in a crater floor: it wells lava up, and it is what throws (design §7.1.2).
 *
 * Blast-resistant on purpose. A volcano's projectiles crater the ground they land on, and a volcano that
 * could destroy its own vents would quietly switch itself off — the decision to stop one belongs to
 * whoever is standing there, so mining the tubes is the permanent answer and plugging them the reversible
 * one.
 *
 * **Throwing rides the random tick and welling does not, because the two want opposite rhythms.** A shot
 * is meant to be rare and unscheduled, which is exactly what a random tick is; but a block is visited only
 * about once a minute, and a lava supply that takes a minute to show its first block reads as broken —
 * which is what a walk found when a tube laid on flat ground appeared to do nothing at all.
 *
 * So welling is **event-driven and then self-chaining**: being placed starts it, a neighbour changing
 * starts it, and each pass that puts lava somewhere books the next. That is also what makes a buried
 * cluster a hazard rather than a curiosity — breaking the rock over one is a neighbour change, so it
 * begins flooding what you just opened at once instead of on whatever tick vanilla gets round to it.
 *
 * **The chain ends by itself**, which is why it can afford to exist: the reach bounds how much lava a
 * tube may ever harden, so a pass that finds nowhere left to put a block books nothing and the tube goes
 * back to costing one block read per visit.
 *
 * **Ending on "nowhere left" and not on "put nothing in"**, which are different answers and were once the
 * same one — see [LavaTubes.Welling]. A pass waiting on vanilla's own flow books the next tick exactly as
 * a pass that laid a block does; only a plugged or a full tube stops.
 */
class LavaTubeBlock(properties: BlockBehaviour.Properties) : Block(properties) {

    override fun onPlace(state: BlockState, level: Level, at: BlockPos, was: BlockState, moving: Boolean) {
        if (level is ServerLevel) startWelling(level, at)
    }

    /** Something changed beside it — most importantly the rock over a buried cluster being broken. */
    override fun neighborChanged(
        state: BlockState,
        level: Level,
        at: BlockPos,
        neighbour: Block,
        orientation: Orientation?,
        moving: Boolean,
    ) {
        if (level is ServerLevel) startWelling(level, at)
    }

    override fun tick(state: BlockState, level: ServerLevel, at: BlockPos, random: RandomSource) {
        val found = LavaTubes.well(level, at)
        if (found.worthComingBack) keepWelling(level, at, found.comeBackIn)
    }

    /**
     * Most lava tubes in an Age are buried and inert, so this leaves early on the cheapest question there
     * is: a cluster with stone over it wells nothing and throws nothing until something digs it out.
     *
     * Welling is booked from here too, as the safety net over the event-driven half — a chunk can load
     * with work already waiting for it, and nothing changed to say so.
     */
    override fun randomTick(state: BlockState, level: ServerLevel, at: BlockPos, random: RandomSource) {
        LavaTubes.erupt(level, at, random)
        startWelling(level, at)
    }

    /** The ordinary cadence, which is the one a tube that is getting somewhere keeps — see [LavaTubes.Welling]. */
    private fun startWelling(level: ServerLevel, at: BlockPos) {
        if (LavaTubes.plugged(level, at)) return
        keepWelling(level, at, LavaTubes.Welling.PLACED.comeBackIn)
    }

    private fun keepWelling(level: ServerLevel, at: BlockPos, inTicks: Int) {
        if (level.blockTicks.hasScheduledTick(at, this)) return
        level.scheduleTick(at, this, inTicks)
    }
}
