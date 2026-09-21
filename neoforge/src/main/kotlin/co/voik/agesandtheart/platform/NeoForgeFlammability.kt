package co.voik.agesandtheart.platform

import co.voik.agesandtheart.platform.services.Flammability
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.state.BlockState

/**
 * NeoForge asks the block itself, per position and per face, which is the whole reason this is a service.
 * Its own default falls back to vanilla's flag, so a block that says nothing answers as it does on Fabric.
 */
class NeoForgeFlammability : Flammability {
    override fun catchesFire(level: BlockGetter, pos: BlockPos, state: BlockState, face: Direction): Boolean =
        state.ignitedByLava(level, pos, face)
}
