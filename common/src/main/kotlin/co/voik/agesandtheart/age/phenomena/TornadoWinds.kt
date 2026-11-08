package co.voik.agesandtheart.age.phenomena

import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.item.PrimedTnt
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.vehicle.VehicleEntity
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The winds about a tornado (`notes/wind-plan.md` §4), as a real vortex's: going round anticlockwise seen from above,
 * fastest at the funnel's edge and falling away outside it, spiralling inward, and straight up inside it.
 *
 * Four rings. Out to [Vortex.outerReach], a push going round like a gale's, which shelter answers. Inside
 * [Vortex.spiralReach], a strong spiral inward that drags creatures and items and shoves players off their feet.
 * Inside [Vortex.orbitReachAt], everything is held off the ground and swung round the funnel, drawn in slowly at first
 * and faster the nearer it gets (Jonah). Once in the core, the funnel itself, it is held there, shot up the funnel
 * along its lean and flung out of the top a long way sideways to fall where it lands: **it hurts only by the fall**
 * (Jonah). The wider the tornado, the fiercer ([Vortex.fierceness]).
 *
 * Both sides read the same tornado: the server moves what is not a player ([blowOn]), each client its own player.
 */
object TornadoWinds {

    /** One tornado's winds at a moment, about its foot. */
    class Vortex(
        val x: Double,
        val footY: Double,
        val z: Double,
        val core: Double,
        val top: Double,
        val might: Double,
        private val topCore: Double = core,
        private val headingRadians: Float = 0.0f,
        private val roped: Float = 0.0f,
        private val time: Double = 0.0,
        private val phase: Float = 0.0f,
    ) {
        val spiralReach = core * SPIRAL_PER_CORE + SPIRAL_BEYOND
        val outerReach = core * OUTER_PER_CORE + OUTER_BEYOND

        /** How hard it drives things round: its [might], and up to half again for the widest. */
        val fierceness: Double = might * (1.0 + WIDEST_IS_FIERCER_BY * Mth.inverseLerp(core, Tornado.NARROWEST_FOOT, Tornado.WIDEST_FOOT).coerceIn(0.0, 1.0))

        /** How [x], [z] stands to the foot: how far out, which way round and which way in. */
        fun bearingsOf(atX: Double, atZ: Double): Bearings {
            val awayX = atX - x
            val awayZ = atZ - z
            val out = sqrt(awayX * awayX + awayZ * awayZ)
            if (out < ON_THE_AXIS) return Bearings(out, aroundX = 1.0, aroundZ = 0.0, inX = 0.0, inZ = 0.0)
            return Bearings(out, aroundX = awayZ / out, aroundZ = -awayX / out, inX = -awayX / out, inZ = -awayZ / out)
        }

        /**
         * How [x], [z] stands to the funnel's middle at height [y], which leans ahead of the foot and sways as it climbs,
         * as [Tornado.middleOffset] has it.
         */
        fun bearingsOf(atX: Double, atY: Double, atZ: Double): Bearings {
            val (offsetX, offsetZ) = Tornado.middleOffset(wayUp(atY).toFloat(), (top - footY).toFloat(), headingRadians, roped, time, phase)
            return Vortex(x + offsetX, footY, z + offsetZ, core, top, might).bearingsOf(atX, atZ)
        }

        /** Half the funnel's side at height [y], flaring toward the cloud as it is drawn. */
        fun coreAt(y: Double): Double = core + (topCore - core) * wayUp(y).pow(Tornado.FLARE.toDouble())

        /** How far the orbit reaches at height [y], out from a funnel as wide as it is there. */
        fun orbitReachAt(y: Double): Double = coreAt(y) + core * ORBIT_PER_CORE + ORBIT_BEYOND

        private fun wayUp(y: Double): Double = ((y - footY) / (top - footY)).coerceIn(0.0, 1.0)

        /** Whether height [y] is beside the funnel, from a little under its foot to its top. */
        fun isBeside(y: Double): Boolean = y >= footY - UNDER_THE_FOOT && y <= top

        /**
         * Which way the air moves along the ground at [bearings] outside the core, as a unit vector across, and how
         * hard from nought to one: going round, and turned further inward the nearer the funnel.
         */
        fun flowAt(bearings: Bearings): Flow {
            val nearness = nearnessOf(bearings.out)
            val inflow = Mth.lerp(nearness, LEAST_INFLOW, MOST_INFLOW)
            val towardsX = bearings.aroundX * cos(inflow) + bearings.inX * sin(inflow)
            val towardsZ = bearings.aroundZ * cos(inflow) + bearings.inZ * sin(inflow)
            return Flow(towardsX, towardsZ, might * nearness)
        }

        /** From nought at the outer reach to one at the funnel's edge, rising faster the nearer. */
        fun nearnessOf(out: Double): Double {
            val across = ((outerReach - out) / (outerReach - core)).coerceIn(0.0, 1.0)
            return across * across
        }
    }

