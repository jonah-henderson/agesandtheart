package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

/**
 * Charged arc crystal sparking where it is set down — a crystal or a block of it — every so often a spark leaping off
 * one of its faces, as copper does when lightning has just scoured it. Drawn on the client from the block's own
 * `animateTick`, so it costs nothing anywhere nobody is looking.
 */
object ArcSparks {

    fun around(level: Level, at: BlockPos, random: RandomSource) {
        if (random.nextInt(SPARKS_ONE_TICK_IN) != 0) return
        val face = Direction.getRandom(random)
        val along = face.unitVec3
        val x = at.x + HALF + along.x * OUT_OF_THE_FACE + (random.nextDouble() - HALF) * ACROSS_THE_FACE * (1.0 - kotlin.math.abs(along.x))
        val y = at.y + HALF + along.y * OUT_OF_THE_FACE + (random.nextDouble() - HALF) * ACROSS_THE_FACE * (1.0 - kotlin.math.abs(along.y))
        val z = at.z + HALF + along.z * OUT_OF_THE_FACE + (random.nextDouble() - HALF) * ACROSS_THE_FACE * (1.0 - kotlin.math.abs(along.z))
        level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z, along.x * LEAPS, along.y * LEAPS, along.z * LEAPS)
    }

    private const val SPARKS_ONE_TICK_IN = 3
    private const val HALF = 0.5
    private const val OUT_OF_THE_FACE = 0.52
    private const val ACROSS_THE_FACE = 0.9
    private const val LEAPS = 0.08
}

/** A crystal of arc crystal a bolt has charged: it sparks, and lights all about it — see [ArcSparks]. */
class ChargedArcCrystal(properties: Properties) : Block(properties) {
    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) =
        ArcSparks.around(level, pos, random)
}
