package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.AABB
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * One column of sand, walking (design §5.2.2).
 *
 * **An entity, which is §5.4's third shape and the one that ruling already blessed for a tornado**: a
 * position and a heading are recorded by no block and cannot be derived, since where a column goes next
 * depends on nothing the world has written down. The ruling was never "nothing may persist" — it is that
 * the mod keeps no ledger beside the recipe, and an entity is chunk data the world persists for us.
 *
 * Two things follow from being one, and both are wanted. **It stops while nobody is there**, because an
 * entity in an unloaded chunk is not ticked — §5.4 says a rising sea should advance in your absence and a
 * sandstorm should not. And **it is lost harmlessly**: a column that goes with a chunk costs a walk rather
 * than a migration.
 *
 * **Its heading is its own yaw**, which is why nothing here syncs one. Vanilla already sends `yRot` with
 * the position, so the renderer gets the direction of travel for free and the prism turns with it.
 *
 * **What is synced is the width, not the clock that decides it.** The ramp is arithmetic over the Age's
 * dials ([SandfallBehaviour.halfWidthAt]) and a client has no datapack to read them from, so it is computed
 * once, here, and the answer is sent. A client that re-derived it would be a second copy of the rule to
 * keep in step, and the value it would need is larger than the value itself.
 */
class SandColumn(type: EntityType<out SandColumn>, level: Level) : Entity(type, level) {

    /** How wide it stands this instant — half the prism's side, in blocks. Nothing at either end of its life. */
    var halfWidth: Float
        get() = entityData.get(HALF_WIDTH)
        private set(value) = entityData.set(HALF_WIDTH, value)

    /**
     * Half the side of the solid middle — **the one definition of it**, because two things need to agree
     * about where it is: the renderer draws the opaque shell there, and the air goes blind inside it.
     * A rectangle stated twice is the defect the UI layer exists to prevent, and it is no different here.
     *
     * A **constant** two blocks in from the outside rather than a share of the width, so a wide column is
     * mostly solid rather than mostly haze. The share is a floor for the ends of a life, when two blocks
     * would be the whole column and there would be no shell left to see it through.
     */
    val coreHalfWidth: Float
        get() = max(halfWidth - SHELL_BLOCKS, halfWidth * LEAST_CORE_SHARE)

    /** Whether [atX], [atZ] is inside the column's own turned square of half-width [reach]. */
    fun covers(atX: Double, atZ: Double, reach: Double): Boolean {
        if (reach <= NOTHING) return false
        val heading = yRot.toDouble() * Mth.DEG_TO_RAD
        val forwardX = -sin(heading)
        val forwardZ = cos(heading)
        val offsetX = atX - x
        val offsetZ = atZ - z
        val along = abs(forwardX * offsetX + forwardZ * offsetZ)
        val across = abs(forwardZ * offsetX - forwardX * offsetZ)
        return max(along, across) <= reach
    }

    /** How many ticks it has stood. */
    var age: Int = 0
        private set

    /** How many it will stand for. */
    var lifetime: Int = 0
        private set

    /** How fast it walks, in blocks per tick — rolled once at spawn, and never again. */
    var speed: Double = 0.0
        private set

    /**
     * How wide it will stand once it is open — half a side, in blocks, rolled once at spawn.
     *
     * **What the deposit rate divides by, and never [halfWidth].** The rate is derived to leave a fixed
     * depth after one pass, so dividing by the width the column *happens* to be standing at saturates it at
     * certainty as the column closes and leaves a tower of sand where a trail should be. Its own full
     * width is constant for its life, which is what makes it safe to divide by.
     */
    var fullHalfWidth: Double = 0.0
        private set

    /**
     * How deep one pass of this column leaves the ground — its own, rolled at spawn.
     *
     * **Per column and not read live**, for the same reason its width is: an Age's instability decides how
     * hard its columns bury, and a rate re-read every tick would change under a column halfway through its
     * life if anything ever rewrote the book it came from.
     */
    var depth: Double = 0.0
        private set

    /** How many ticks since anybody was near enough to see it. */
    private var sinceSeen: Int = 0

    init {
        noPhysics = true
        isNoGravity = true
    }

