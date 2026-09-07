package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.Identifier
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3

/**
 * One meteor storm: a place, a clock, and the bodies it drops (design §5.2).
 *
 * **The storm is the entity and [Meteors] is only the weather that raises them**, which is the shape
 * `Sandfall` set and the reason there is no packet and no store anywhere in this feature. A storm has the
 * two things no other phenomenon here has — somewhere it is happening and a length of time it lasts — and
 * an entity is both of those already: it has a position, it is ticked, it is tracked to nearby clients by
 * vanilla, and it goes away by being removed. Nothing else has to know it exists.
 *
 * **It runs in two phases and the first one drops nothing.** For [approaching] ticks it is only a thing in
 * the sky getting closer, which is what the client draws its telegraph from; then it falls for as long as
 * it was given. A crater that simply appears is a punishment and one that arcs in is a challenge, so the
 * quiet phase is the mechanic rather than decoration on it.
 *
 * **Concentrated, not scattered.** A tempest strikes rarely over a wide area; this pounds a small one for
 * ten or fifteen seconds and then stops. That difference is [REACH] against the tempest's chunk-wide roll,
 * and it is the whole brief — a meteor storm you can watch coming, walk out of, and come back to loot.
 */
class MeteorStorm(type: EntityType<out MeteorStorm>, level: Level) : Entity(type, level) {

    /**
     * How many bodies the sky splits into, watched by the client because the telegraph has to agree with
     * what then arrives — a sky that promised four and delivered nine breaks at the seam.
     */
    var bodies: Int
        get() = entityData.get(BODIES)
        set(value) = entityData.set(BODIES, value)

    /**
     * How long it goes on dropping once it starts, in ticks.
     *
     * Watched, like [bodies], because the client needs to know when the last body has landed — the violet
     * it casts fades out after that, and a cast that outlives its storm is the same lie a hazard marker
     * that outlives its hazard would be.
     */
    var falling: Int
        get() = entityData.get(FALLING)
        set(value) = entityData.set(FALLING, value)

