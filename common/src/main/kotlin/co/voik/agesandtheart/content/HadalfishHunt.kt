package co.voik.agesandtheart.content

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.level.LevelReader
import net.minecraft.world.phys.Vec3
import java.util.EnumSet
import kotlin.math.cos
import kotlin.math.sin

/**
 * How a hadalfish hunts: it circles you, then it comes.
 *
 * **One goal rather than four, because the phases share a clock and a victim.** Circling, charging,
 * striking and withdrawing are one continuous piece of behaviour with a memory — how long it has been
 * orbiting, how many bites it has left in this run — and splitting them across goals would have meant that
 * state living on the entity where anything could reach it, plus four `canUse` predicates written to not
 * contradict each other.
 *
 * **It drives velocity directly and stops the navigation to do it.** `GuardianMoveControl` only acts while
 * a path is running, so a stopped navigation leaves the field clear — and a pathfinder is the wrong tool
 * for both halves of this anyway: an orbit is a position that moves every tick, and a charge wants a speed
 * no `MOVEMENT_SPEED` attribute should have to carry.
 *
 * **The circle is the telegraph, and it is the whole of the counterplay.** A charge that came without
 * warning would just be damage; a charge you can see winding up is a thing to get your back to a wall for.
 */
class HadalfishHunt(private val fish: Hadalfish) : Goal() {

    private enum class Phase { CIRCLING, CHARGING, STRIKING, WITHDRAWING }

    private var phase = Phase.CIRCLING

    /** Ticks left in whatever it is doing — a patience in [Phase.CIRCLING], a giving-up everywhere else. */
    private var patience = 0

    private var bitesLeft = 0

    private var betweenBites = 0

    /** Where on the orbit it is, in radians. Advancing this is what makes it go round. */
    private var bearing = 0.0

    /** Which way round. Drawn once a hunt so two fish on one player do not shadow each other exactly. */
    private var handedness = 1.0

    init {
        flags = EnumSet.of(Flag.MOVE, Flag.LOOK)
    }

    override fun canUse(): Boolean {
        val quarry = fish.target
        return quarry != null && quarry.isAlive && fish.isInWater && !hasRisenTooFar()
    }

    override fun canContinueToUse(): Boolean = canUse()

    /**
     * Whether the hunt has carried the fish too far up out of the abyss to go on.
     *
     * **Rising is the counterplay** (Jonah, 2026-09-10). The encounter is made of the pressure and the
     * dark, so a fish that followed you into the shallows would be a mini-boss fought from a boat with
     * none of it — and a player who cannot win should always have a direction to swim in. It gives up
     * [GIVES_UP_ABOVE_THE_LINE] blocks over the line rather than exactly at it, so the edge is a retreat
     * rather than a wall you bounce off.
     *
     * Measured on the fish and not on the quarry: what matters is where the *animal* has been drawn to,
     * which is also what stops it hanging at the surface waiting.
     */
    private fun hasRisenTooFar(): Boolean = isAboveTheHunt(fish.level(), fish.blockY)

    /** The orbit moves every tick, so this may not be run on the goal selector's slower beat. */
    override fun requiresUpdateEveryTick(): Boolean = true

    override fun start() {
        fish.navigation.stop()
        circle()
        bearing = fish.random.nextDouble() * FULL_TURN
        handedness = if (fish.random.nextBoolean()) 1.0 else -1.0
    }

    override fun stop() {
        fish.navigation.stop()
        phase = Phase.CIRCLING
        // Given up rather than interrupted, so it lets go: holding the quarry left it hanging where it gave up,
        // since `HadalfishLoiter` only runs for a fish hunting nobody (Jonah, walked A7 2026-09-14).
        if (hasRisenTooFar()) fish.target = null
    }

    override fun tick() {
        val quarry = fish.target ?: return
        fish.lookControl.setLookAt(quarry, LOOK_YAW, LOOK_PITCH)
        when (phase) {
            Phase.CIRCLING -> orbit(quarry)
            Phase.CHARGING -> charge(quarry)
            Phase.STRIKING -> strike(quarry)
            Phase.WITHDRAWING -> withdraw(quarry)
        }
    }

    /**
     * Hold station on a moving circle, and run out of patience.
     *
     * The orbit is computed against the quarry's *current* position every tick, so a player who swims does
     * not shake it — they drag the circle along with them, which is what makes it read as being stalked.
     */
    private fun orbit(quarry: LivingEntity) {
        bearing += TURN_RATE * handedness
        val station = quarry.position().add(
            cos(bearing) * ORBIT_RADIUS,
            ABOVE_THE_QUARRY,
            sin(bearing) * ORBIT_RADIUS,
        )
        steer(station, ORBIT_SPEED, ORBIT_EASE)
        facePlainly(quarry)
        if (--patience <= 0) {
            phase = Phase.CHARGING
            patience = GIVES_UP_CHARGING_AFTER
        }
    }

