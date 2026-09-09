package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.content.AgeContent
import kotlin.math.abs
import net.minecraft.core.BlockPos
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityDimensions
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.InterpolationHandler
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.MoverType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * Ore that will not be caught: a body of charged rock adrift at altitude, which an Age with a charged sky
 * puts up and nothing built on the ground can bring down (design §7.1.2).
 *
 * **The spine of it is an inversion.** Astrite rewards building a big soft catcher; this punishes building
 * anything at all — every block repels it, so a pillar pushes its own target away and a catching station
 * shoves the thing it was built for out over the next valley. What is left is shooting it, flying to it,
 * or tethering it, and that is the whole of what makes the method the star rather than the material.
 *
 * **A player does not repel it.** An elytra daredevil who flies up and breaks one in the air has earned it.
 *
 * **Its tier is the whole design.** A tier decides how big a body is, what it yields, and **which band it
 * calls home** — so a fragment does not need to be told to sink: it seeks the home its own tier gives it,
 * and descending is what being smaller *means* here. Break the biggest for pieces of the next tier down,
 * break those again, and the lowest yields the ore. The bottom band being poor is the same rule read from
 * the other end — only the smallest tier spawns there.
 */
class DriftingOre(type: EntityType<out DriftingOre>, level: Level) : Entity(type, level) {

    /** How big a body this is, and so what it yields and where it belongs — see the class note. */
    var tier: Int
        get() = entityData.get(TIER)
        set(value) = entityData.set(TIER, value.coerceIn(0, MOST_TIERS - 1))

    /**
     * Which of [OreClusters.SHAPES] this body weathered into — **the whole of what is sent about its
     * shape**, since server and client grow the same rock from the same number.
     */
    var shape: Int
        get() = entityData.get(SHAPE)
        set(value) = entityData.set(SHAPE, Math.floorMod(value, OreClusters.SHAPES))

