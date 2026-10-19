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

    private enum class Phase { CIRCLING, CHARGING, STRIKING, LINGERING, WITHDRAWING }

    private var phase = Phase.CIRCLING

    /** Ticks left in whatever it is doing — a patience in [Phase.CIRCLING], a giving-up everywhere else. */
    private var patience = 0

    private var bitesLeft = 0

    private var betweenBites = 0

    /** Where on the orbit it is, in radians. Advancing this is what makes it go round. */
    private var bearing = 0.0

    /** Which way round. Drawn once a hunt so two fish on one player do not shadow each other exactly. */
    private var handedness = 1.0

    /** The side it came at the quarry from, level and of unit length — where it keeps to while it bites. */
    private var cameFrom = Vec3(1.0, 0.0, 0.0)

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
        fish.isHunting = true
        circle()
        bearing = fish.random.nextDouble() * FULL_TURN
        handedness = if (fish.random.nextBoolean()) 1.0 else -1.0
    }

    override fun stop() {
        fish.navigation.stop()
        phase = Phase.CIRCLING
        fish.isHunting = false
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
            Phase.LINGERING -> linger()
            Phase.WITHDRAWING -> withdraw(quarry)
        }
    }

    /**
     * Hold station on a moving circle, and run out of patience.
     *
     * The orbit is computed against the quarry's *current* position every tick, so a player who swims does
     * not shake it — they drag the circle along with them, which is what makes it read as being stalked.
     *
     * **But a player swimming at it gains on it** (Jonah, 2026-10-19): it gives ground no faster than
     * [GIVES_GROUND_AT], so it can be reached before it decides to come.
     */
    private fun orbit(quarry: LivingEntity) {
        bearing += TURN_RATE * handedness
        val station = Vec3(
            quarry.x + cos(bearing) * ORBIT_RADIUS,
            levelWith(quarry),
            quarry.z + sin(bearing) * ORBIT_RADIUS,
        )
        steer(station, ORBIT_SPEED, ORBIT_EASE)
        givingGroundNoFasterThan(quarry, GIVES_GROUND_AT)
        facePlainly(quarry)
        if (--patience <= 0) {
            phase = Phase.CHARGING
            patience = GIVES_UP_CHARGING_AFTER
        }
    }

    /**
     * The height it circles and backs off at: level with the quarry (Jonah, 2026-10-19), and while the quarry is in
     * the deep, no higher than keeps the whole fish in it — above the abyss's surface it cannot be seen from below.
     */
    private fun levelWith(quarry: LivingEntity): Double {
        val level = fish.level()
        val line = DeepWater.lineIn(level) ?: return quarry.y
        val quarryIsInTheDeep = level.getFluidState(quarry.blockPosition()).`is`(DeepWater.DEEP_WATER)
        val highestStillInTheDeep = line + 1.0 - fish.bbHeight
        return if (quarryIsInTheDeep) minOf(quarry.y, highestStillInTheDeep) else quarry.y
    }

    /** Takes off whatever of its velocity carries it away from [quarry] faster than [most] blocks a tick. */
    private fun givingGroundNoFasterThan(quarry: LivingEntity, most: Double) {
        val away = fish.position().subtract(quarry.position())
        if (away.lengthSqr() < TOO_CLOSE_TO_AIM) return
        val outward = away.normalize()
        val retreating = fish.deltaMovement.dot(outward)
        if (retreating > most) fish.deltaMovement = fish.deltaMovement.subtract(outward.scale(retreating - most))
    }

    /**
     * Straight at them, fast, and **stopping where its bite reaches** (Jonah, 2026-10-19): at full speed it carried on
     * past the quarry and bit from behind, round a shield. It comes no nearer than [standingOff].
     *
     * **Set rather than eased**, unlike every other phase: the charge is the one moment the thing should
     * stop looking like it is swimming and start looking like it has been fired.
     */
    private fun charge(quarry: LivingEntity) {
        val toward = quarry.eyePosition.subtract(fish.eyePosition)
        val leftToGo = (toward.length() - standingOff(quarry)).coerceAtLeast(0.0)
        val speed = minOf(CHARGE_SPEED * fish.paceShare, leftToGo)
        fish.deltaMovement = if (toward.lengthSqr() < TOO_CLOSE_TO_AIM) Vec3.ZERO else toward.normalize().scale(speed)
        if (withinReach(quarry)) {
            phase = Phase.STRIKING
            bitesLeft = BITES_A_RUN
            betweenBites = 0
            patience = GIVES_UP_STRIKING_AFTER
            cameFrom = levelAwayFrom(quarry) ?: cameFrom
        } else if (--patience <= 0) {
            // Missed, or it lost the line — break off rather than tail them at charge speed forever.
            beginWithdrawing()
        }
    }

    /**
     * Three bites, quickly, from the side it came from.
     *
     * It holds a station [standingOff] from the quarry on that side, eye to eye, and follows it there at a fraction
     * of the charge, so a player backing off is followed for the length of the run and no longer, and a bite comes
     * from where the charge was seen coming — never from behind a shield it went round.
     */
    private fun strike(quarry: LivingEntity) {
        val station = quarry.position()
            .add(cameFrom.scale(standingOff(quarry)))
            .add(0.0, quarry.eyeHeight - fish.eyeHeight.toDouble(), 0.0)
        if (station.distanceToSqr(fish.position()) < ON_STATION) fish.deltaMovement = fish.deltaMovement.scale(KEEPS_IN_REACH)
        else steer(station, CHARGE_SPEED * PRESSING_IN, STRIKE_EASE)
        if (betweenBites > 0) betweenBites--
        if (betweenBites <= 0 && withinReach(quarry)) {
            val level = fish.level()
            if (level is ServerLevel) fish.doHurtTarget(level, quarry)
            betweenBites = BETWEEN_BITES
            if (--bitesLeft <= 0) beginLingering()
        } else if (--patience <= 0) {
            beginWithdrawing()
        }
    }

    /**
     * Hangs where the last bite left it for [LINGERS_FOR], so a player who took the run can answer it
     * (Jonah, 2026-10-19).
     */
    private fun linger() {
        fish.deltaMovement = fish.deltaMovement.scale(KEEPS_IN_REACH)
        if (--patience <= 0) beginWithdrawing()
    }

    private fun beginLingering() {
        phase = Phase.LINGERING
        patience = LINGERS_FOR
    }

    /**
     * Back out to where it can circle again, **outward and level with the quarry** rather than straight away from it,
     * which so often meant up and out of the deep, where it was gone. By distance, with a timeout so it cannot get
     * stuck out.
     */
    private fun withdraw(quarry: LivingEntity) {
        val outward = levelAwayFrom(quarry) ?: cameFrom
        val rising = ((levelWith(quarry) - fish.y) * LEVELS_OUT_BY).coerceIn(-LEVELS_AT_MOST, LEVELS_AT_MOST)
        val heading = outward.scale(WITHDRAW_SPEED * fish.paceShare).add(0.0, rising, 0.0)
        fish.deltaMovement = fish.deltaMovement.lerp(heading, WITHDRAW_EASE)
        val outFar = fish.position().subtract(quarry.position()).horizontalDistance() >= ORBIT_RADIUS
        if (outFar || --patience <= 0) circle()
    }

    /** The level direction from [quarry] to the fish, or null when it is right over or under them. */
    private fun levelAwayFrom(quarry: LivingEntity): Vec3? {
        val away = fish.position().subtract(quarry.position())
        val level = Vec3(away.x, 0.0, away.z)
        return if (level.lengthSqr() < TOO_CLOSE_TO_AIM) null else level.normalize()
    }

    /** How far off the quarry it bites from, middle to middle: the two bodies' half-widths and half its reach. */
    private fun standingOff(quarry: LivingEntity): Double =
        (fish.bbWidth + quarry.bbWidth) / 2.0 + Hadalfish.BITES_FROM / 2.0

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
     * The light on its eye is at this range the only part of the animal anybody can see, so it has to stay
     * pointed at the player — and the look control alone cannot manage it. Vanilla
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
        fish.deltaMovement = fish.deltaMovement.lerp(toward.normalize().scale(speed * fish.paceShare), ease)
    }

    companion object {
        const val FULL_TURN = Math.PI * 2

        /** Whether [y] is higher than a hunt goes, [GIVES_UP_ABOVE_THE_LINE] over the abyss line. */
        fun isAboveTheHunt(level: LevelReader, y: Int): Boolean {
            val line = DeepWater.lineIn(level) ?: return false
            return y > line + GIVES_UP_ABOVE_THE_LINE
        }

        /**
         * **Beyond the fog, so the body is never seen circling** — the lantern fading out to uncover the
         * fish is the best thing the animal does, and it only happens if the reveal belongs to the charge.
         * Past `DeepWaterFog`'s reach, where eleven blocks put the whole approach in plain view, and a little
         * further out than that needs (Jonah, 2026-10-19), where the lure is further up its fade from near.
         */
        const val ORBIT_RADIUS = 32.0

        /**
         * Radians a tick, and **it falls as the orbit widens**.
         *
         * Holding station on a circle costs a tangential speed of this times [ORBIT_RADIUS], and [ORBIT_SPEED]
         * supplies about 0.54 blocks a tick of it. At [ORBIT_RADIUS] this asks 0.51, so the fish keeps up; a
         * circle taken at a run is not a circle. A lap is a little under twenty seconds, and a circling phase
         * was never a whole lap anyway.
         */
        const val TURN_RATE = 0.016


        /**
         * What the orbit steers at — **a target velocity, of which about five parts in seven survive**.
         *
         * `Guardian.travelInWater` moves by the delta and *then* damps it by nine tenths, and [steer] eases
         * a fifth of the way toward this each tick, so the steady state moves at about 0.714 of it:
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

        /** The share of its speed it keeps each tick while on station, so the charge ends at the bite. */
        const val KEEPS_IN_REACH = 0.25

        /** Within half a block of where it bites from, squared. */
        const val ON_STATION = 0.25

        /** How it closes the height between it and the circle as it backs off: a tenth a tick, and at most this. */
        const val LEVELS_OUT_BY = 0.1
        const val LEVELS_AT_MOST = 0.3
        const val STRIKE_EASE = 0.35

        /**
         * How fast it gives ground while circling, in blocks a tick: three a second, under a swimming
         * player's sprint, so swimming at it closes the distance.
         */
        const val GIVES_GROUND_AT = 0.15

        /** Half a second after its last bite before it turns away. */
        const val LINGERS_FOR = 10

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
