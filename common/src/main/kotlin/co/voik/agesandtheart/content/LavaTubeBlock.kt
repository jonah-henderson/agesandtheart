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
 * **Everything it does happens on a random tick**, which is the rhythm the material is designed around
 * rather than a budget it is squeezed into: a caldera arrives full because its Age's shape filled it, so
 * nothing here has a crater to race, and what is left is a slow ratchet outward and a rare shot. Vanilla
 * is already paying for these visits, so a world full of buried tubes costs one block read apiece.
 */
class LavaTubeBlock(properties: BlockBehaviour.Properties) : Block(properties) {

    /**
     * Most lava tubes in an Age are buried and inert, so both errands leave early on the cheapest question
     * there is: a cluster with stone over it wells nothing and throws nothing until something digs it out.
     */
    override fun randomTick(state: BlockState, level: ServerLevel, at: BlockPos, random: RandomSource) {
        LavaTubes.well(level, at)
        LavaTubes.erupt(level, at, random)
    }
}
