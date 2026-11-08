package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.Gust
import co.voik.agesandtheart.age.phenomena.Sampling
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.BiomeColors
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ColorParticleOption
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.tags.BlockTags
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.cos
import kotlin.math.sin

/**
 * What a gust is seen coming by (`notes/wind-plan.md` §2): since nothing in the world bends in the wind, something has
 * to be put in the air for it, thickening through the warning and thickest as it hits. Client particles added
 * straight to the engine, so nothing about them crosses the network.
 *
 * **Streaks of our own by default, and dust and leaves off the ground to fall back on** (Jonah): which is [SIGN].
 */
object GustSigns {

    enum class Sign {
        /** Thin streaks racing along the wind through the open air around the player. */
        STREAKS,

        /** Dust off the ground and leaves off what grows on it, which the wind then carries (`WindBorne`). */
        DUST_AND_LEAVES,
    }

    private val SIGN = Sign.STREAKS

    /** This tick's signs of [gust] around [player], driving along [drivingDegrees], in a gale [lying] this much risen. */
    fun show(level: ClientLevel, player: LocalPlayer, drivingDegrees: Double, gust: Gust, lying: Double) {
        val random = level.random
        val towardsX = cos(Math.toRadians(drivingDegrees))
        val towardsZ = sin(Math.toRadians(drivingDegrees))
        repeat(howManyNow(random, gust.presence * lying * MOST_EACH_TICK)) {
            val upwind = Mth.nextDouble(random, -FURTHEST_UPWIND, FURTHEST_DOWNWIND)
            val across = Mth.nextDouble(random, -FURTHEST_ACROSS, FURTHEST_ACROSS)
            val x = player.x + towardsX * upwind - towardsZ * across
            val z = player.z + towardsZ * upwind + towardsX * across
            when (SIGN) {
                Sign.STREAKS -> streakAt(level, random, x, player.eyeY + Mth.nextDouble(random, LOWEST, HIGHEST), z, towardsX, towardsZ)
                Sign.DUST_AND_LEAVES -> debrisAt(level, random, x, z, towardsX, towardsZ)
            }
        }
    }

    /** A whole number of signs whose mean is [expected]. */
    private fun howManyNow(random: RandomSource, expected: Double): Int {
        val whole = expected.toInt()
        return whole + if (random.nextDouble() < expected - whole) 1 else 0
    }

    private fun streakAt(level: ClientLevel, random: RandomSource, x: Double, y: Double, z: Double, towardsX: Double, towardsZ: Double) {
        val at = BlockPos.containing(x, y, z)
        // In the open air only: not under cover, and not in water, where no wind reaches.
        if (!Sampling.openToTheSky(level, at) || !level.getFluidState(at).isEmpty) return
        val speed = Mth.nextDouble(random, SLOWEST_STREAK, FASTEST_STREAK)
        val lifetime = Mth.nextInt(random, SHORTEST_STREAK_LIFE, LONGEST_STREAK_LIFE)
        val size = Mth.nextFloat(random, SMALLEST_STREAK, LARGEST_STREAK)
        Minecraft.getInstance().particleEngine.add(WindStreak(level, x, y, z, towardsX * speed, towardsZ * speed, lifetime, size))
    }

    private fun debrisAt(level: ClientLevel, random: RandomSource, x: Double, z: Double, towardsX: Double, towardsZ: Double) {
        val surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z))
        val groundAt = BlockPos.containing(x, surface - 1.0, z)
        val ground = level.getBlockState(groundAt)
        if (ground.isAir) return
        val growsLeaves = ground.`is`(Blocks.GRASS_BLOCK) || ground.`is`(BlockTags.LEAVES)
        val lifted: ParticleOptions = if (growsLeaves && random.nextBoolean()) {
            ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, BiomeColors.getAverageFoliageColor(level, groundAt))
        } else {
            BlockParticleOption(ParticleTypes.FALLING_DUST, ground)
        }
        val y = surface + Mth.nextDouble(random, DEBRIS_LOWEST, DEBRIS_HIGHEST)
        level.addParticle(lifted, x, y, z, towardsX * DEBRIS_SPEED, DEBRIS_LIFT, towardsZ * DEBRIS_SPEED)
    }

    /** The most signs a tick at the height of a gust in a gale fully risen. */
    private const val MOST_EACH_TICK = 4.0

    /** Where around the player a sign starts: mostly upwind, so it passes them, and across and over them. */
    private const val FURTHEST_UPWIND = 24.0
    private const val FURTHEST_DOWNWIND = 4.0
    private const val FURTHEST_ACROSS = 16.0
    private const val LOWEST = -3.0
    private const val HIGHEST = 8.0

    /** In blocks a tick: twenty to thirty a second, faster than anything else that moves. */
    private const val SLOWEST_STREAK = 1.0
    private const val FASTEST_STREAK = 1.5
    private const val SHORTEST_STREAK_LIFE = 12
    private const val LONGEST_STREAK_LIFE = 22

    /** Half the length of a streak, in blocks. */
    private const val SMALLEST_STREAK = 1.0f
    private const val LARGEST_STREAK = 2.0f

    private const val DEBRIS_LOWEST = 0.2
    private const val DEBRIS_HIGHEST = 1.5
    private const val DEBRIS_SPEED = 0.4
    private const val DEBRIS_LIFT = 0.05
}
