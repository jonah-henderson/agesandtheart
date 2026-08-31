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
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import kotlin.math.cos
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

    /** How many ticks it has stood. */
    var age: Int = 0
        private set

    /** How many it will stand for. */
    var lifetime: Int = 0
        private set

    /** How fast it walks, in blocks per tick — rolled once at spawn, and never again. */
    var speed: Double = 0.0
        private set

    /** How many ticks since anybody was near enough to see it. */
    private var sinceSeen: Int = 0

    init {
        noPhysics = true
        isNoGravity = true
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(HALF_WIDTH, 0.0f)
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
        halfWidth = behaviour.column.halfWidthAt(age, lifetime).toFloat()
        steer(behaviour.column)
        advance(level)
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

    override fun readAdditionalSaveData(input: ValueInput) {
        age = input.getIntOr(AGE_KEY, 0)
        sinceSeen = input.getIntOr(SINCE_SEEN_KEY, 0)
        lifetime = input.getIntOr(LIFETIME_KEY, 0)
        speed = input.getDoubleOr(SPEED_KEY, 0.0)
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        output.putInt(AGE_KEY, age)
        output.putInt(SINCE_SEEN_KEY, sinceSeen)
        output.putInt(LIFETIME_KEY, lifetime)
        output.putDouble(SPEED_KEY, speed)
    }

    override fun shouldBeSaved(): Boolean = true

    /** Nothing to take hold of — it is weather, not a thing standing in the world. */
    override fun isPickable(): Boolean = false

    override fun hurtServer(level: ServerLevel, source: DamageSource, damage: Float): Boolean = false

    companion object {
        private val HALF_WIDTH: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(SandColumn::class.java, EntityDataSerializers.FLOAT)

        private const val AGE_KEY = "age"
        private const val SINCE_SEEN_KEY = "since_seen"
        private const val FRESHLY_SEEN = 0
        private const val LIFETIME_KEY = "lifetime"
        private const val SPEED_KEY = "speed"
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
            level.addFreshEntity(column)
            return column
        }
    }
}