    /**
     * The height this body returns to when nothing is acting on it — **its tier's band, asked rather than
     * remembered**, which is what makes a fragment sink with no code for sinking anywhere.
     */
    private val homeY: Double get() = ChargedBands.homeFor(level(), tier)

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(TIER, 0)
        builder.define(SHAPE, 0)
    }

    /**
     * As big as the rock actually is — **two, four or six across**, rather than the one size an
     * `EntityType` can be told at registration.
     *
     * It has to be the cluster's own span or the two halves disagree about where the body is: a box
     * smaller than the rock means arrows pass through stone, and a larger one means a player stands on
     * air. What the type was registered with is only the fallback for a body whose tier has not arrived.
     */
    override fun getDimensions(pose: Pose): EntityDimensions =
        OreClusters.spanOf(tier).toFloat().let { EntityDimensions.fixed(it, it) }

    /**
     * And the box is **centred on the body**, where vanilla's stands on it.
     *
     * A rock's position is its middle: the bands it seeks are heights of its middle, and it is drawn from
     * its middle outward. A box rising from `y` would have put the whole cluster half a body below what
     * anything else in the world thought it was hitting.
     */
    override fun makeBoundingBox(at: Vec3): AABB {
        val across = bbWidth.toDouble()
        return AABB.ofSize(at, across, bbHeight.toDouble(), across)
    }

    /** And it is rebuilt when the tier arrives, since a client learns that after the entity itself. */
    override fun onSyncedDataUpdated(key: EntityDataAccessor<*>) {
        if (key == TIER) refreshDimensions()
        super.onSyncedDataUpdated(key)
    }

    override fun tick() {
        super.tick()
        val before = position()
        if (level().isClientSide) {
            // **The client never simulates one, it only catches up.** Both sides running the physics was
            // the jitter: the client's `deltaMovement` starts at nothing and is never sent, so the two
            // drift apart and every position packet snapped the body back. A plain `Entity` returns no
            // interpolation handler, so a packet *is* a snap — see [getInterpolation].
            interpolation.interpolate()
        } else {
            // **The push first, because it is the only term that can be expensive**, and it answers zero
            // for nearly every body on nearly every tick — see [pushedFromRock].
            val pushed = pushedFromRock()
            val sought = towardItsBand()
            val settled = deltaMovement.add(pushed).add(sought).scale(SETTLING)
            // Drift is added rather than accumulated, so it is a *current* the body sits in rather than a
            // shove it remembers: everything charged in the Age moves the same way at the same speed,
            // which is what sells a field they are all following.
            deltaMovement = settled
            move(MoverType.SELF, settled.add(driftOf(level()).scale(driftPace)))
            discardIfNobodyIsAround()
        }
        // **Sideways only.** Vanilla already lifts whatever is standing on a box that rises and drops it
        // when the box falls, so adding the vertical delta on top applied it twice: a rider was shoved up,
        // resolved down, shoved up again — a shudder that got worse the smaller the body was, because a
        // player on a two-cube has nowhere to settle. Sideways is the part vanilla does not do.
        val moved = position().subtract(before)
        carryWhatStandsOnIt(Vec3(moved.x, 0.0, moved.z))
    }

    /**
     * **A body that holds still can be stood on; one that is going somewhere has to take you with it.**
     *
     * Vanilla carries *passengers*, never riders on a roof, so without this a boulder slides out from
     * under whoever climbed on — which is the whole of what makes one worth landing on rather than
     * bouncing off.
     *
     * **Each side carries what it owns.** A server moving a player fights that player's own client and
     * rubber-bands; a client moving a mob is drawing a lie the next packet corrects. So the server takes
     * everything that is not a player and the client takes the players, which is exactly the split the
     * game already makes about who decides where a thing is.
     */
    private fun carryWhatStandsOnIt(moved: Vec3) {
        if (moved.lengthSqr() < NOTHING) return
        val ledge = boundingBox.setMinY(boundingBox.maxY).expandTowards(0.0, A_FOOT, 0.0)
        for (rider in level().getEntities(this, ledge) { it !== this }) {
            if (level().isClientSide != (rider is Player)) continue
            rider.setPos(rider.position().add(moved))
        }
    }

    /**
     * How a client is told where this is: **caught up to over as many ticks as the server waits between
     * telling it**, so the two beats line up and there is no held frame at the end of one.
     */
    private val interpolation = InterpolationHandler(this, AgeContent.DRIFTING_ORE_UPDATE_TICKS)

    override fun getInterpolation(): InterpolationHandler = interpolation

    /**
     * Away from every block within reach, or nothing at all — **and the common case is nothing**.
     *
     * At the height these fly there is usually no rock within reach in any direction, and a chunk section
     * knows that about itself: `hasOnlyAir` is a flag off the palette rather than a walk of four thousand
     * blocks. So the ordinary tick asks a handful of sections whether they hold anything and stops. That
     * is the same dismissal `TintedLights.stocked` and `Lures` make, and it is what keeps a sky full of
     * these affordable.
     *
     * Only where a section *does* hold something is the neighbourhood actually walked.
     */
    private fun pushedFromRock(): Vec3 {
        val reach = reachOf(tier)
        if (nothingStandsNear(reach)) return Vec3.ZERO
        val from = position()
        var away = Vec3.ZERO
        val at = BlockPos.MutableBlockPos()
        val least = BlockPos.containing(from.subtract(reach, reach, reach))
        val most = BlockPos.containing(from.add(reach, reach, reach))
        for (x in least.x..most.x) for (y in least.y..most.y) for (z in least.z..most.z) {
            at.set(x, y, z)
            // **Any block at all**, not merely a solid one, so glass and scaffolding are no cleverer than
            // stone and a catching station cannot be built out of the things that usually get around a
            // rule like this.
            if (level().getBlockState(at).isAir) continue
            val toward = from.subtract(x + HALF, y + HALF, z + HALF)
            val span = toward.length()
            if (span > reach || span < CLOSEST) continue
            // Falls off with distance, so a wall shoves and a pebble nudges, and a body deep inside a
            // structure is pushed out of it rather than held in the middle of everything at once.
            away = away.add(toward.scale((1.0 - span / reach) / span))
        }
        return if (away.lengthSqr() < NOTHING) Vec3.ZERO else away.normalize().scale(SHOVE * strengthOf(tier))
    }

    /**
     * Whether every chunk section this body could reach into is empty air.
     *
     * Asked of the sections rather than the blocks, which is the whole saving: a section answers in one
     * read, and a body at the build limit is usually inside one that has never held anything.
     */
    private fun nothingStandsNear(reach: Double): Boolean {
        val from = position()
        val least = BlockPos.containing(from.subtract(reach, reach, reach))
        val most = BlockPos.containing(from.add(reach, reach, reach))
        for (chunkX in (least.x shr SECTION_BITS)..(most.x shr SECTION_BITS)) {
            for (chunkZ in (least.z shr SECTION_BITS)..(most.z shr SECTION_BITS)) {
                // **Never `getChunk`, which loads or generates.** A body drifting at the edge of the
                // simulated area reaches into a column nobody has been to, and asking for it would stall
                // the server thread generating one, every tick, for every body. Every other hot scan in
                // this codebase takes the same escape (`Arcs.crystalsNear`, `Lures`, `Worsening`).
                val chunk = level().chunkSource.getChunkNow(chunkX, chunkZ) ?: continue
                for (y in (least.y shr SECTION_BITS)..(most.y shr SECTION_BITS)) {
                    val index = chunk.getSectionIndexFromSectionY(y)
                    if (index < 0 || index >= chunk.sections.size) continue
                    if (!chunk.sections[index].hasOnlyAir()) return false
                }
            }
        }
        return true
    }

    /**
     * Back toward [homeY], and **only vertically** — being shoved sideways is not something a body undoes,
     * so a shoved one drifts on from wherever it was left rather than swimming back to where it was.
     *
     * A pull rather than a snap, and damped by [SETTLING] on the way, so a body that has been pushed down
     * rises through the levels it was knocked past instead of springing back through them.
     */
    private fun towardItsBand(): Vec3 {
        val off = homeY - y
        if (abs(off) < AT_HOME) return Vec3.ZERO
        return Vec3(0.0, off.coerceIn(-RISE, RISE) * homingRate() * climbPace, 0.0)
    }

    /**
     * How fast a body climbs or sinks to its own band, at most.
     *
     * **Twice the drift and no more** (Jonah, 2026-09-09) — a long graceful ascent rather than a plummet,
     * which is what makes a fragment sinking to the band below something you watch rather than something
     * that has already happened. Derived from the drift and the damping rather than written down, so the
     * relationship survives either of them being retuned.
     */
    private fun homingRate(): Double = DRIFT_SPEED * SEEKS_AGAINST_DRIFT * (1.0 - SETTLING) / RISE

    /**
     * Nothing to look at and nobody looking: a body far from every player is not worth ticking.
     *
     * **Measured against the tracking range rather than a figure of its own.** `getNearestPlayer` asks in
     * three dimensions, and the highest band is at the build limit — a body up there is 236 blocks from a
     * player standing at sea level *directly underneath it*, so a radius chosen as a plausible-sounding
     * number deleted the whole top band seconds after it spawned, which is the tier the entire ladder
     * climbs towards. The honest rule is the one the client already uses: forget a body when nobody is
     * being sent it.
     */
    private fun discardIfNobodyIsAround() {
        if (level().gameTime % LOOKED_FOR_EVERY != 0L) return
        if (level().getNearestPlayer(this, FORGOTTEN_BEYOND) == null) discard()
    }

    /**
     * **Broken rather than killed, and what breaking gives you is the tier below.**
     *
     * A body takes hits until it comes apart, and what it comes apart into is several of the next size
     * down — which are already looking for a lower band, so they descend on their own. Break those again
     * for the tier under that, and the smallest for the ore itself. That is why the richest bodies are at
     * the top: reaching one is the climb, and getting it home is three breakings.
     */
    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean {
        if (isRemoved) return false
        struck += amount
        if (struck < holdsTogether(tier)) return true
        breakApart(level)
        return true
    }

    private fun breakApart(level: ServerLevel) {
        if (tier > 0) {
            // **Laid out clear of one another rather than scattered from a point.** Fragments used to be
            // jittered off the middle, which was fine while a body was a hitbox and is not now they are
            // shaped and solid to each other: three of them overlapping at birth spent their lives shoving
            // and getting nowhere, so a break read as one rock going lumpy rather than as three coming
            // apart. Set out around a ring instead, wide enough that no two can touch however the ring is
            // turned and however far each wanders off its share of it — see [ringRadiusFor], which is
            // checked rather than reasoned about, the margin at a radius of one span being 1%.
            val clear = ringRadiusFor(OreClusters.spanOf(tier - 1))
            val turned = level.random.nextDouble() * A_FULL_TURN
            for (piece in 0..<FRAGMENTS) {
                val body = AgeContent.DRIFTING_ORE.create(level, EntitySpawnReason.TRIGGERED) ?: continue
                body.tier = tier - 1
                body.shape = level.random.nextInt(OreClusters.SHAPES)
                val bearing = turned + piece * (A_FULL_TURN / FRAGMENTS) +
                    (level.random.nextDouble() - HALF) * A_FULL_TURN * WANDER_OFF_THE_RING
                val outward = Vec3(Math.cos(bearing), 0.0, Math.sin(bearing))
                // Free to be off the ring vertically: the horizontal spacing alone keeps them apart,
                // their boxes being upright cubes.
                body.snapTo(position().add(outward.scale(clear)).add(0.0, scattered(level.random) * clear, 0.0))
                // And thrown outward as well as set out, so the ring is a moment rather than an
                // arrangement and they visibly come apart before they start seeking their own band.
                body.deltaMovement = outward.scale(THROWN)
                level.addFreshEntity(body)
            }
        }
        else {
            // The smallest tier is where the ore actually comes out. Three of three is nine, so a body
            // from the top band is worth nine of these — which is the arithmetic that pays for the climb.
            val yield = YIELD_LEAST + level.random.nextInt(YIELD_MOST - YIELD_LEAST + 1)
            repeat(yield) { spawnAtLocation(level, ItemStack(AgeContent.ARC_CRYSTAL)) }
        }
        discard()
    }

    private fun scattered(random: RandomSource): Double = random.nextDouble() - HALF

    override fun isPickable(): Boolean = true

    /**
     * **Nothing collides with the box, because the box is a third gaps.**
     *
     * Answering yes here is what makes vanilla add `Shapes.create(getBoundingBox())` for this body, and a
     * weathered rock does not fill its own cube — so a player stood on air a third of the time. Saying no
     * takes the box out of the reckoning and `OreColliders` puts [collider] in instead, through the one
     * seam there is (`notes/authoring-tools.md` Part IV).
     *
     * The box still matters for everything that is *not* collision: it is what decides whether this is
     * near enough to be worth asking about at all.
     */
    override fun canBeCollidedWith(against: Entity?): Boolean = false

    /** The cluster's own outline, where it is standing — see [OreClusters.shapeOf]. */
    fun collider(): VoxelShape = OreClusters.shapeOf(shape, tier).move(position())

    override fun readAdditionalSaveData(input: ValueInput) {
        tier = input.getIntOr(TIER_KEY, 0)
        shape = input.getIntOr(SHAPE_KEY, 0)
        struck = input.getFloatOr(STRUCK_KEY, 0.0f)
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        output.putInt(TIER_KEY, tier)
        output.putInt(SHAPE_KEY, shape)
        output.putFloat(STRUCK_KEY, struck)
    }

    /** How much it has taken so far. Not synched: nothing is drawn from it. */
    private var struck: Float = 0.0f

    /**
     * **How fast this one takes the Age's current, and how eagerly it seeks its band** — a little either
     * side of its fellows (Jonah, 2026-09-09).
     *
     * The *direction* stays shared, which is the point of the drift: everything charged in the Age going
     * one way is what sells a field they are all following. What was wrong was that they went at exactly
     * one speed too, so a sky of them slid about like a single sheet. Varying only the pace keeps the
     * current and loses the rigidity.
     *
     * Rolled per body and never sent: the client stopped simulating when it started interpolating, so this
     * is the server's business alone and costs nothing to keep.
     */
    private val driftPace = PACE_LEAST + random.nextDouble() * (PACE_MOST - PACE_LEAST)
    private val climbPace = PACE_LEAST + random.nextDouble() * (PACE_MOST - PACE_LEAST)

    companion object {
        private val TIER: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(DriftingOre::class.java, EntityDataSerializers.INT)
        private val SHAPE: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(DriftingOre::class.java, EntityDataSerializers.INT)

        private const val TIER_KEY = "tier"
        private const val SHAPE_KEY = "shape"
        private const val STRUCK_KEY = "struck"

        /** How much a body of this tier takes before it comes apart. Bigger bodies are more work. */
        fun holdsTogether(tier: Int): Float = SMALLEST_TOUGHNESS + tier * TOUGHNESS_A_TIER

        /** How many pieces of the tier below a break gives. Read with the tiers: three of three is nine. */
        const val FRAGMENTS = 3

        /**
         * And what the smallest gives, which is the only tier that yields the material itself.
         *
         * **Halved 2026-09-09** (Jonah: "loads of crystals from just a few boulders"). Read against the
         * ladder rather than against one body: three fragments of three means a top-band body is nine of
         * these, so even one apiece is nine to eighteen crystal for a climb, a shot and three breakings.
         */
        private const val YIELD_LEAST = 1
        private const val YIELD_MOST = 2

        /** How many sizes there are, and so how many bands and how many breakings to the ore. */
        const val MOST_TIERS = 3

        /** How far a body of this tier feels rock, in blocks. Bigger bodies are pushed from further. */
        fun reachOf(tier: Int): Double = SMALLEST_REACH + tier * REACH_A_TIER

        /** And how hard. A big one shoulders past a wall a small one is turned by. */
        fun strengthOf(tier: Int): Double = SMALLEST_SHOVE + tier * SHOVE_A_TIER

        /**
         * Which way everything charged in this Age drifts, and how fast — **one current for the whole
         * world**, drawn from the level's own seed so every body agrees without being told.
         */
        fun driftOf(level: Level): Vec3 {
            val bearing = level.dimension().identifier().hashCode() * DRIFT_SALT
            return Vec3(Math.cos(bearing), 0.0, Math.sin(bearing)).scale(DRIFT_SPEED)
        }

        private const val SECTION_BITS = 4

        /** How far above a body something has to be to count as standing on it. */
        private const val A_FOOT = 0.35
        private const val HALF = 0.5

        /** How much of its motion a body keeps from tick to tick. Heavy: these wallow rather than dart. */
        private const val SETTLING = 0.82

        /**
         * How hard a shove is at its strongest, before the tier multiplies it.
         *
         * **Raised five times over 2026-09-09**, in two goes and both by eye. The inversion is the whole
         * design — a body has to be visibly unable to be caught — so this wants to read as *repelled*,
         * holding five or six blocks off anything built, rather than as drifting past a wall it happens
         * to avoid.
         *
         * Against [SETTLING], full shove settles at about a block and a half a tick, and half again for
         * the largest bodies. It is only ever that at point blank: the falloff across [reachOf] is what
         * makes the standoff a distance rather than a wall.
         */
        private const val SHOVE = 0.30
        private const val SMALLEST_SHOVE = 1.0
        private const val SHOVE_A_TIER = 0.35

        private const val SMALLEST_REACH = 4.0
        private const val REACH_A_TIER = 1.5

        /** Nearer than this and the direction away is meaningless, so it is left to the next tick. */
        private const val CLOSEST = 0.001
        private const val NOTHING = 1.0e-6

        /** How close to its band counts as home, and how far off it the climb is at full speed. */
        private const val AT_HOME = 0.35
        private const val RISE = 12.0

        /** And how fast that climb is, against the drift — see [homingRate]. */
        private const val SEEKS_AGAINST_DRIFT = 2.0

        /** Doubled 2026-09-09 (Jonah): a sky that barely moves reads as scenery rather than weather. */
        private const val DRIFT_SPEED = 0.034
        private const val DRIFT_SALT = 0.000_37

        /** How often the emptiness around it is checked, and how far away is far enough to forget it. */
        private const val LOOKED_FOR_EVERY = 40L

        /** A fact about the bands rather than about this entity — see [ChargedBands.FORGOTTEN_BEYOND]. */
        private const val FORGOTTEN_BEYOND = ChargedBands.FORGOTTEN_BEYOND

        private const val SMALLEST_TOUGHNESS = 6.0f
        private const val TOUGHNESS_A_TIER = 6.0f

        /** How hard a fragment is thrown clear of the ring it is set out on. */
        private const val THROWN = 0.35

        /** How far off its share of the ring a fragment may sit, so three do not read as a diagram. */
        const val WANDER_OFF_THE_RING = 0.08

        /**
         * How wide a ring [FRAGMENTS] of a body this wide are set out on, so none of them touch.
         *
         * **A quarter wider than it strictly needs to be.** At exactly one span the worst turn of the ring
         * leaves a hundredth of a block between two boxes, which is clear on paper and not something to
         * build a break on; this leaves a quarter of a span. `DriftingOreCheck` holds it.
         */
        fun ringRadiusFor(span: Double): Double = span * RING_ROOM

        private const val RING_ROOM = 1.25

        private const val A_FULL_TURN = Math.PI * 2

        /** How far either side of its fellows a body drifts and climbs — see [driftPace]. */
        private const val PACE_LEAST = 0.75
        private const val PACE_MOST = 1.25
    }
}
