package co.voik.agesandtheart.content

import com.mojang.serialization.MapCodec
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.IntegerProperty

/**
 * A block of arc crystal, and **how much charge a bolt left in it** (design §7.1.2).
 *
 * **The charge is a block state rather than a block entity**, for the reason `Wounds` gave up its own: a
 * player stacks these by the dozen, and an object in memory and a record in chunk NBT per block is a
 * ceiling on how big a pile can be. Eight states is a cheap price for a pile of any size.
 *
 * **A charged crystal is worth two**, and that one rule is the whole of what the lightning buys: every
 * machine it feeds reads its supply through [Arcs.supplyAround], so a bolt doubles the force of whatever
 * was already built — the pull, the push and the bite together — without any of them being told about
 * lightning.
 */
class ArcCrystalBlock(properties: Properties) : Block(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(CHARGE, FLAT))
    }

    override fun codec(): MapCodec<ArcCrystalBlock> = CODEC

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(CHARGE)
    }

    companion object {
        val CODEC: MapCodec<ArcCrystalBlock> = simpleCodec(::ArcCrystalBlock)

        /**
         * How many discharges are left in this block.
         *
         * Spent by biting and by nothing else, so a pile with no copper on it and nobody touching it keeps
         * what the storm gave it — which is what makes a charged pile a stored thing worth coming back
         * for, and a hazard when you do.
         */
        val CHARGE: IntegerProperty = IntegerProperty.create("charge", FLAT, FULLY_CHARGED)

        /** What a bolt is worth, and what an uncharged block holds. */
        const val FULLY_CHARGED = 7
        const val FLAT = 0

        /** What a charged block counts as against an ordinary one — see the class note. */
        const val CHARGED_IS_WORTH = 2
        const val ORDINARY_IS_WORTH = 1
    }
}
