package co.voik.agesandtheart.content

import co.voik.agesandtheart.worldgen.feature.TemperedGround
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState

/**
 * Untempered temperstone, which bakes where it is left near heat.
 *
 * The outermost band of a natural formation and the form that turns up in blobs elsewhere, so the rule a
 * player reads off the ground around lava is also one they can carry away and use: move it closer and it
 * tempers, too close and it spoils.
 */
class RawTemperstoneBlock(properties: BlockBehaviour.Properties) : Block(properties) {

    override fun randomTick(state: BlockState, level: ServerLevel, at: BlockPos, random: RandomSource) {
        bake(level, at)
    }

    /** The scheduled half of [bake], which is how one baked block carries its neighbours along. */
    override fun tick(state: BlockState, level: ServerLevel, at: BlockPos, random: RandomSource) {
        bake(level, at)
    }

    /**
     * Bake this block if the heat reaches it, and pass the heat to whatever is stacked against it.
     *
     * **Waiting on a random tick apiece is too slow for a pile somebody just placed.** A random tick finds
     * one block in a section every few seconds, so a stack of raw stone converted a block at a time over
     * minutes, which reads as nothing happening rather than as a process. Baking one block schedules its
     * raw neighbours instead, so the first tick starts a wave that runs through the whole pile — the
     * conversion is still the world's to do and still takes visible time, but on the scale of the thing a
     * player is standing next to.
     */
    private fun bake(level: ServerLevel, at: BlockPos) {
        val baked = TemperedGround.bakedAt(level, at) ?: return
        level.setBlockAndUpdate(at, baked)
        for (way in Direction.entries) {
            val neighbour = at.relative(way)
            if (!level.getBlockState(neighbour).`is`(this)) continue
            if (level.blockTicks.hasScheduledTick(neighbour, this)) continue
            level.scheduleTick(neighbour, this, SPREAD_DELAY)
        }
    }

    private companion object {
        /**
         * Long enough to watch it travel, short enough that a pile is done while you are still there.
         *
         * **Halved in speed 2026-09-09** (Jonah): the wave was right in kind and a little quick to read as
         * a process rather than as a flash.
         */
        const val SPREAD_DELAY = 20
    }
}