    /**
     * Straight at them, fast.
     *
     * **Set rather than eased**, unlike every other phase: the charge is the one moment the thing should
     * stop looking like it is swimming and start looking like it has been fired.
     */
    private fun charge(quarry: LivingEntity) {
        fish.deltaMovement = quarry.eyePosition.subtract(fish.eyePosition).normalize().scale(CHARGE_SPEED)
        if (withinReach(quarry)) {
            phase = Phase.STRIKING
            bitesLeft = BITES_A_RUN
            betweenBites = 0
            patience = GIVES_UP_STRIKING_AFTER
        } else if (--patience <= 0) {
            // Missed, or it lost the line — break off rather than tail them at charge speed forever.
            beginWithdrawing()
        }
    }

    /**
     * Three bites, quickly, while pressing in.
     *
     * It keeps closing between them at a fraction of the charge, so a player backing off is followed for
     * the length of the run and no longer.
     */
    private fun strike(quarry: LivingEntity) {
        // Closing only while out of reach, and pulling up once in it: steered at the quarry's own position
        // for the whole run, with the charge's speed still on it, a two-block fish ended up around them.
        if (withinReach(quarry)) fish.deltaMovement = fish.deltaMovement.scale(KEEPS_IN_REACH)
        else steer(quarry.position(), CHARGE_SPEED * PRESSING_IN, STRIKE_EASE)
        if (betweenBites > 0) betweenBites--
        if (betweenBites <= 0 && withinReach(quarry)) {
            val level = fish.level()
            if (level is ServerLevel) fish.doHurtTarget(level, quarry)
            betweenBites = BETWEEN_BITES
            if (--bitesLeft <= 0) beginWithdrawing()
        } else if (--patience <= 0) {
            beginWithdrawing()
        }
    }

    /** Back out to where it can circle again — by distance, with a timeout so it cannot get stuck out. */
    private fun withdraw(quarry: LivingEntity) {
        val away = fish.position().subtract(quarry.position())
        // Straight up is as good as any direction when it is directly on top of them, and picking one
        // stops a normalize() on a zero vector answering with NaN and freezing the fish.
        val heading = if (away.lengthSqr() < TOO_CLOSE_TO_AIM) Vec3(0.0, 1.0, 0.0) else away.normalize()
        fish.deltaMovement = fish.deltaMovement.lerp(heading.scale(WITHDRAW_SPEED), WITHDRAW_EASE)
        if (away.length() >= ORBIT_RADIUS || --patience <= 0) circle()
    }

    private fun beginWithdrawing() {
        phase = Phase.WITHDRAWING
        patience = GIVES_UP_WITHDRAWING_AFTER
    }

    private fun circle() {
        phase = Phase.CIRCLING
        patience = CIRCLES_FOR_AT_LEAST + fish.random.nextInt(CIRCLES_FOR_UP_TO_ANOTHER)
    }

    private fun withinReach(quarry: LivingEntity): Boolean = fish.isWithinMeleeAttackRange(quarry)

    /**
     * Turn the **body** to the quarry, not only the head.
     *
     * The lure hangs in front of the face and at this range is the only part of the animal anybody can
     * see, so it has to stay pointed at the player — and the look control alone cannot manage it. Vanilla
     * clamps head yaw to a bound either side of the body, and a body tangential to its own circle is
     * broadside on, which swung the lure in and out of view for reasons a player could not read (Jonah,
     * walked 2026-09-10).
     *
     * Set rather than eased: the circle already turns slowly, so the facing follows it without snapping.
     */
    private fun facePlainly(quarry: LivingEntity) {
        val toward = quarry.position().subtract(fish.position())
        if (toward.horizontalDistanceSqr() < TOO_CLOSE_TO_AIM) return
        val bearingTo = (Math.toDegrees(kotlin.math.atan2(toward.z, toward.x)) - QUARTER_TURN).toFloat()
        fish.yBodyRot = bearingTo
        fish.yRot = bearingTo
    }

    /** Ease toward the velocity that would carry it to [station], rather than snapping onto it. */
    private fun steer(station: Vec3, speed: Double, ease: Double) {
        val toward = station.subtract(fish.position())
        if (toward.lengthSqr() < TOO_CLOSE_TO_AIM) return
        fish.deltaMovement = fish.deltaMovement.lerp(toward.normalize().scale(speed), ease)
    }