    /** Where a point stands to a tornado's foot. */
    data class Bearings(val out: Double, val aroundX: Double, val aroundZ: Double, val inX: Double, val inZ: Double)

    /** The way the air goes across the ground, and how hard. */
    data class Flow(val towardsX: Double, val towardsZ: Double, val strength: Double)

    /**
     * Something taken up into a core: the height it is thrown out at, when and where it was taken, and when it was
     * thrown and how fast across.
     */
    data class Lift(
        val throwAtY: Double,
        val takenAt: Long,
        val takenAtY: Double,
        val thrownAt: Long? = null,
        val flungSpeed: Double = 0.0,
    )

    /** [tornado]'s winds now, or null while it has none: before its funnel is down. */
    fun of(tornado: Tornado): Vortex? {
        val down = ((tornado.descended - BLOWS_FROM) / (1.0f - BLOWS_FROM)).coerceIn(0.0f, 1.0f)
        val drawnBack = 1.0 - tornado.roped
        val might = Mth.smoothstep(down) * drawnBack * drawnBack
        if (might <= 0.0) return null
        return shapeOf(tornado, might)
    }

    /** Where [tornado]'s winds would blow and how far, whether or not they blow yet, at [might]. */
    fun shapeOf(tornado: Tornado, might: Double): Vortex {
        val core = Tornado.footHalfWidthOf(tornado.footHalfWidth, tornado.roped).toDouble()
        val topCore = Tornado.topHalfWidthOf(tornado.footHalfWidth, tornado.roped).toDouble()
        return Vortex(
            tornado.x, tornado.y, tornado.z, core, Tornado.OVERCAST_FLOOR, might, topCore,
            headingRadians = tornado.yRot * Mth.DEG_TO_RAD,
            roped = tornado.roped,
            time = tornado.level().gameTime.toDouble(),
            phase = tornado.phase,
        )
    }

    /**
     * [entity] in [vortex]'s winds this tick, as [exposed] to the sky as it stands. [lift] is how it was taken up last
     * tick, and what is returned is how it is now: null once it is not in the core, or has flown long enough after
     * being thrown to be left to fall.
     */
    fun carry(entity: Entity, vortex: Vortex, lift: Lift?, now: Long, random: RandomSource, exposed: Float): Lift? {
        if (lift?.thrownAt != null) return fly(entity, lift, now)
        if (!vortex.isBeside(entity.y)) return null
        // Once taken up it is held, wherever the funnel's travel and lean have put it.
        if (lift != null) return takenUp(entity, vortex, vortex.bearingsOf(entity.x, entity.y, entity.z), lift, now)
        // About the funnel's middle where it is, which leans ahead and flares wider the higher.
        val bearings = vortex.bearingsOf(entity.x, entity.y, entity.z)
        if (bearings.out >= vortex.outerReach) return null
        val isInTheCore = bearings.out < vortex.coreAt(entity.y) && exposed > NO_SKY
        if (isInTheCore) return takenUp(entity, vortex, bearings, drawnFor(entity, vortex, now, random), now)
        if (entity.isInWater) return null
        if (bearings.out < vortex.orbitReachAt(entity.y) && exposed > NO_SKY) {
            orbiting(entity, vortex, bearings)
            return null
        }
        val flow = vortex.flowAt(bearings)
        val push = flow.strength * PUSH * (vortex.fierceness / vortex.might) * exposed
        val rise = if (bearings.out < vortex.spiralReach) flow.strength * SPIRAL_RISE * exposed else 0.0
        entity.push(flow.towardsX * push, rise, flow.towardsZ * push)
        return null
    }

