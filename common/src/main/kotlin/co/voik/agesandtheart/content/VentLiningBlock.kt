package co.voik.agesandtheart.content

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.AmethystClusterBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState

/**
 * The hot lining of a deep-sea vent, which precipitates gloomgrit onto its own faces (design §7.1.2).
 *
 * **Budding amethyst's rule, with one substitution that carries the whole design.** Vanilla's block grows
 * a cluster into air or into a full water source; this one grows **only into deep water**. That single
 * change answers the thing the material most needed answering: a player who walls the chamber off and
 * puts air in it to work at leisure has taken the abyss out of it, and the lining stops producing. There
 * is no rule about sealing vents, no timer and nothing to enforce — the counterplay simply stops paying,
 * because what deposits gloomgrit is the pressure it was deposited under.
 *
 * It also means the yield and the hazard cannot be separated. `DeepWater` takes the abyss out for
 * [DeepWater.DEPRESSURISED_UNDER_AIR] blocks beneath any air pocket, so an air pocket in the chamber roof
 * sterilises the lining under it by exactly the rule that makes the pocket safe to stand in.
 *
 * **Not obtainable, like the block it is modelled on.** Budding amethyst drops nothing even under silk
 * touch, and for the same reason: a player who could carry the source home would never come back down.
 */
class VentLiningBlock(properties: BlockBehaviour.Properties) : Block(properties) {

    override fun codec(): MapCodec<VentLiningBlock> = CODEC

    /**
     * Occasionally, onto one face.
     *
     * One face per tick rather than a survey of all six: the die is rolled for a *direction* and then the
     * face is asked, so a lining block with one open face is six times slower than one standing in open
     * abyss. That is vanilla's own arrangement and it is the right one here — the wall of a wide chamber
     * should out-produce a block buried in the neck.
     */
    override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
        if (random.nextInt(BUDS_ONE_TIME_IN) != 0) return
        val way = Direction.entries[random.nextInt(Direction.entries.size)]
        val onto = pos.relative(way)
        if (!openAbyss(level, onto)) return
        val cluster = AgeContent.GLOOMGRIT_CLUSTER.defaultBlockState()
            .setValue(AmethystClusterBlock.FACING, way)
        level.setBlockAndUpdate(onto, DeepWaterLogging.holding(cluster))
    }

    /**
     * Whether [onto] is open abyss a cluster could stand in.
     *
     * **Asked of the fluid tag and not of the block**, so the flowing half and any abyssal fluid a pack
     * adds answer the same way — and so that a *deep-waterlogged* stair somebody placed is correctly
     * refused, since the cluster would have nowhere to go. A full source of the abyss in a liquid block is
     * the whole of what a bud needs.
     */
    private fun openAbyss(level: ServerLevel, onto: BlockPos): Boolean {
        if (level.getBlockState(onto).block !is LiquidBlock) return false
        val standing = level.getFluidState(onto)
        return standing.`is`(DeepWater.DEEP_WATER) && standing.isSource
    }

    companion object {
        val CODEC: MapCodec<VentLiningBlock> = simpleCodec(::VentLiningBlock)

        /**
         * How often a roll comes good — **and the rate is not what caps the yield**, saturation is.
         *
         * A random-ticking block is reached about once every 1365 ticks at vanilla's tick speed, so one
         * face buds roughly every nine minutes at this figure. A chamber has a hundred-odd open faces and
         * every one of them stops once it carries a cluster, so what this number really sets is **how long
         * a picked-over chamber takes to be worth returning to**, which is the thing to walk it against.
         *
         * UNWALKED. This is the dial.
         */
        private const val BUDS_ONE_TIME_IN = 8
    }
}
