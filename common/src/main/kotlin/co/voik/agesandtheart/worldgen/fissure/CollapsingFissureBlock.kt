package co.voik.agesandtheart.worldgen.fissure

import co.voik.agesandtheart.age.consequence.Collapse
import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.state.BlockState

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
 * **Vanilla does the scheduling.** `randomTicks()` in the properties is the whole of it: this spreads the
 * way grass, fire and sculk spread, on the same budget, tunable with `randomTickSpeed`, and costing us no
 * tick of our own. An Age nobody is in does not spread, exactly as an unloaded field does not grow grass.
 */
class CollapsingFissureBlock(properties: Properties) : StarFissureBlock(properties) {

    override fun codec(): MapCodec<out CollapsingFissureBlock> = CODEC

    /**
     * Takes one neighbouring column, sometimes.
     *
     * **Only the topmost block of a tear spreads**, which is the whole governor. A tear is a band of these
     * many blocks deep, and letting every one of them reach outward would make the rate scale with the
     * depth of the band rather than with the length of its edge — the frontier is a *surface*, and only the
     * blocks on it should behave like one. Grass does the same thing by only ever being the top block.
     *
     * **Adjacent only** (`decisions.md`): vanilla grass reaches through a 3×3×5 box, and a rule whose edge
     * a player cannot see makes a barrier they cannot trust. A player should be able to look at a tear and
     * know which block goes next.
     */
    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (!level.getBlockState(pos.above()).isAir) return
        val towards = SIDEWAYS[random.nextInt(SIDEWAYS.size)]
        Collapse.takeColumnBeside(level, pos.relative(towards))
    }

    private companion object {
        val CODEC: MapCodec<CollapsingFissureBlock> = simpleCodec(::CollapsingFissureBlock)

        /** A tear widens across the ground; it is already as deep as the world. */
        val SIDEWAYS = Direction.Plane.HORIZONTAL.toList()
    }
}