    /** How fast the bodies come in, and so how hard they land. */
    var fury: Double = ORDINARY_FURY

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(BODIES, FEW)
        builder.define(FALLING, SHORTEST_FALL)
    }

    /**
     * How strongly the violet is cast over this place — nought before, one through, and fading at each end.
     *
     * **Up for the whole of the storm rather than the last of the warning** (Jonah, walked). It was ten
     * seconds of ground light at the end, which over a disc this wide read as a spotlight on somewhere
     * else; what it is now is the place turning violet while the storm is over it, which is a warning you
     * cannot miss and cannot mistake for anything.
     *
     * A pure function of the entity's own clock, so neither side has to be told: [tickCount] ticks on both.
     */
    fun castStrength(): Float {
        if (tickCount < WELLING_UP) return tickCount.toFloat() / WELLING_UP
        val ends = APPROACHING + falling + LINGERING
        if (tickCount > ends - LINGERING) return ((ends - tickCount).toFloat() / LINGERING).coerceAtLeast(NONE_OF_IT)
        return ALL_OF_IT
    }

    /** How far through its approach this is, nought to one — what a sky animation is drawn from. */
    fun approachedBy(partial: Float): Float =
        ((tickCount + partial) / APPROACHING).coerceIn(NONE_OF_IT, ALL_OF_IT)

    override fun tick() {
        super.tick()
        val level = level()
        if (level !is ServerLevel) return
        // Outlives its last body by [LINGERING], which is the violet fading out rather than being switched
        // off — and it is why the storm is what holds the clock: nothing else knows the shower has ended.
        if (tickCount > APPROACHING + falling + LINGERING) {
            discard()
            return
        }
        if (tickCount <= APPROACHING) return
        dropSome(level)
    }

    /**
     * What body [number] of this storm does — the one answer a client works out for itself as well.
     *
     * **Kept once worked out**, which is a memo of a pure function rather than state: a storm's flights
     * cannot change while its two numbers hold, and both sides were building every one of them afresh —
     * the sky once a body a frame, the server twice a body a tick. A long storm is a couple of hundred
     * bodies, so that was the lights lagging (Jonah, walked).
     */
    fun flightOf(number: Int): MeteorFlight {
        if (flights.size != bodies || flightsSpanned != falling) {
            flightsSpanned = falling
            flights = List(bodies) { MeteorFlight.of(uuid.leastSignificantBits, it, bodies, falling, APPROACHING) }
        }
        return flights[number]
    }

    private var flights: List<MeteorFlight> = emptyList()
    private var flightsSpanned = NOT_YET

    /**
     * Where a body's light hangs, [nearness] of the way from first sighting to its own fall.
     *
     * **A real place on the body's own entry line**, closing from [TELEGRAPHED_FROM] to the range it is
     * actually thrown from — not a direction picked for the sky. That is what hangs the telegraph over the
     * storm rather than wherever the viewer happens to be facing, what makes the lights swing as the
     * bodies bear down, and what lets them start as one point and come apart on their own.
     *
     * **It closes on a curve rather than evenly**, so that the light is moving at about the speed of the
     * rock at the instant one becomes the other. A straight ramp from twelve thousand blocks to a hundred
     * and fifty over thirty seconds arrives doing twenty blocks a tick, which is twice what the body then
     * does — so a light that had been bearing down on you handed over to something visibly dawdling
     * (Jonah, walked). Slowing the last of the approach also holds the lights further out for longer,
     * which tightens the sighting.
     *
     * Asked by the sky that draws the light and by the command that turns you to face it, so those two
     * cannot disagree about where it is.
     */
    fun seenFrom(flight: MeteorFlight, nearness: Float): Vec3 {
        val stillToCome = (ALL_OF_IT - nearness).toDouble()
        val range = ENTRY_RANGE + CLOSES_AT * stillToCome + (TELEGRAPHED_FROM - ENTRY_RANGE - CLOSES_AT) *
            stillToCome * stillToCome
        val (offsetX, offsetY, offsetZ) = flight.entryOffset(range)
        return Vec3(x + flight.landsAwayX + offsetX, y + offsetY, z + flight.landsAwayZ + offsetZ)
    }

    /**
     * The bodies whose moment is this tick.
     *
     * **Read off the same list the sky is drawing**, rather than rolled here: a body announced in the sky
     * and a body dropped on the ground have to be the same body, and the only way to promise that without
     * sending anything is for both sides to ask the same pure function.
     */
    private fun dropSome(level: ServerLevel) {
        for (number in 0..<bodies) {
            val flight = flightOf(number)
            if (flight.fallsAt != tickCount) continue
            throwOne(level, flight)
        }
    }

    /**
     * One body, entering obliquely at the angle its flight drew.
     *
     * **Aimed at the ground rather than dropped from overhead** (Jonah, 2026-09-06): a body that comes
     * straight down is on screen for a moment and reads as a falling block, where one entering between ten
     * and forty-five degrees crosses the sky and reads as something arriving from somewhere else. It is
     * placed back along its own entry line and pointed at where it is going, so however shallow the angle
     * it still lands where it was announced.
     */
    private fun throwOne(level: ServerLevel, flight: MeteorFlight) {
        val landsAt = BlockPos.containing(x + flight.landsAwayX, y, z + flight.landsAwayZ)
        val ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, landsAt)
        // **Thrown from exactly where its own light was**, so the handover is a light becoming a rock
        // rather than one thing going out and another appearing seventy blocks below it.
        val from = seenFrom(flight, ARRIVING)
        val toTheGround = Vec3(ground.x + HALF, ground.y.toDouble(), ground.z + HALF).subtract(from)
        val speed = SLOWEST_ARRIVAL + (FASTEST_ARRIVAL - SLOWEST_ARRIVAL) * fury
        val body = Meteor(AgeContent.METEOR, level)
        body.blast = (AT_REST + (AT_FULL_FURY - AT_REST) * fury).toFloat()
        body.setPos(from.x, from.y, from.z)
        body.setDeltaMovement(toTheGround.normalize().scale(speed))
        level.addFreshEntity(body)
    }

    /** Nothing about a storm is worth colliding with; it is a clock standing in the sky. */
    override fun isPickable(): Boolean = false

    /** And nothing can put one out. It ends when its own clock runs down and by no other means. */
    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean = false

    override fun addAdditionalSaveData(output: ValueOutput) {
        output.putInt(FALLING_KEY, falling)
        output.putDouble(FURY_KEY, fury)
        output.putInt(BODIES_KEY, bodies)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        falling = input.getIntOr(FALLING_KEY, SHORTEST_FALL)
        fury = input.getDoubleOr(FURY_KEY, ORDINARY_FURY)
        bodies = input.getIntOr(BODIES_KEY, FEW)
    }

    companion object {
        private val BODIES: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(MeteorStorm::class.java, EntityDataSerializers.INT)
        private val FALLING: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(MeteorStorm::class.java, EntityDataSerializers.INT)

        /**
         * How long it hangs in the sky before anything falls, in ticks — **thirty seconds** (Jonah).
         *
         * Long enough to see it, read where it is going and *do something about it*: the counterplay is
         * spatial, so the warning has to leave time to walk somewhere else, and half a minute is what a
         * walk asked for over the four and a half seconds this first had.
         */
        const val APPROACHING = 600

        /**
         * How long the violet takes to come up at the start, and to go out after the last body, in ticks.
         *
         * **A cheap trick, and deliberately in the player's favour** (Jonah): the sky says a storm is
         * coming and roughly where, and the cast says *exactly* where you are standing. Light rather than
         * a marker because it costs nothing to understand — a place that turns violet is a thing anybody
         * reads without being taught it.
         */
        const val WELLING_UP = 60
        const val LINGERING = 60

        /** Vanilla sprinting, in blocks a tick — 4.317 a second walking, a third again running. */
        private const val A_SPRINT = 0.2806

        /** And what a player is allowed for seeing the warning and deciding, in ticks. */
        private const val NOTICING = 60

        /**
         * How wide the pounding is — **exactly as far as you can run in the warning you are given**
         * (Jonah), which is why it is worked out here rather than chosen.
         *
         * Take the telegraph, take off the seconds the warning itself spends coming up, take off a few
         * more for noticing it and turning round, and multiply what is left by a sprint. A storm that
         * gathers dead over you is then survivable by running and by nothing else — which is the whole of
         * this phenomenon's counterplay, and the number that makes the promise true rather than nearly.
         *
         * Widening it is therefore not free: every block wants a tenth of a second more warning.
         */
        const val REACH = A_SPRINT * (APPROACHING - WELLING_UP - NOTICING)

        /**
         * How long the pounding lasts, in ticks — **ten seconds at the low end, a full minute at the
         * high** (Jonah).
         *
         * Ten to fifteen is the baseline a storm draws between before its Age's rung stretches it; the
         * minute is what a teeming Age reaches, and it is meant to be a different order of event rather
         * than a longer nuisance.
         */
        const val SHORTEST_FALL = 200
        const val ORDINARY_FALL = 300
        const val LONGEST_FALL = 1200

        private const val FEW = 4

        /** No length a storm can have, so the flights are worked out the first time they are asked for. */
        private const val NOT_YET = -1

        /**
         * **A bit under a creeper at rest, and past twice TNT at the very top** (Jonah, 2026-09-06).
         *
         * Walked at half fury under the first numbers and judged far too much — "way too much, but it was
         * super cool" — so what was the middle of the old scale is now its ceiling, and reachable only by
         * an Age broken enough to buy the whole of the manifestation. An ordinary storm is a nuisance you
         * shelter from; a ruined Age's is the hardest thing in the modpack.
         */
        private const val AT_REST = 2.5
        private const val AT_FULL_FURY = 11.0

        /** How far back along its own entry line a body starts. Far enough to cross real sky. */
        const val ENTRY_RANGE = 150.0

        /**
         * How far out a body's light hangs when it first appears, in blocks.
         *
         * **Far beyond anything that renders, which is the illusion the whole telegraph rests on.** A
         * shower has to look like it is arriving from somewhere else, and at this range the whole disc the
         * bodies are aimed at is about half a degree of sky — a moon's width for the lot of them — so they
         * start as one light and come apart as they close, with no animation saying so.
         */
        const val TELEGRAPHED_FROM = 12000.0

        /** Doubled off a walk: a body should streak rather than sail. */
        private const val SLOWEST_ARRIVAL = 7.2
        private const val FASTEST_ARRIVAL = 12.0

        /**
         * How far a light still has to close in its last tick of approach, so it hands over to a rock
         * moving at about the same speed. The middle of the arrival speeds, times the whole telegraph.
         */
        private const val CLOSES_AT = (SLOWEST_ARRIVAL + FASTEST_ARRIVAL) / 2.0 * APPROACHING

        private const val FALLING_KEY = "falling"
        private const val FURY_KEY = "fury"
        private const val BODIES_KEY = "bodies"

        private const val ORDINARY_FURY = 0.0
        private const val HALF = 0.5
        private const val NONE_OF_IT = 0.0f
        private const val ALL_OF_IT = 1.0f

        /** A body's light at the instant the rock takes over from it. */
        private const val ARRIVING = 1.0f

        val ID: Identifier = "meteor_storm".location()

        /** Stand one up at [where], to approach and then fall. */
        fun gatherAt(level: ServerLevel, where: Vec3, bodies: Int, falling: Int, fury: Double): MeteorStorm {
            val storm = MeteorStorm(AgeContent.METEOR_STORM, level)
            storm.setPos(where.x, where.y, where.z)
            storm.bodies = bodies
            storm.falling = falling
            storm.fury = fury
            level.addFreshEntity(storm)
            return storm
        }
    }
}
