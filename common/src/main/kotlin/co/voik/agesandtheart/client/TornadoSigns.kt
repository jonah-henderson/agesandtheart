package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.phenomena.Sampling
import co.voik.agesandtheart.age.phenomena.Tornado
import co.voik.agesandtheart.age.phenomena.TornadoWinds
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.TerrainParticle
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The winds about a tornado made visible (`notes/wind-plan.md` §4): streaks going round it in the open air about the
 * player, as a gust's race past ([GustSigns]), and the ground's own blocks torn up in crumbs all about its foot, or
 * spray off water, spiralling in, swung up the funnel and thrown out. Client particles added straight to the engine,
 * each turned by the same winds that move the player ([TornadoWinds]) as it goes.
 */
object TornadoSigns {

    /** This tick's signs of [tornadoes] about [player]. */
    fun show(level: ClientLevel, player: LocalPlayer, tornadoes: List<Tornado>) {
        val random = level.random
        for (tornado in tornadoes) {
            val vortex = TornadoWinds.of(tornado) ?: continue
            repeat(howManyNow(random, STREAKS_EACH_TICK * vortex.might)) { streakNear(level, random, player, tornado) }
            repeat(howManyNow(random, CRUMBS_EACH_TICK * vortex.might)) { crumbAbout(level, random, tornado, vortex) }
        }
    }

    /** A whole number of signs whose mean is [expected]. */
    private fun howManyNow(random: RandomSource, expected: Double): Int {
        val whole = expected.toInt()
        return whole + if (random.nextDouble() < expected - whole) 1 else 0
    }

    /** A streak somewhere in the open about [player], if that is inside [tornado]'s winds and outside its funnel. */
    private fun streakNear(level: ClientLevel, random: RandomSource, player: LocalPlayer, tornado: Tornado) {
        val x = player.x + Mth.nextDouble(random, -STREAKS_ABOUT, STREAKS_ABOUT)
        val y = player.eyeY + Mth.nextDouble(random, LOWEST_STREAK, HIGHEST_STREAK)
        val z = player.z + Mth.nextDouble(random, -STREAKS_ABOUT, STREAKS_ABOUT)
        val vortex = TornadoWinds.of(tornado) ?: return
        val bearings = vortex.bearingsOf(x, z)
        val isInTheWinds = bearings.out > vortex.core && bearings.out < vortex.outerReach && vortex.isBeside(y)
        val at = BlockPos.containing(x, y, z)
        if (!isInTheWinds || !Sampling.openToTheSky(level, at) || !level.getFluidState(at).isEmpty) return
        val lifetime = Mth.nextInt(random, SHORTEST_STREAK_LIFE, LONGEST_STREAK_LIFE)
        val size = Mth.nextFloat(random, SMALLEST_STREAK, LARGEST_STREAK)
        val streak = Swirling(level, tornado, x, y, z, lifetime, size)
        Minecraft.getInstance().particleEngine.add(streak)
    }

