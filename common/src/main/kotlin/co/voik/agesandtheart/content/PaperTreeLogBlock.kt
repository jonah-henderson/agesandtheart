package co.voik.agesandtheart.content

import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty

/**
 * Living yema: a paper tree's wood, and **the only wood that can die**, into dead yema (design §7.1.2).
 *
 * [OF_THE_TREE] marks a log the tree grew. A log a player places never has it, as a placed leaf is
 * persistent, so a house built of living yema stays living and felled wood never turns.
 *
 * Stripped by an axe as every log of ours is ([StrippableLogBlock]).
 */
class PaperTreeLogBlock(
    properties: Properties,
    strippedInto: () -> Block,
) : StrippableLogBlock(properties, strippedInto) {

    init {
        registerDefaultState(defaultBlockState().setValue(OF_THE_TREE, false))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        builder.add(OF_THE_TREE)
    }

    companion object {
        /** Grown by the tree rather than placed, and so a log the tree can lose. */
        val OF_THE_TREE: BooleanProperty = BooleanProperty.create("of_the_tree")
    }
}