    companion object {
        /**
         * What a steer actually delivers, as a share of the velocity it is aimed at.
         *
         * `Guardian.travelInWater` moves by the delta and *then* damps it by nine tenths, and [steer] eases
         * [ORBIT_EASE] of the way toward its target each tick, so the steady state settles below the
         * target. Public because the three speeds are only comparable through it — see `HadalfishCheck`.
         */
        const val STEERING_KEEPS = 0.714

        /** How fast the orbit has to travel to stay on a circle that is turning under it. */
        val holdingTheOrbitCosts: Double get() = TURN_RATE * ORBIT_RADIUS

        /** And what the steering can actually supply against that. */
        val orbitCanSupply: Double get() = ORBIT_SPEED * STEERING_KEEPS

        /** Blocks a second, for the three that have to read as three different things. */
        val circlesAt: Double get() = orbitCanSupply * A_SECOND
        val chargesAt: Double get() = CHARGE_SPEED * A_SECOND

        private const val A_SECOND = 20.0

        const val FULL_TURN = Math.PI * 2

        /** Whether [y] is higher than a hunt goes, [GIVES_UP_ABOVE_THE_LINE] over the abyss line. */
        fun isAboveTheHunt(level: LevelReader, y: Int): Boolean {
            val line = DeepWater.lineIn(level) ?: return false
            return y > line + GIVES_UP_ABOVE_THE_LINE
        }

        /**
         * **Beyond the fog, so the body is never seen circling** — the lantern fading out to uncover the
         * fish is the best thing the animal does, and it only happens if the reveal belongs to the charge.
         * Past `DeepWaterFog`'s reach, where eleven blocks put the whole approach in plain view.
         */
        const val ORBIT_RADIUS = 28.0

        /**
         * Radians a tick, and **it had to halve when the orbit widened**.
         *
         * Holding station on a circle costs a tangential speed of this times [ORBIT_RADIUS]. At the old
         * rate over the new radius that is 0.98 blocks a tick — nearly [CHARGE_SPEED], which the steering
         * cannot deliver and should not, since a circle taken at a run is not a circle. Halved, the station
         * asks 0.50 and [ORBIT_SPEED] supplies it. A lap is seventeen seconds now, and a circling phase was
         * never a whole lap anyway.
         */
        const val TURN_RATE = 0.018

        /** It hangs a little over you, which is where a thing that is about to drop on you should be. */
        const val ABOVE_THE_QUARRY = 2.5

        /**
         * What the orbit steers at — **a target velocity, of which about five parts in seven survive**.
         *
         * `Guardian.travelInWater` moves by the delta and *then* damps it by nine tenths, and [steer] eases
         * a fifth of the way toward this each tick, so the steady state moves at [STEERING_KEEPS] of it:
         * 0.54 blocks a tick, eleven a second. That is deliberately just under a wander and nothing like
         * [CHARGE_SPEED] — the three speeds have to read as three things (Jonah, 2026-09-10).
         *
         * **A little over what the station costs, not exactly it.** Sized to the circle at 0.70 it came out
         * a fraction short, and a fish that is permanently one percent behind its own station never catches
         * up — it falls away from the circle instead of riding it. `HadalfishCheck` is what says so.
         */
        const val ORBIT_SPEED = 0.75
        const val ORBIT_EASE = 0.2

        /**
         * Blocks a tick, and **exactly that**: `Guardian.travelInWater` calls `move` before it damps, so a
         * delta set outright is the distance covered. Thirty-five a second, up from twenty-one, which is
         * what the longer run through the dark costs — a charge from beyond the fog at the old speed would
         * spend the reveal as a slow drift into view rather than performing it.
         */
        const val CHARGE_SPEED = 1.75

        /** How much of the charge it keeps while biting, so it stays on a retreating player. */
        const val PRESSING_IN = 0.35

        /** The share of its speed it keeps each tick while in reach, so the charge ends at the bite. */
        const val KEEPS_IN_REACH = 0.25
        const val STRIKE_EASE = 0.35

        const val WITHDRAW_SPEED = 0.75
        const val WITHDRAW_EASE = 0.25

        const val BITES_A_RUN = 3

        /**
         * Ticks between bites, and **past vanilla's invulnerability window rather than inside it**.
         *
         * Five read as one flurry and landed as two: a hit inside twenty ticks of the last one is swallowed
         * whole, so a third of every run went nowhere and the animal was quietly weaker than its numbers
         * (Jonah, walked 2026-09-10). Three bites that read as two is the outcome to avoid — either they
         * all land or there is one deliberate bite, and this is the first of those.
         */
        const val BETWEEN_BITES = 25

        const val CIRCLES_FOR_AT_LEAST = 70
        const val CIRCLES_FOR_UP_TO_ANOTHER = 70

        const val GIVES_UP_CHARGING_AFTER = 60

        /** Long enough to hold [BITES_A_RUN] bites [BETWEEN_BITES] apart, or the run ends mid-flurry. */
        const val GIVES_UP_STRIKING_AFTER = 90
        const val GIVES_UP_WITHDRAWING_AFTER = 60

        const val TOO_CLOSE_TO_AIM = 1.0E-4

        /** Minecraft's yaw has zero facing south, which is a quarter turn off `atan2`'s zero. */
        const val QUARTER_TURN = 90.0

        /** How far over the abyss's own line it will still follow you — see [hasRisenTooFar]. */
        const val GIVES_UP_ABOVE_THE_LINE = 16

        const val LOOK_YAW = 30.0f
        const val LOOK_PITCH = 30.0f
    }
}
