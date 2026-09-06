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
 */
class LavaTubeBlock(properties: BlockBehaviour.Properties) : Block(properties) {

    override fun randomTick(state: BlockState, level: ServerLevel, at: BlockPos, random: RandomSource) {
        LavaTubes.wellUp(level, at)
    }
}
