package co.voik.agesandtheart.platform

import co.voik.agesandtheart.platform.services.Flammability
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.state.BlockState

/**
 * Fabric has only vanilla's flag, which is the same answer on every face — so the side is not consulted
 * because there is nothing to consult it with. See [Flammability].
 */
class FabricFlammability : Flammability {
    override fun catchesFire(level: BlockGetter, pos: BlockPos, state: BlockState, face: Direction): Boolean =
        state.ignitedByLava()
}
