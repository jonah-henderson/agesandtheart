package co.voik.agesandtheart.content

import com.mojang.serialization.MapCodec
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty

/**
 * A block of astrite, which becomes a lure when it is set high enough (design §7.1.2).
 *
 * **Whether it is aloft lives in the block state rather than being recomputed from the position.** Two
 * things fall out of that and both are the point. A lure can be *drawn* differently, since only a state
 * can pick a model; and a storm can find one for nearly nothing, because a section's palette answers
 * whether it holds any such block without reading a single position — the trick [Lures] leans on and
 * `Wounds` established.
 *
 * The one thing it cannot survive is being *moved* below the line by a piston, which would leave the state
 * lying. Nothing in the pack pushes one today, and the cost of being wrong is a dead lure rather than
 * anything unsound.
 */
class AstriteBlock(properties: BlockBehaviour.Properties) : Block(properties) {

    override fun codec(): MapCodec<AstriteBlock> = CODEC

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(ALOFT)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(ALOFT, isAloft(context.clickedPos.y))

    companion object {
        val CODEC: MapCodec<AstriteBlock> = simpleCodec(::AstriteBlock)

        /**
         * Whether this block is high enough to draw a storm down on itself.
         *
         * **A stated height rather than "above the clouds"** (Jonah): an Age can move its own clouds
         * through `CLOUD_HEIGHT`, so that rule would be invisible — a player has to be able to tell
         * whether a lure is live by reading the Y and nothing else.
         */
        val ALOFT: BooleanProperty = BooleanProperty.create("aloft")

        /**
         * How high that is. Near the ceiling on purpose: raising the material this far is the cost of the
         * lure, and it leaves enough room above to stand a platform and work on it.
         */
        const val HIGH_ENOUGH = 288

        fun isAloft(y: Int): Boolean = y >= HIGH_ENOUGH

    }
}
