package co.voik.agesandtheart.content

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.goal.Goal
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
        return quarry != null && quarry.isAlive && fish.isInWater
    }

    override fun canContinueToUse(): Boolean = canUse()

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
        steer(quarry.position(), CHARGE_SPEED * PRESSING_IN, STRIKE_EASE)
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

    /** Ease toward the velocity that would carry it to [station], rather than snapping onto it. */
    private fun steer(station: Vec3, speed: Double, ease: Double) {
        val toward = station.subtract(fish.position())
        if (toward.lengthSqr() < TOO_CLOSE_TO_AIM) return
        fish.deltaMovement = fish.deltaMovement.lerp(toward.normalize().scale(speed), ease)
    }

    private companion object {
        const val FULL_TURN = Math.PI * 2

        /** Radians a tick — a lap in about nine seconds, slow enough to read as deliberate. */
        const val TURN_RATE = 0.035

        const val ORBIT_RADIUS = 11.0

        /** It hangs a little over you, which is where a thing that is about to drop on you should be. */
        const val ABOVE_THE_QUARRY = 2.5

        const val ORBIT_SPEED = 0.45
        const val ORBIT_EASE = 0.2

        /** Blocks a tick. Roughly twenty a second — it crosses the orbit in well under one. */
        const val CHARGE_SPEED = 1.05

        /** How much of the charge it keeps while biting, so it stays on a retreating player. */
        const val PRESSING_IN = 0.35
        const val STRIKE_EASE = 0.35

        const val WITHDRAW_SPEED = 0.75
        const val WITHDRAW_EASE = 0.25

        const val BITES_A_RUN = 3

        /** Ticks between bites: fast enough to read as one flurry rather than three attacks. */
        const val BETWEEN_BITES = 5

        const val CIRCLES_FOR_AT_LEAST = 70
        const val CIRCLES_FOR_UP_TO_ANOTHER = 70

        const val GIVES_UP_CHARGING_AFTER = 60
        const val GIVES_UP_STRIKING_AFTER = 50
        const val GIVES_UP_WITHDRAWING_AFTER = 60

        const val TOO_CLOSE_TO_AIM = 1.0E-4

        const val LOOK_YAW = 30.0f
        const val LOOK_PITCH = 30.0f
    }
}
