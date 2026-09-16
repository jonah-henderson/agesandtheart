package co.voik.agesandtheart.worldgen.fissure

import co.voik.agesandtheart.age.consequence.Collapse
import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.redstone.Orientation

/**
 * A star fissure that is **still opening** — the Age coming apart at the bottom (design §5.3).
 *
 * Everything it *is* is [StarFissureBlock]: the starfield, the fall, the way out to the overworld. The only
 * difference is that this one is not finished, and takes the ground beside it.
 *
 * **A second block rather than a state on the first, and that is the point.** The two have to be told apart
 * — an ordinary star fissure is an escape hatch that must never eat the world it is an escape from — and
 * doing it with a block means **the world is the state**. Which columns have gone is written in the blocks
 * that are there; nothing is tracked, nothing is derived from a clock, and nothing has to be reconciled if
 * a chunk unloads. That is `decisions.md`'s rule about a phenomenon on our own block, taken at its word.
 *
 * **It books its own turns rather than riding the random tick**, which is the one thing it does not borrow
 * from grass. `randomTickSpeed` is a single number for the whole server, so an Age written to come apart
 * spread at exactly the pace of one barely past the threshold. The delay is drawn rather than fixed — a
 * visibly periodic tear reads as machinery — and its mean falls as the Age's instability rises. Vanilla
 * persists scheduled ticks with the chunk, so §5.4's "the world is the state" survives intact.
 */
class CollapsingFissureBlock(properties: Properties) : StarFissureBlock(properties) {

    override fun codec(): MapCodec<out CollapsingFissureBlock> = CODEC

    /**
     * **A block written at generation gets no placement event**, so [Collapse] lays the first schedule for
     * the columns it cuts. This covers the other way in: a column the tear spread into arrives through
     * `setBlock` and is booked here.
     */
    override fun onPlace(state: BlockState, level: Level, at: BlockPos, was: BlockState, moving: Boolean) {
        if (level is ServerLevel && !was.`is`(this)) Collapse.keepTearing(level, at)
    }

    /**
     * Takes one neighbouring column, sometimes, and books the next turn.
     *
     * **Only the topmost block of a tear spreads**, which is the whole governor. A tear is a band of these
     * many blocks deep, and letting every one of them reach outward would make the rate scale with the
     * depth of the band rather than with the length of its edge — the frontier is a *surface*, and only the
     * blocks on it should behave like one. Grass does the same thing by only ever being the top block.
     *
     * **Adjacent only** (`decisions.md`): vanilla grass reaches through a 3×3×5 box, and a rule whose edge
     * a player cannot see makes a barrier they cannot trust. A player should be able to look at a tear and
     * know which block goes next.
     *
     * **Covering it changes nothing, because collapse is not preventable.** The test is whether another tear
     * stands over this one, not whether the sky does: a block buried inside the band books nothing, which is
     * what keeps a tear costing its edge rather than its volume, while a block somebody has floored over is
     * still the top of its column and goes on taking the ground beside it. What it spreads into loses
     * whatever was over it anyway ([Collapse.takeColumnBeside]).
     */
    override fun tick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (level.getBlockState(pos.above()).`is`(this)) return
        Collapse.keepTearing(level, pos)
        val towards = SIDEWAYS[random.nextInt(SIDEWAYS.size)]
        Collapse.takeColumnBeside(level, pos.relative(towards))
    }

    /**
     * The safety net over the booked chain, and it is the same one [co.voik.agesandtheart.content.LavaTubeBlock]
     * keeps for the same reason.
     *
     * A column whose booked turn was lost has nothing of its own to wake it — a tear the generator wrote
     * before its first tick, say — and without this it would stay inert for the rest of the world's life.
     *
     * Costs nothing where a turn is already booked: [Collapse.keepTearing] declines to double-book.
     */
    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (!level.getBlockState(pos.above()).`is`(this)) Collapse.keepTearing(level, pos)
    }

    private companion object {
        val CODEC: MapCodec<CollapsingFissureBlock> = simpleCodec(::CollapsingFissureBlock)

        /** A tear widens across the ground; it is already as deep as the world. */
        val SIDEWAYS = Direction.Plane.HORIZONTAL.toList()
    }
}
