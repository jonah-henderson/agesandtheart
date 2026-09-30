package co.voik.agesandtheart.content

import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.TintedParticleLeavesBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty

/**
 * A paper tree's leaves, which say how the tree is (design §7.1.2): green while its root is in its band,
 * turning from the outside in while it is not — brown when sere, yellow going to black when drowned.
 *
 * Vanilla's leaves otherwise, distance and persistence and decay included: the distance is what "outermost"
 * reads, and a placed leaf is persistent, so what a player builds of them never turns.
 */
class PaperTreeLeavesBlock(properties: Properties) : TintedParticleLeavesBlock(PARTICLE_CHANCE, properties) {

    enum class Blight(private val key: String) : StringRepresentable {
        HEALTHY("healthy"),
        BROWNING("browning"),
        YELLOWING("yellowing"),
        BLACKENED("blackened"),
        ;

        override fun getSerializedName(): String = key
    }

    init {
        registerDefaultState(defaultBlockState().setValue(BLIGHT, Blight.HEALTHY))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        super.createBlockStateDefinition(builder)
        builder.add(BLIGHT)
    }

    companion object {
        val BLIGHT: EnumProperty<Blight> = EnumProperty.create("blight", Blight::class.java)

        /** Oak's: a leaf now and then from under a canopy. */
        private const val PARTICLE_CHANCE = 0.01f
    }
}