    /**
     * Swung round the funnel and drawn in: slowly at the orbit's edge, and faster by the same factor for every block
     * nearer, to [MOST_DRAWN_IN] at the funnel's. Taken quickly up over the trees, and from there, or from whatever
     * height it came in at, climbing slowly, the faster the nearer.
     */
    private fun orbiting(entity: Entity, vortex: Vortex, bearings: Bearings) {
        val ground = entity.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(entity.x), Mth.floor(entity.z))
        val overTheGround = entity.y - ground
        val core = vortex.coreAt(entity.y)
        val orbitReach = vortex.orbitReachAt(entity.y)
        val inwardShare = ((orbitReach - bearings.out) / (orbitReach - core)).coerceIn(0.0, 1.0)
        val climb = if (overTheGround < OVER_THE_TREES) {
            ((OVER_THE_TREES - overTheGround) * RISES_TO_THE_TREES).coerceAtMost(FASTEST_TO_THE_TREES)
        } else {
            Mth.lerp(inwardShare, SLOWEST_CLIMB, FASTEST_CLIMB)
        }
        val round = ORBIT_OUTSIDE * vortex.fierceness
        val inward = LEAST_DRAWN_IN * (MOST_DRAWN_IN / LEAST_DRAWN_IN).pow(inwardShare) * vortex.might
        val wanted = Vec3(
            bearings.aroundX * round + bearings.inX * inward,
            HOLDS_UP + climb * CLIMB_GRIPPED,
            bearings.aroundZ * round + bearings.inZ * inward,
        )
        entity.push(wanted.subtract(entity.deltaMovement).scale(ORBIT_GRIP))
    }

    /** How high something just taken up into [vortex] will be thrown out: most of the way up, by chance. */
    private fun drawnFor(entity: Entity, vortex: Vortex, now: Long, random: RandomSource): Lift {
        val share = Mth.nextDouble(random, LOWEST_THROW, HIGHEST_THROW)
        return Lift(vortex.footY + (vortex.top - vortex.footY) * share, now, takenAtY = entity.y)
    }

    /**
     * Swung up the core in a tight spiral about its middle, until it reaches the height it is thrown out at or has been
     * up [LONGEST_LIFT]: then flung out of the top, outward and round.
     */
    private fun takenUp(entity: Entity, vortex: Vortex, bearings: Bearings, lift: Lift, now: Long): Lift {
        val acrossX = -bearings.inX * FLUNG_OUT + bearings.aroundX * FLUNG_ROUND
        val acrossZ = -bearings.inZ * FLUNG_OUT + bearings.aroundZ * FLUNG_ROUND
        val across = sqrt(acrossX * acrossX + acrossZ * acrossZ)
        val speed = FLING * vortex.fierceness
        val flung = Vec3(acrossX / across * speed, THROW_UP * vortex.might, acrossZ / across * speed)
        val isThrownNow = entity.y >= lift.throwAtY || now - lift.takenAt >= LONGEST_LIFT
        if (isThrownNow) {
            setMoving(entity, flung)
            return lift.copy(thrownAt = now, flungSpeed = speed)
        }
        val core = vortex.coreAt(entity.y)
        val inward = CENTRING * ((bearings.out - SPIRALS_AT * core) / core).coerceIn(-1.0, MOST_CENTRING_CORES)
        val round = ORBIT * vortex.fierceness
        val rising = Vec3(
            bearings.aroundX * round + bearings.inX * inward,
            LIFT * vortex.might,
            bearings.aroundZ * round + bearings.inZ * inward,
        )
        // Over the last of the climb it turns from rising to flung, so it leaves the top in an arc.
        val climbed = ((entity.y - lift.takenAtY) / (lift.throwAtY - lift.takenAtY)).coerceIn(0.0, 1.0)
        val turning = Mth.smoothstep(((climbed - TURNS_OUT_FROM) / (1.0 - TURNS_OUT_FROM)).coerceIn(0.0, 1.0).toFloat()).toDouble()
        val wanted = rising.lerp(flung, turning)
        entity.push(wanted.subtract(entity.deltaMovement).scale(GRIP))
        return lift
    }

    /**
     * One tick of [entity] flying after [lift] threw it, the air's drag across nearly all taken away so it carries on
     * as flung and falls in a long arc, until it lands or [LEFT_TO_FLY] has passed. What is returned is null once it
     * has. Asked wherever it has flown to, however far from the tornado that threw it.
     */
    fun fly(entity: Entity, lift: Lift, now: Long): Lift? {
        val thrownAt = lift.thrownAt ?: return null
        val sinceThrown = now - thrownAt
        val hasLanded = sinceThrown > 0 && (entity.onGround() || entity.isInWater)
        if (hasLanded || sinceThrown >= LEFT_TO_FLY) return null
        // Where it is not ticked, its drag is not taken either, and what was given back would build on itself. What
        // is not alive keeps its own drag, which is little: thrown further, it would land beyond the ground the
        // server keeps ticking and hang there in the air.
        val isFrozen = (entity.level() as? ServerLevel)?.isPositionEntityTicking(entity.blockPosition()) == false
        val moving = entity.deltaMovement
        val across = sqrt(moving.x * moving.x + moving.z * moving.z)
        if (isFrozen || across < STILL || entity !is LivingEntity) return lift
        val drag = LIVING_AIR_DRAG
        val kept = 1.0 - (1.0 - drag) * DRAG_LEFT
        // Never past the speed it was flung at, whatever else has pushed it.
        val restoredTo = minOf(across * kept / drag, lift.flungSpeed / drag)
        val restored = (restoredTo / across - 1.0).coerceAtLeast(0.0)
        entity.push(moving.x * restored, 0.0, moving.z * restored)
        return lift
    }

    /** Sets [entity] moving at [velocity], through `push` so the server sends it on. */
    private fun setMoving(entity: Entity, velocity: Vec3) = entity.push(velocity.subtract(entity.deltaMovement))

    /**
     * Whether a tornado holds [entity] up, in its orbit or its core, where a server must not take it for flying.
     * Read only while a player floats, so it costs nothing on the ground.
     */
    @JvmStatic
    fun isHeldUp(entity: Entity): Boolean {
        val near = entity.boundingBox.inflate(MOST_REACH, entity.level().height.toDouble(), MOST_REACH)
        fun holds(tornado: Tornado): Boolean {
            val vortex = of(tornado) ?: return false
            val isWithinTheOrbit = vortex.bearingsOf(entity.x, entity.y, entity.z).out < vortex.orbitReachAt(entity.y) + HELD_UP_SLACK
            return isWithinTheOrbit && vortex.isBeside(entity.y)
        }
        return entity.level().getEntitiesOfClass(Tornado::class.java, near).any(::holds)
    }

    /** One tick of [tornado]'s winds on everything about it but players, whose own clients move them. */
    fun blowOn(level: ServerLevel, tornado: Tornado, lifted: MutableMap<Entity, Lift>) {
        val vortex = of(tornado)
        if (vortex == null) {
            lifted.clear()
            return
        }
        val reach = vortex.outerReach
        val around = AABB(vortex.x - reach, vortex.footY - UNDER_THE_FOOT, vortex.z - reach, vortex.x + reach, vortex.top, vortex.z + reach)
        val now = level.gameTime
        val seen = HashSet<Entity>()
        for (entity in level.getEntities(tornado, around, ::isCarried)) {
            seen += entity
            val exposed = Sampling.exposureAt(level, entity.blockPosition())
            val next = carry(entity, vortex, lifted[entity], now, level.random, exposed)
            if (next == null) lifted.remove(entity) else lifted[entity] = next
        }
        for ((entity, lift) in lifted.entries.toList()) {
            if (entity in seen || !entity.isAlive) continue
            val flying = fly(entity, lift, now) ?: continue
            seen += entity
            lifted[entity] = flying
        }
        lifted.keys.retainAll(seen)
    }

    /**
     * What the winds move, besides players: what lives, what was dropped, thrown or lit, what fell, and what is ridden
     * — not what rides it, which goes with it, nor anything too great to be moved.
     */
    private fun isCarried(entity: Entity): Boolean {
        val isLoose = entity is LivingEntity || entity is ItemEntity || entity is ExperienceOrb ||
            entity is FallingBlockEntity || entity is VehicleEntity || entity is Projectile || entity is PrimedTnt
        val isBeyondIt = entity is Player || entity is EnderDragon || entity is WitherBoss || entity is Tornado
        val isHeld = entity.isPassenger || entity.isSpectator || entity.isNoGravity
        return isLoose && !isBeyondIt && !isHeld
    }

    /** It blows once the funnel is this far down, at full might once it is on the ground. */
    private const val BLOWS_FROM = 0.7f

    /**
     * The orbit reaches past the funnel's edge, at every height, a block and nine tenths of the foot's width: eight for
     * the narrowest, twenty-eight for the widest. The spiral reaches to two and a half cores and eight blocks more; the
     * gale's push to four and thirty-two.
     */
    private const val ORBIT_PER_CORE = 1.8
    private const val ORBIT_BEYOND = 1.0
    private const val SPIRAL_PER_CORE = 2.5
    private const val SPIRAL_BEYOND = 8.0
    private const val OUTER_PER_CORE = 4.0
    private const val OUTER_BEYOND = 32.0
    private const val UNDER_THE_FOOT = 4.0

    /** How far inward of going round the air turns, at the outer reach and at the funnel's edge, in radians. */
    private const val LEAST_INFLOW = 0.25
    private const val MOST_INFLOW = 0.75

    /**
     * The push at the funnel's edge, in blocks a tick each tick, falling away outward as [Vortex.nearnessOf]: more than
     * twice a sprint's gain there, so nobody walks out of it, and less than a sprint's from about two thirds of the
     * way out, so further out it can be fought.
     */
    private const val PUSH = 0.5
    /** How much fiercer the widest tornado is than the narrowest. */
    private const val WIDEST_IS_FIERCER_BY = 0.5
    /** And in the spiral a little lift with it, so what it drags skips over the ground. */
    private const val SPIRAL_RISE = 0.03

    /**
     * Further than the widest tornado's orbit reaches at its top, leaned as far as a rope leans, and how far past its
     * orbit something is still held.
     */
    private const val MOST_REACH = 128.0
    private const val HELD_UP_SLACK = 8.0

    /**
     * In the orbit, in blocks a tick: what it is brought towards each tick, by [ORBIT_GRIP] of the difference — round,
     * a little in, and climbing. [HOLDS_UP] is what holds its own weight against gravity at that grip, and
     * [CLIMB_GRIPPED] what turns a wanted climb into what to aim for.
     */
    private const val ORBIT_OUTSIDE = 0.9
    private const val LEAST_DRAWN_IN = 0.07
    private const val MOST_DRAWN_IN = 1.0
    private const val HOLDS_UP = 0.2
    private const val CLIMB_GRIPPED = 1.05
    private const val ORBIT_GRIP = 0.4
    /** Taken up quickly to this many blocks over the ground, over the trees, at up to this much a tick. */
    private const val OVER_THE_TREES = 12.0
    private const val RISES_TO_THE_TREES = 0.15
    private const val FASTEST_TO_THE_TREES = 0.8
    /** Above them, or above where it came in, it climbs this slowly at the orbit's edge and this fast at the funnel's. */
    private const val SLOWEST_CLIMB = 0.01
    private const val FASTEST_CLIMB = 0.06

    /**
     * In the core, in blocks a tick: what it is brought towards each tick, by [GRIP] of the difference, going round,
     * up and in toward the middle — about two blocks a tick up, so the top is reached inside [LONGEST_LIFT].
     */
    private const val ORBIT = 1.0
    private const val LIFT = 2.2
    private const val CENTRING = 0.4
    /** It spirals up a third of the way out from the middle, held there from up to three cores out. */
    private const val SPIRALS_AT = 0.33
    private const val MOST_CENTRING_CORES = 3.0
    private const val GRIP = 0.5
    /**
     * It turns from rising to flung over the last of its climb from here, and is thrown out of the top: from nineteen
     * to all of twenty parts of the way up the funnel.
     */
    private const val TURNS_OUT_FROM = 0.7
    private const val LOWEST_THROW = 0.95
    private const val HIGHEST_THROW = 1.0
    /** Thrown at the latest after five seconds up, wherever it has got to. */
    private const val LONGEST_LIFT = 100L
    /**
     * Flung across at [FLING] blocks a tick, as fierce as the tornado, a little more outward than round, and up; then
     * a creature or player is left only [DRAG_LEFT] of the air's drag across (`LivingEntity.travel`'s) for [LEFT_TO_FLY]
     * ticks or until it lands; anything else keeps its own, which is little.
     * Thrown from the top, a player lands about sixty-five blocks off the narrowest and a hundred off the widest: inside
     * an eight-chunk view of the tornado with time to watch it go (Jonah).
     */
    private const val FLING = 2.0
    private const val FLUNG_OUT = 1.0
    private const val FLUNG_ROUND = 0.6
    private const val THROW_UP = 0.6
    private const val DRAG_LEFT = 0.3
    private const val LIVING_AIR_DRAG = 0.91
    private const val LEFT_TO_FLY = 300L

    private const val NO_SKY = 0.0f
    private const val STILL = 1.0e-4
    private const val ON_THE_AXIS = 1.0e-3
}