    /** A crumb of the ground torn up somewhere in [tornado]'s spiral, of whatever the ground there is. */
    private fun crumbAbout(level: ClientLevel, random: RandomSource, tornado: Tornado, vortex: TornadoWinds.Vortex) {
        val angle = random.nextDouble() * FULL_TURN
        // Even over the disc's area rather than crowded at its middle.
        val out = vortex.spiralReach * sqrt(random.nextDouble())
        val x = vortex.x + cos(angle) * out
        val z = vortex.z + sin(angle) * out
        val surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z))
        val groundAt = BlockPos.containing(x, surface - 1.0, z)
        val ground = level.getBlockState(groundAt)
        if (ground.isAir) return
        val throwAt = Mth.nextDouble(random, LOWEST_CRUMB_THROW, HIGHEST_CRUMB_THROW)
        val crumb = Crumb(level, tornado, x, surface + CRUMB_LIFTED_FROM, z, ground, groundAt, throwAt)
        crumb.lifetime = Mth.nextInt(random, SHORTEST_CRUMB_LIFE, LONGEST_CRUMB_LIFE)
        // Off water, spray: the water's own sprite washed white to pale blue rather than tinted the sea's colour.
        if (ground.fluidState.`is`(FluidTags.WATER)) {
            val white = random.nextFloat()
            crumb.setColor(Mth.lerp(white, SPRAY_BLUE_RED, 1.0f), Mth.lerp(white, SPRAY_BLUE_GREEN, 1.0f), 1.0f)
        }
        Minecraft.getInstance().particleEngine.add(crumb.scale(CRUMB_SCALE))
    }

    /** A streak going round [tornado], turned along its winds each tick. */
    private class Swirling(
        level: ClientLevel,
        private val tornado: Tornado,
        x: Double,
        y: Double,
        z: Double,
        lifetime: Int,
        size: Float,
    ) : WindStreak(level, x, y, z, 0.0, 0.0, lifetime, size) {

        init {
            steer()
        }

        override fun steer() {
            val vortex = TornadoWinds.of(tornado)
            if (!tornado.isAlive || vortex == null) return remove()
            val flow = vortex.flowAt(vortex.bearingsOf(x, y, z))
            val speed = SLOWEST_STREAK + (FASTEST_STREAK - SLOWEST_STREAK) * flow.strength
            goTowards(flow.towardsX * speed, flow.strength * STREAK_RISE, flow.towardsZ * speed)
        }
    }

    /**
     * A crumb of the ground, spiralling in along [tornado]'s winds and rising the nearer it gets, swung up the core
     * and thrown out once [throwAt] above the foot, to fall as any crumb does.
     */
    private class Crumb(
        level: ClientLevel,
        private val tornado: Tornado,
        x: Double,
        y: Double,
        z: Double,
        state: BlockState,
        groundAt: BlockPos,
        private val throwAt: Double,
    ) : TerrainParticle(level, x, y, z, 0.0, 0.0, 0.0, state, groundAt) {

        private var isThrown = false

        init {
            hasPhysics = false
        }

        override fun tick() {
            super.tick()
            if (!isThrown) steer()
        }

        private fun steer() {
            val vortex = TornadoWinds.of(tornado)
            if (!tornado.isAlive || vortex == null) {
                isThrown = true
                return
            }
            // About the funnel's middle where the crumb is, which leans and sways as it is drawn.
            val bearings = vortex.bearingsOf(x, y, z)
            if (bearings.out < vortex.coreAt(y)) return whirled(vortex, bearings)
            val flow = vortex.flowAt(bearings)
            val speed = SLOWEST_CRUMB + (FASTEST_CRUMB - SLOWEST_CRUMB) * flow.strength
            xd = flow.towardsX * speed
            yd = CRUMB_SKIMS + CRUMB_RISES * flow.strength * flow.strength
            zd = flow.towardsZ * speed
        }

        private fun whirled(vortex: TornadoWinds.Vortex, bearings: TornadoWinds.Bearings) {
            if (y - vortex.footY >= throwAt) {
                isThrown = true
                hasPhysics = true
                xd = -bearings.inX * CRUMB_THROWN_OUT + bearings.aroundX * CRUMB_WHIRLS
                zd = -bearings.inZ * CRUMB_THROWN_OUT + bearings.aroundZ * CRUMB_WHIRLS
                return
            }
            xd = bearings.aroundX * CRUMB_WHIRLS + bearings.inX * CRUMB_CENTRES
            yd = CRUMB_CLIMBS
            zd = bearings.aroundZ * CRUMB_WHIRLS + bearings.inZ * CRUMB_CENTRES
        }
    }

    private const val FULL_TURN = 2.0 * PI

    /** The bluest spray, which runs from this to white. */
    private const val SPRAY_BLUE_RED = 0.7f
    private const val SPRAY_BLUE_GREEN = 0.85f

    /** Streaks a tick about the player at a tornado's full might, and how far about them, in blocks. */
    private const val STREAKS_EACH_TICK = 10.0
    private const val STREAKS_ABOUT = 28.0
    private const val LOWEST_STREAK = -3.0
    private const val HIGHEST_STREAK = 10.0
    private const val SHORTEST_STREAK_LIFE = 10
    private const val LONGEST_STREAK_LIFE = 18
    private const val SMALLEST_STREAK = 1.0f
    private const val LARGEST_STREAK = 2.0f
    /**
     * In blocks a tick, from the outer reach to the funnel's edge — a gust's at the slowest, as fast as the funnel's
     * bands near it — and how fast one there climbs.
     */
    private const val SLOWEST_STREAK = 1.2
    private const val FASTEST_STREAK = 3.0
    private const val STREAK_RISE = 0.15

    /** Crumbs a tick about a tornado at full might. */
    private const val CRUMBS_EACH_TICK = 14.0
    private const val CRUMB_SCALE = 2.5f
    private const val CRUMB_LIFTED_FROM = 0.1
    private const val SHORTEST_CRUMB_LIFE = 60
    private const val LONGEST_CRUMB_LIFE = 140
    /** In blocks a tick along the ground, from the spiral's edge to the funnel's, and how fast it lifts. */
    private const val SLOWEST_CRUMB = 0.3
    private const val FASTEST_CRUMB = 1.0
    private const val CRUMB_SKIMS = 0.02
    private const val CRUMB_RISES = 0.25
    /** In the core: round, in toward the middle and up, and thrown out this many blocks up the funnel. */
    private const val CRUMB_WHIRLS = 0.9
    private const val CRUMB_CENTRES = 0.2
    private const val CRUMB_CLIMBS = 0.7
    private const val CRUMB_THROWN_OUT = 1.0
    private const val LOWEST_CRUMB_THROW = 10.0
    private const val HIGHEST_CRUMB_THROW = 45.0
}
