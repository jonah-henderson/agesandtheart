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
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.MoverType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

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
        // **The push first, because it is the only term that can be expensive**, and it answers zero for
        // nearly every body on nearly every tick — see [pushedFromRock].
        val pushed = pushedFromRock()
        val sought = towardItsBand()
        val settled = deltaMovement.add(pushed).add(sought).scale(SETTLING)
        // Drift is added rather than accumulated, so it is a *current* the body sits in rather than a
        // shove it remembers: everything charged in the Age moves the same way at the same speed, which
        // is what sells a field they are all following.
        deltaMovement = settled
        move(MoverType.SELF, settled.add(driftOf(level())))
        if (!level().isClientSide) discardIfNobodyIsAround()
    }

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
                val chunk = level().getChunk(chunkX, chunkZ)
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
        return Vec3(0.0, off.coerceIn(-RISE, RISE) * HOMING, 0.0)
    }

    /** Nothing to look at and nobody looking: a body far from every player is not worth ticking. */
    private fun discardIfNobodyIsAround() {
        if (tickCount % LOOKED_FOR_EVERY != 0) return
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
            repeat(FRAGMENTS) {
                val piece = AgeContent.DRIFTING_ORE.create(level, EntitySpawnReason.TRIGGERED) ?: return@repeat
                piece.tier = tier - 1
                piece.shape = level.random.nextInt(OreClusters.SHAPES)
                piece.snapTo(position().add(scattered(level.random), scattered(level.random), scattered(level.random)))
                // Thrown outward rather than dropped, so a break reads as one and the pieces are not a
                // stack of bodies at the same point arguing about where to be.
                piece.deltaMovement = Vec3(scattered(level.random), scattered(level.random), scattered(level.random))
                    .normalize().scale(THROWN)
                level.addFreshEntity(piece)
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

    private fun scattered(random: RandomSource): Double = (random.nextDouble() - HALF) * SCATTER

    override fun isPickable(): Boolean = true

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

        /** And what the smallest gives, which is the only tier that yields the material itself. */
        private const val YIELD_LEAST = 2
        private const val YIELD_MOST = 4

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
        private const val HALF = 0.5

        /** How much of its motion a body keeps from tick to tick. Heavy: these wallow rather than dart. */
        private const val SETTLING = 0.82

        /** How hard a shove is at its strongest, before the tier multiplies it. */
        private const val SHOVE = 0.06
        private const val SMALLEST_SHOVE = 1.0
        private const val SHOVE_A_TIER = 0.35

        private const val SMALLEST_REACH = 4.0
        private const val REACH_A_TIER = 1.5

        /** Nearer than this and the direction away is meaningless, so it is left to the next tick. */
        private const val CLOSEST = 0.001
        private const val NOTHING = 1.0e-6

        /** How close to its band counts as home, and how fast it climbs back. */
        private const val AT_HOME = 0.35
        private const val HOMING = 0.045
        private const val RISE = 12.0

        private const val DRIFT_SPEED = 0.017
        private const val DRIFT_SALT = 0.000_37

        /** How often the emptiness around it is checked, and how far away is far enough to forget it. */
        private const val LOOKED_FOR_EVERY = 40
        private const val FORGOTTEN_BEYOND = 192.0

        private const val SMALLEST_TOUGHNESS = 6.0f
        private const val TOUGHNESS_A_TIER = 6.0f

        /** How far a fragment starts from where its parent was, and how hard it is thrown clear. */
        private const val SCATTER = 2.4
        private const val THROWN = 0.22
    }
}
