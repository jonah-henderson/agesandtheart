package co.voik.agesandtheart.platform.services

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.state.BlockState

/**
 * Whether fire arriving from a given side would set a block alight — **a question the two loaders answer
 * differently**, which is why it is a service rather than a call.
 *
 * Vanilla asks it of the block state alone: `ignitedByLava()` reads a flag set once in the block's
 * properties, so the answer is the same wherever the block ever stands. NeoForge deprecates that and
 * replaces it with `ignitedByLava(level, pos, face)`, because a block may reasonably catch on one face and
 * not another, or only where it is placed.
 *
 * **That difference is real and cannot be restated**, so it does not belong in
 * [co.voik.agesandtheart.compat.VanillaThatMoved] — there is no one expression that says it on both
 * loaders. It matters here more than it would in most mods: an Age is made of whatever blocks its sentence
 * reached, other mods' included, and those are exactly the blocks with a reason to answer per face.
 *
 * **NeoForge's method is named for lava and the question is not.** Its own wording is "called when lava is
 * updating", but what it actually asks — can fire take hold on this face of this block — is the same
 * question an Age's burning sky asks of the ground under it, which is the only caller here and has no lava
 * in it anywhere. The name is vanilla's history rather than a limit on the meaning.
 *
 * Fabric's implementation is the flag, because on Fabric there is nothing else to ask.
 */
interface Flammability {
    /**
     * Whether fire coming from [face] would set the block at [pos] alight.
     *
     * [face] is the side the fire arrives *from*, as NeoForge names it — so a sky that is burning asks
     * about [Direction.UP], and lava under a block would ask the same.
     */
    fun catchesFire(level: BlockGetter, pos: BlockPos, state: BlockState, face: Direction): Boolean
}
