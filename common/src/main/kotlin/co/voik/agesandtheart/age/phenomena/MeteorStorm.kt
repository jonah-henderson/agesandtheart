package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.Identifier
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
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
 * **It runs in two phases and the first one drops nothing.** For [APPROACHING] ticks it is only a thing in
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

    /**
     * How steeply this storm's bodies come in, in radians off the horizontal.
     *
     * Watched, because the sky draws the approach from it and the ground throws along it. What is watched
     * is an angle somebody *asked* for, which is the only way to look at the shallow and the steep ends of
     * the range side by side; a storm that drew its own reads it off its identity, which both sides can do
     * once the uuid has arrived.
     *
     * **The watched default has to be a constant.** A client is sent only the values that differ from
     * their defaults, and `uuid` inside [defineSynchedData] is still the placeholder vanilla fills in
     * afterwards — so a default drawn from it is never sent, and each side answers with an angle of its
     * own.
     */
    var slant: Float
        get() = entityData.get(SLANT).takeIf { it != UNASKED } ?: drawnSlant()
        set(value) = entityData.set(SLANT, value)

    /** The angle this storm comes in at when nobody named one — its own, off its own identity. */
    private fun drawnSlant(): Float = MeteorFlight.angleOf(uuid.leastSignificantBits).toFloat()

    /**
     * How wide this storm's bodies are spread, in blocks — [REACH] unless a lure drew it in.
     *
     * Watched, because the sky draws its lights from where the bodies are aimed and the ground throws
     * them there; a client working from the default would put a concentrated storm's lights all over a
     * sky it is falling in one spot of.
     */
    var reach: Float
        get() = entityData.get(REACHES)
        set(value) = entityData.set(REACHES, value)

    /**
     * When the world was, when this gathered — and so, with the world's clock, how old it is.
     *
     * **The storm's own [tickCount] cannot be that clock, and a stress test is where it shows** (Jonah,
     * walked). A client ticks its entities at a fixed twenty a second whatever the server manages; a
     * server under load simply runs slower and never catches up. So the two counts drift apart without
     * bound — on a laggy server the client's lights went out seconds before the ground threw the bodies
     * they had been promising, which reads as lights that never arrive.
     *
     * `gameTime` is the fix because it is the *server's* count and it is put right on every client every
     * twenty ticks. An Age's level data is derived from the overworld's, so it is the same number here as
     * there, and the drift it can accumulate between corrections is a second at worst.
     */
    private var gatheredAt: Long
        get() = entityData.get(GATHERED_AT)
        set(value) = entityData.set(GATHERED_AT, value)

    /** How far through its life this is, in ticks. Both sides work it out; neither counts it. */
    val age: Int
        get() {
            val gathered = gatheredAt
            if (gathered == NEVER) return JUST_GATHERED
            return (level().gameTime - gathered).toInt().coerceAtLeast(JUST_GATHERED)
        }

    /**
     * The same clock [partOfATickOn] into the tick being drawn — what a frame wants and a tick does not.
     *
     * A light's place is worked out from this, and at twenty steps a second it closes the last of its
     * approach in twenty-block strides, which reads as a light being redrawn rather than one moving.
     */
    fun ageWithin(partOfATickOn: Float): Float = age + partOfATickOn

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(BODIES, FEW)
        builder.define(FALLING, SHORTEST_FALL)
        builder.define(SLANT, UNASKED)
        builder.define(GATHERED_AT, NEVER)
        builder.define(REACHES, REACH.toFloat())
    }

    /**
     * How strongly the violet is cast over this place — nought before, one through, and fading at each end.
     *
     * **Up for the whole of the storm rather than the last of the warning** (Jonah, walked). It was ten
     * seconds of ground light at the end, which over a disc this wide read as a spotlight on somewhere
     * else; what it is now is the place turning violet while the storm is over it, which is a warning you
     * cannot miss and cannot mistake for anything.
     *
     * A pure function of [age], which both sides read off the synced [gatheredAt], so neither has to be told.
     */
    fun castStrength(): Float {
        val age = age
        if (age < WELLING_UP) return age.toFloat() / WELLING_UP
        val ends = APPROACHING + falling + LINGERING
        if (age > ends - LINGERING) return ((ends - age).toFloat() / LINGERING).coerceAtLeast(NONE_OF_IT)
        return ALL_OF_IT
    }

    override fun tick() {
        super.tick()
        val level = level()
        if (level !is ServerLevel) return
        // Outlives its last body by [LINGERING], which is the violet fading out rather than being switched
        // off — and it will not go while it still owes anything, however long it has been kept waiting.
        if (age > APPROACHING + falling + LINGERING && droppedTo >= bodies) {
            discard()
            return
        }
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
        if (flights.size != bodies || flightsSpanned != falling || flightsSlanted != slant ||
            flightsReached != reach
        ) {
            flightsReached = reach
            flightsSpanned = falling
            flightsSlanted = slant
            val steepness = slant.toDouble()
            flights = List(bodies) {
                MeteorFlight.of(
                    uuid.leastSignificantBits, it, bodies, falling, APPROACHING, steepness, reach.toDouble(),
                )
            }
        }
        return flights[number]
    }

    private var flights: List<MeteorFlight> = emptyList()
    private var flightsSpanned = NOT_YET
    private var flightsSlanted = Float.NaN
    private var flightsReached = Float.NaN

    /**
     * Where a body's light hangs, [nearness] of the way from first sighting to its own fall.
     *
     * **A real place on the body's own entry line**, closing from [TELEGRAPHED_FROM] to the range it is
     * actually thrown from — not a direction picked for the sky. That is what hangs the telegraph over the
     * storm rather than wherever the viewer happens to be facing, what makes the lights swing as the
     * bodies bear down, and what lets them start as one point and come apart on their own.
     *
     * **It closes evenly, and the rock is what matches** ([CLOSING_IN]). The two were briefly reconciled
     * the other way round, by easing the last of the approach down to the body's speed — which agreed, and
     * agreed on the slower of the two: a light that had been bearing down on you handed over to something
     * dawdling. What a watcher has been promised for thirty seconds is the light's own speed, so that is
     * the one that stands.
     *
     * Asked by the sky that draws the light and by the command that turns you to face it, so those two
     * cannot disagree about where it is.
     */
    fun seenFrom(flight: MeteorFlight, nearness: Float): Vec3 {
        val stillToCome = (ALL_OF_IT - nearness).toDouble()
        val range = ENTRY_RANGE + (TELEGRAPHED_FROM - ENTRY_RANGE) * stillToCome
        val (offsetX, offsetY, offsetZ) = flight.entryOffset(range)
        return landingOf(flight).add(offsetX, offsetY, offsetZ)
    }

    /**
     * **Where a body is actually going, ground and all** — the one point the sky's line and the rock's
     * flight both converge on.
     *
     * They converged on two different points and that is the whole of why the two angles drifted apart
     * (Jonah, 2026-09-09, walked, and the second time this has gone wrong). A storm sits on the ground at
     * *its own* spot; a body lands somewhere else in the disc, on terrain of its own height. The sky drew
     * its approach converging on the storm's height and the throw aimed at the body's, so every block of
     * difference between them tilted the flight against the light it was announced by — a body landing
     * twenty blocks below the storm centre flew at thirty-six degrees where thirty had been drawn.
     *
     * The height is asked of the world rather than carried, on both sides, and where neither can answer
     * they agree on the plane the storm itself stands on — see [groundUnder].
     */
    fun landingOf(flight: MeteorFlight): Vec3 {
        val landsAt = BlockPos.containing(x + flight.landsAwayX, y, z + flight.landsAwayZ)
        return Vec3(landsAt.x + HALF, groundUnder(landsAt), landsAt.z + HALF)
    }

    /**
     * The ground a body is aimed at, or **the storm's own plane where that chunk is not loaded**.
     *
     * A heightmap answers `minY` for a chunk it does not have — silently, and on either side: a client for
     * anything it was never sent, a server for anything past its view distance. A storm's disc reaches
     * nearly three hundred blocks, well past both, so the far side of a shower was aiming at bedrock: the
     * sky drew lights closing on a point underground, and the ground threw bodies from inside the rock.
     *
     * The storm's own height is the answer both sides have, since it is part of the position vanilla
     * already syncs. A body thrown at it flies over real terrain and is landed by its own collision, or by
     * `Meteor.overdue` if the world it was crossing stopped ticking under it.
     */
    private fun groundUnder(landsAt: BlockPos): Double {
        val level = level()
        val loaded = level.hasChunk(
            SectionPos.blockToSectionCoord(landsAt.x),
            SectionPos.blockToSectionCoord(landsAt.z),
        )
        if (!loaded) return y
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, landsAt).y.toDouble()
    }

    /**
     * The bodies whose moment is this tick.
     *
     * **Read off the same list the sky is drawing**, rather than rolled here: a body announced in the sky
     * and a body dropped on the ground have to be the same body, and the only way to promise that without
     * sending anything is for both sides to ask the same pure function.
     *
     * **A walk through the list rather than a search for this exact tick** (Jonah, walked). Asking which
     * bodies were due *now* meant any tick that never came was a body that never fell — and there were
     * several: the earliest bodies are all clamped to the first tick of the fall, which the old guard
     * skipped outright, and a chunk that stops ticking loses every moment it was asleep for. Walking an
     * index throws each body exactly once whatever the clock did, and lets a body come down at once when
     * its moment is long gone, since it is thrown already as old as it should be.
     *
     * Capped per tick, so catching up on a storm that was left alone for an hour is a busy few seconds
     * rather than three hundred explosions between two frames.
     */
    private fun dropSome(level: ServerLevel) {
        val age = age
        var thrown = 0
        while (droppedTo < bodies && thrown < MOST_IN_A_TICK) {
            val flight = flightOf(droppedTo)
            if (flight.fallsAt > age) return
            throwOne(level, flight, level.gameTime - (age - flight.fallsAt))
            droppedTo++
            thrown++
        }
    }

    /** How many of this storm's bodies have been thrown. Saved, so a reload does not throw them again. */
    private var droppedTo = NONE_THROWN_YET

    /**
     * One body, entering obliquely at the angle its flight drew.
     *
     * **Aimed at the ground rather than dropped from overhead** (Jonah, 2026-09-06): a body that comes
     * straight down is on screen for a moment and reads as a falling block, where one entering between ten
     * and forty-five degrees crosses the sky and reads as something arriving from somewhere else. It is
     * placed back along its own entry line and pointed at where it is going, so however shallow the angle
     * it still lands where it was announced.
     */
    private fun throwOne(level: ServerLevel, flight: MeteorFlight, dueAt: Long) {
        // **Thrown from exactly where its own light was, at exactly the angle that light came in on.**
        // Both ends of the line are [landingOf] and [seenFrom] now rather than one of each worked out
        // here: the handover is a light becoming a rock, and it has to keep the angle as well as the
        // place, which asking the ground a second time in this method is precisely how it stopped doing.
        val from = seenFrom(flight, ARRIVING)
        val toTheGround = landingOf(flight).subtract(from)
        val speed = SLOWEST_ARRIVAL + (FASTEST_ARRIVAL - SLOWEST_ARRIVAL) * fury
        val body = Meteor(AgeContent.METEOR, level)
        body.blast = (AT_REST + (AT_FULL_FURY - AT_REST) * fury).toFloat()
        body.setPos(from.x, from.y, from.z)
        body.setDeltaMovement(toTheGround.normalize().scale(speed))
        // Aged from the moment it was *due* rather than the moment it was thrown, so one whose moment went
        // by while nobody was here is already past its flight and comes straight down.
        body.thrownAt = dueAt
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
        output.putFloat(SLANT_KEY, slant)
        output.putLong(GATHERED_KEY, gatheredAt)
        output.putInt(DROPPED_KEY, droppedTo)
        output.putFloat(REACH_KEY, reach)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        falling = input.getIntOr(FALLING_KEY, SHORTEST_FALL)
        fury = input.getDoubleOr(FURY_KEY, ORDINARY_FURY)
        bodies = input.getIntOr(BODIES_KEY, FEW)
        slant = input.getFloatOr(SLANT_KEY, UNASKED)
        gatheredAt = input.getLongOr(GATHERED_KEY, NEVER)
        droppedTo = input.getIntOr(DROPPED_KEY, NONE_THROWN_YET)
        reach = input.getFloatOr(REACH_KEY, REACH.toFloat())
    }

    companion object {
        private val BODIES: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(MeteorStorm::class.java, EntityDataSerializers.INT)
        private val FALLING: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(MeteorStorm::class.java, EntityDataSerializers.INT)
        private val SLANT: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(MeteorStorm::class.java, EntityDataSerializers.FLOAT)
        private val GATHERED_AT: EntityDataAccessor<Long> =
            SynchedEntityData.defineId(MeteorStorm::class.java, EntityDataSerializers.LONG)
        private val REACHES: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(MeteorStorm::class.java, EntityDataSerializers.FLOAT)

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
         * How far the pounding would reach from its middle if there were room for it — **as far as you can
         * run in the warning you are given** (Jonah), which is why it is worked out here rather than
         * chosen. A radius, so the disc is twice this across; every reach in this phenomenon is one, a
         * lure's included.
         *
         * **A ceiling rather than the figure a storm gets.** [Meteors.withinSight] is what usually binds,
         * and under a twelve-chunk view it binds well under this — a storm that spread this wide put a
         * third of its bodies where no client could hold the ground they were aimed at. Nothing about the
         * counterplay breaks when it comes down, since a narrower disc is one you leave sooner.
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

        /** No angle a storm can come in at, so it reads as "nobody named one" — see [slant]. */
        private const val UNASKED = 0.0f

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

        /**
         * How fierce a body is, nought to one, read back off how hard it lands.
         *
         * The one definition of that scale, so a body can be *worth* what it costs without being told a
         * second number it would then have to carry and save.
         */
        fun fiercenessOf(blast: Float): Double =
            ((blast - AT_REST) / (AT_FULL_FURY - AT_REST)).coerceIn(NONE_OF_IT.toDouble(), ALL_OF_IT.toDouble())

        /**
         * How far back along its own entry line a body starts — far enough to cross real sky, and **the
         * first charge on what a watcher's client can hold** ([Meteors.withinSight]).
         *
         * A body is thrown from here rather than from where it lands, so this much of the budget is spent
         * before the storm has any width at all: at the usual twelve chunks it is over half of it. It buys
         * about five ticks of visible flight, and lengthening it takes blocks off the disc.
         */
        const val ENTRY_RANGE = 96.0

        /**
         * How far out a body's light hangs when it first appears, in blocks.
         *
         * **Far beyond anything that renders, which is the illusion the whole telegraph rests on.** A
         * shower has to look like it is arriving from somewhere else, and at this range the whole disc the
         * bodies are aimed at is about half a degree of sky — a moon's width for the lot of them — so they
         * start as one light and come apart as they close, with no animation saying so.
         */
        const val TELEGRAPHED_FROM = 12000.0

        /**
         * How fast a light closes, in blocks a tick — the whole approach over the whole telegraph.
         *
         * **And therefore how fast the rock comes in.** The handover is the one moment the two are the
         * same object, so the body leaves at exactly the speed the light arrived at and a fierce Age
         * throws them faster still. Derived rather than written down, so that moving the telegraph or the
         * range it is watched from cannot quietly put them out of step again (Jonah, walked twice).
         */
        const val CLOSING_IN = (TELEGRAPHED_FROM - ENTRY_RANGE) / APPROACHING

        /** What being fierce adds on top of that. */
        private const val HARDER_STILL = 1.4

        private const val SLOWEST_ARRIVAL = CLOSING_IN
        private const val FASTEST_ARRIVAL = CLOSING_IN * HARDER_STILL

        private const val FALLING_KEY = "falling"
        private const val SLANT_KEY = "slant"
        private const val GATHERED_KEY = "gathered_at"
        private const val DROPPED_KEY = "dropped_to"
        private const val REACH_KEY = "reach"

        /**
         * **Minus one, because zero is a time.** A storm that gathered on tick zero — a fresh world, or a
         * level whose clock reads zero — matched the sentinel, so its age stayed [JUST_GATHERED] for ever:
         * it never aged past its own flight, never dropped a body and never discarded. `Meteor` already
         * used −1 for the same reason.
         */
        private const val NEVER = -1L
        private const val JUST_GATHERED = 0
        private const val NONE_THROWN_YET = 0

        /** How many bodies may be thrown in one tick while a storm catches up on what it slept through. */
        private const val MOST_IN_A_TICK = 3
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
        fun gatherAt(
            level: ServerLevel,
            where: Vec3,
            bodies: Int,
            falling: Int,
            fury: Double,
            slant: Double? = null,
            reach: Double = REACH,
        ): MeteorStorm {
            val storm = MeteorStorm(AgeContent.METEOR_STORM, level)
            storm.setPos(where.x, where.y, where.z)
            storm.bodies = bodies
            storm.falling = falling
            storm.fury = fury
            storm.gatheredAt = level.gameTime
            storm.reach = reach.toFloat()
            if (slant != null) storm.slant = slant.toFloat()
            level.addFreshEntity(storm)
            return storm
        }
    }
}