    /**
     * How fast this column's sand pours, as a share of the fastest anything may — what the shader reads to
     * make a column that buries deeper visibly stream harder.
     *
     * **Synced, and for the same reason [halfWidth] is**: it is arithmetic over the Age's dials and a client
     * has no datapack to read them from, so it is settled once here and the answer is sent.
     */
    var pour: Float
        get() = entityData.get(POUR)
        private set(value) = entityData.set(POUR, value)

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(HALF_WIDTH, 0.0f)
        builder.define(POUR, 0.0f)
    }

    override fun tick() {
        super.tick()
        val level = level()
        if (level !is ServerLevel) return
        age += 1
        val behaviour = SandfallBehaviour.of(level.server)
        val watched = level.getNearestPlayer(this, behaviour.forgottenAt) != null
        sinceSeen = if (watched) FRESHLY_SEEN else sinceSeen + 1
        // Gone when its life is out, and gone when nobody has been near it for a while — the second is what
        // stops a column standing in a corner of an Age nobody walks to. **A grace rather than an instant**,
        // or a column would vanish out from under anybody who stepped behind a hill.
        if (age > lifetime || sinceSeen > behaviour.forgottenAfter) {
            discard()
            return
        }
        halfWidth = behaviour.column.halfWidthAt(age, lifetime, fullHalfWidth).toFloat()
        steer(behaviour.column)
        advance(level)
        bury(level, behaviour.column)
        drive(level, behaviour.column)
        beHeard(level, behaviour.column)
    }

    /**
     * What standing in it does: **everything caught under the sand is driven down, hard.**
     *
     * **There is no counterplay to being under a column and that is the design** — a sandfall's answer is
     * not to be where it is, which is what its telegraph buys you. So this is not a nudge: it scales with
     * how wide the column stands, so the twenty-across one an unstable Age sends is not survivable by
     * walking, and it stacks with whatever the sand is doing to the space above your head.
     *
     * `hurtMarked` is what makes it reach a player at all: movement is the client's to decide, and this is
     * vanilla's own way of saying otherwise — the same flag knockback sets.
     */
    private fun drive(level: ServerLevel, behaviour: ColumnBehaviour) {
        val standing = halfWidth.toDouble()
        if (standing <= NOTHING || behaviour.push <= NOTHING) return
        val force = behaviour.push * (standing / behaviour.widestHalfWidth)
        for (caught in level.getEntitiesOfClass(LivingEntity::class.java, sweptVolume(level, standing))) {
            if (!covers(caught.x, caught.z, standing)) continue
            caught.push(NOTHING, -force, NOTHING)
            caught.hurtMarked = true
        }
    }

    /** From the ground it walks on to the top of the world, which is all of what the column covers. */
    private fun sweptVolume(level: ServerLevel, standing: Double): AABB =
        AABB(x - standing, y, z - standing, x + standing, level.maxY.toDouble(), z + standing)

    /**
     * The sound of it — **fire, borrowed, and openly a stopgap** (Jonah, 2026-08-31).
     *
     * A crackle played fast and pitched well down is a passable roar of falling material, and it costs
     * nothing to replace: when there is a sound of our own, this is one identifier and one pitch.
     */
    private fun beHeard(level: ServerLevel, behaviour: ColumnBehaviour) {
        if (behaviour.betweenSounds <= NONE || age % behaviour.betweenSounds != 0) return
        val standing = halfWidth.toDouble()
        if (standing <= NOTHING) return
        level.playSound(
            null,
            x + (random.nextDouble() - MIDDLE) * standing,
            y + random.nextDouble() * HEARD_UP_TO,
            z + (random.nextDouble() - MIDDLE) * standing,
            SoundEvents.FIRE_AMBIENT,
            SoundSource.WEATHER,
            (standing / behaviour.widestHalfWidth).toFloat() * ROAR,
            DEEP,
        )
    }

    /**
     * The sand it leaves, this tick.
     *
     * **Every position under the footprint is offered one block**, at a chance derived from how wide the
     * column stands and how fast it walks ([ColumnBehaviour.depositChanceFor]) — so what a pack writes is
     * how deep a pass should leave the ground, and the rate falls out of the geometry. There is no
     * accumulator and nothing remembers how much has fallen here: the sand is the state (§5.4).
     *
     * **The trail is ragged because the edge is a ring rather than a line.** Positions outside the
     * footprint are offered the same block at a chance that falls off with every block out, which is what
     * makes the trail spill and wander at its edges instead of being a swept rectangle.
     *
     * The square is the column's own, so it turns with the heading: a position is inside when neither of
     * its distances **along** and **across** the heading exceeds the half-width, which is one dot product
     * each and no trigonometry per position.
     */
    private fun bury(level: ServerLevel, behaviour: ColumnBehaviour) {
        val standing = halfWidth.toDouble()
        if (standing <= NOTHING) return
        val heading = yRot.toDouble() * Mth.DEG_TO_RAD
        val forwardX = -sin(heading)
        val forwardZ = cos(heading)
        // The corners of a turned square reach half as far again as its edges, so the box swept has to
        // allow for that on top of how far the spill can carry.
        val reach = ceil(behaviour.spillReachAt(standing) * ROOT_TWO).toInt()
        val onIt = behaviour.depositChanceFor(speed, fullHalfWidth, depth)
        // Asked once for the whole sweep rather than per position: it is the same answer either way and
        // the sweep is hundreds of positions wide.
        val watched = level.getNearestPlayer(this, behaviour.dramaReach) != null
        if (watched) hangDust(level, behaviour, standing)
        for (eastward in -reach..reach) {
            for (southward in -reach..reach) {
                val offsetX = blockX + eastward + MIDDLE - x
                val offsetZ = blockZ + southward + MIDDLE - z
                val along = abs(forwardX * offsetX + forwardZ * offsetZ)
                val across = abs(forwardZ * offsetX - forwardX * offsetZ)
                val chance = onIt * behaviour.spillFadeAt(standing, max(along, across) - standing)
                if (random.nextDouble() >= chance) continue
                val dramatically = watched && random.nextDouble() < behaviour.dramaShare
                pile(level, blockX + eastward, blockZ + southward, dramatically)
            }
        }
    }

    /**
     * Bends the course, occasionally and never far.
     *
     * The cap is the brief's thirty degrees, but the thing that makes it read as *mostly straight* is
     * [SandfallBehaviour.turnEvery] rather than the cap: a turn is a rare event, and its size is drawn so
     * that small ones are much likelier than large.
     */
    private fun steer(behaviour: ColumnBehaviour) {
        if (behaviour.turnEvery <= 0 || age % behaviour.turnEvery != 0) return
        yRot = Mth.wrapDegrees(yRot + behaviour.turnedBy(random).toFloat())
    }

    /**
     * One step along the heading, riding whatever ground is under it.
     *
     * **The ground is only asked of a chunk that is already there.** `LevelReader.getHeight` goes through
     * `getChunk(…, FULL, true)`, which generates one on the calling thread — so a column that wandered a
     * block ahead of the loaded world would generate terrain from the tick loop. Where there is no chunk
     * yet the column keeps the height it had, which is right: it is about to be discarded for want of
     * anybody near it anyway.
     */
    private fun advance(level: ServerLevel) {
        val heading = yRot.toDouble() * Mth.DEG_TO_RAD
        val nextX = x - sin(heading) * speed
        val nextZ = z + cos(heading) * speed
        setPos(nextX, groundAt(level, nextX, nextZ) ?: y, nextZ)
    }

    private fun groundAt(level: ServerLevel, atX: Double, atZ: Double): Double? {
        val blockX = Mth.floor(atX)
        val blockZ = Mth.floor(atZ)
        level.chunkSource.getChunkNow(blockX shr CHUNK_BITS, blockZ shr CHUNK_BITS) ?: return null
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ).toDouble()
    }

    /**
     * One block of sand, on top of whatever is at that column — or on the lowest place beside it.
     *
     * **Sand has an angle of repose, and without one it builds spires** (Jonah, 2026-08-31, seen). Every
     * position accumulates on its own and vanilla's sand only ever falls straight down, so neighbours drift
     * two and three blocks apart over a pass and the trail reads as stone pillars rather than a drift. A
     * block that finds a markedly lower place beside it goes there instead, which fills the hollows first
     * and lets a pile spread rather than climb.
     *
     * **Only when the drop is [SLIDES_WHEN_LOWER_BY] or more**, so a step of one is left alone: sand really
     * does hold a small step, and levelling every difference would give a flat table where the brief asks
     * for something messy and irregular.
     *
     * **[Sampling.skyward] is the shared sky primitive**, and the trap it carries is the one an inferno
     * already paid for: `MOTION_BLOCKING` does not count a torch, a bush or a sapling, so where one stands
     * the heightmap points *at* it rather than above it. That is exactly the block a falling sand block
     * breaks — which is how a buried base goes dark and starts spawning things in itself, the compounding
     * hazard §5.2.2 asks for, at the cost of no rule of its own.
     *
     * **A torch drops and a grass tuft does not**, which is not vanilla's rule and is a deliberate
     * departure from it. `FallingBlockEntity` drops whatever it lands on; a column crossing a plains biome
     * would break a tuft of grass at nearly every position it passed, and thousands of item entities over
     * a life is a cost paid for something nobody would collect. What a player would miss is a torch, and a
     * torch is exactly what `canBeReplaced` separates out.
     */
    private fun pile(level: ServerLevel, atX: Int, atZ: Int, dramatically: Boolean) {
        val top = settledAt(level, atX, atZ) ?: return
        if (top.y >= level.maxY) return
        // **Near somebody, some of it falls rather than appearing.** It is the same block landing in the
        // same column, so nothing about the trail changes — what changes is that you watch it arrive.
        // `FallingBlockEntity.fall` clears the place it starts from, which is already air up there.
        if (dramatically) {
            val from = top.atY((top.y + FALLS_FROM).coerceAtMost(level.maxY - 1))
            if (from.y > top.y && level.getBlockState(from).isAir) {
                FallingBlockEntity.fall(level, from, Blocks.SAND.defaultBlockState())
                return
            }
        }
        val standing = level.getBlockState(top)
        if (!standing.isAir && !standing.canBeReplaced()) level.destroyBlock(top, true)
        level.setBlockAndUpdate(top, Blocks.SAND.defaultBlockState())
    }

    /**
     * The dust hanging in the column, for somebody standing near enough to be in it.
     *
     * **One packet a tick, scattered by the deltas** rather than one call per mote: `sendParticles` treats
     * them as a spread when the count is above one, so a whole column's worth of dust costs a single
     * message. It is the near-field half of what the shader draws — the prism is four flat faces, and what
     * a player inside one needs is something genuinely moving past them.
     */
    private fun hangDust(level: ServerLevel, behaviour: ColumnBehaviour, standing: Double) {
        if (behaviour.dust <= NONE) return
        level.sendParticles(
            SAND_DUST,
            x,
            y + DUST_STANDS / 2.0,
            z,
            behaviour.dust,
            standing,
            DUST_STANDS / 2.0,
            standing,
            NOTHING,
        )
    }

    /**
     * Where a block aimed at this column actually comes to rest — here, or the lowest place beside it.
     *
     * The middle is preferred wherever it is as low as anything around it, so a trail stays where the
     * column put it and only a genuine hollow pulls sand sideways. Ties among the neighbours go to whichever
     * the walk reaches first, which is arbitrary and harmless: they are all the same height.
     */
    private fun settledAt(level: ServerLevel, atX: Int, atZ: Int): BlockPos? {
        val here = topOf(level, atX, atZ) ?: return null
        var settled = here
        for (eastward in -BESIDE..BESIDE) {
            for (southward in -BESIDE..BESIDE) {
                val beside = topOf(level, atX + eastward, atZ + southward) ?: continue
                if (beside.y < settled.y) settled = beside
            }
        }
        return if (here.y - settled.y >= SLIDES_WHEN_LOWER_BY) settled else here
    }

    /** The first empty place above that column, or null where there is no chunk to ask. */
    private fun topOf(level: ServerLevel, atX: Int, atZ: Int): BlockPos? {
        val column = BlockPos(atX, level.minY, atZ)
        if (!level.hasChunkAt(column)) return null
        return Sampling.skyward(level, column)
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        age = input.getIntOr(AGE_KEY, 0)
        sinceSeen = input.getIntOr(SINCE_SEEN_KEY, 0)
        lifetime = input.getIntOr(LIFETIME_KEY, 0)
        speed = input.getDoubleOr(SPEED_KEY, 0.0)
        fullHalfWidth = input.getDoubleOr(FULL_HALF_WIDTH_KEY, 0.0)
        depth = input.getDoubleOr(DEPTH_KEY, 0.0)
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        output.putInt(AGE_KEY, age)
        output.putInt(SINCE_SEEN_KEY, sinceSeen)
        output.putInt(LIFETIME_KEY, lifetime)
        output.putDouble(SPEED_KEY, speed)
        output.putDouble(FULL_HALF_WIDTH_KEY, fullHalfWidth)
        output.putDouble(DEPTH_KEY, depth)
    }

    override fun shouldBeSaved(): Boolean = true

    /** Nothing to take hold of — it is weather, not a thing standing in the world. */
    override fun isPickable(): Boolean = false

    override fun hurtServer(level: ServerLevel, source: DamageSource, damage: Float): Boolean = false

    companion object {
        private val HALF_WIDTH: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(SandColumn::class.java, EntityDataSerializers.FLOAT)

        private val POUR: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(SandColumn::class.java, EntityDataSerializers.FLOAT)

        /** Half a block, so a position is measured from its middle rather than its corner. */
        private const val MIDDLE = 0.5

        /** The diagonal of a unit square: how much further a turned square's corners reach than its edges. */
        private const val ROOT_TWO = 1.4142135623730951

        /** How much lower a neighbour must be before sand slides onto it rather than piling here. */
        private const val SLIDES_WHEN_LOWER_BY = 2

        /** How far a sliding block looks, in blocks — the eight around it and no further. */
        private const val BESIDE = 1

        /** How far above where it will land a dramatic block starts, in blocks. */
        private const val FALLS_FROM = 22

        /** How tall the cloud of dust is, in blocks — the near field, not the whole column. */
        private const val DUST_STANDS = 40.0

        /** Sand, as the dust that comes off it. */
        private val SAND_DUST = BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.SAND.defaultBlockState())

        /** How much of a column, measured in from its edge, is see-through shell rather than solid middle. */
        private const val SHELL_BLOCKS = 2.0f

        /** What is left solid when a column is too narrow to spare two blocks — the ends of a life. */
        private const val LEAST_CORE_SHARE = 0.3f

        /** How far up a column its sound is thrown from, in blocks — the part a player is standing in. */
        private const val HEARD_UP_TO = 24.0
        private const val ROAR = 5.0f

        /** Well down: a crackle at this pitch is a rush rather than a fire. */
        private const val DEEP = 0.45f

        private const val NONE = 0
        private const val NOTHING = 0.0

        private const val AGE_KEY = "age"
        private const val SINCE_SEEN_KEY = "since_seen"
        private const val FRESHLY_SEEN = 0
        private const val LIFETIME_KEY = "lifetime"
        private const val SPEED_KEY = "speed"
        private const val FULL_HALF_WIDTH_KEY = "full_half_width"
        private const val DEPTH_KEY = "depth"
        private const val CHUNK_BITS = 4

        /**
         * Stands one up at [atX], [atZ], headed [headingDegrees] and lasting [lifetime] ticks — or
         * **null where nothing there would tick yet**.
         *
         * **The test is entity-ticking rather than merely loaded** (found 2026-08-31, driving a server). A
         * chunk can be loaded enough to answer `getChunkNow` and still be outside the simulation distance,
         * and a column raised in one stands frozen at nothing wide until somebody walks close enough to
         * start it — it does not age, so it does not open, so there is nothing to see and nothing to walk
         * away from. `isPositionEntityTicking` is the question actually being asked, and it also answers
         * the one this used to: `getHeight` goes through `getChunk(…, FULL, true)` and would generate
         * terrain on the tick thread.
         *
         * The height is taken from the ground rather than passed in, because the only place a column's
         * bottom belongs is on the ground under it.
         */
        fun raise(
            level: ServerLevel,
            atX: Double,
            atZ: Double,
            headingDegrees: Float,
            speed: Double,
            lifetime: Int,
            fullHalfWidth: Double,
            depth: Double,
        ): SandColumn? {
            val blockX = Mth.floor(atX)
            val blockZ = Mth.floor(atZ)
            if (!level.isPositionEntityTicking(BlockPos(blockX, level.minY, blockZ))) return null
            val column = AgeContent.SAND_COLUMN.create(level, EntitySpawnReason.EVENT) ?: return null
            val ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ)
            column.setPos(atX, ground.toDouble(), atZ)
            column.yRot = Mth.wrapDegrees(headingDegrees)
            column.speed = speed
            column.lifetime = lifetime
            column.fullHalfWidth = fullHalfWidth
            column.depth = depth
            column.pour = SandfallBehaviour.of(level.server).column.poursAt(depth).toFloat() /
                ColumnBehaviour.FASTEST_POUR
            level.addFreshEntity(column)
            return column
        }
    }
}
