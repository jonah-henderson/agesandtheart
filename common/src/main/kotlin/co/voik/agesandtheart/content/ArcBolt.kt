package co.voik.agesandtheart.content

import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import org.joml.Vector3fc

/**
 * The arc a charged machine throws when it bites — **a thing that exists only to be looked at**, for about
 * a sixth of a second (design §7.1.2).
 *
 * **Vanilla's own bolt could not be used, and the reason is the sound.** `LightningBolt.setVisualOnly`
 * exists and does what it says about damage and fire, but `tick` plays `LIGHTNING_BOLT_THUNDER` at volume
 * ten thousand on the client regardless of the flag — a turret biting twice a second would be a thunderclap
 * twice a second. It is also a hundred and twenty-eight blocks tall and anchored to the ground.
 *
 * **Vanilla's renderer, though, is reused exactly** (`ArcBoltRenderer`): its `submit` is public and its
 * whole render state is a seed, so the shape is drawn by vanilla's own code with nothing but a transformed
 * `PoseStack` in front of it. Nothing about the bolt's *look* is ours.
 *
 * **The seed is not sent.** Vanilla does not send its own either — a bolt's shape is a client-side detail —
 * and the renderer derives one from the entity id, which is stable for the life of the bolt and different
 * between bolts. The one thing that has to travel is where the far end lands.
 */
class ArcBolt(type: EntityType<out ArcBolt>, level: Level) : Entity(type, level) {

    /** Where the arc lands, **relative to where it starts**, so it survives the position being smoothed. */
    var reachesTo: Vec3
        get() = entityData.get(REACHES_TO).let { Vec3(it.x().toDouble(), it.y().toDouble(), it.z().toDouble()) }
        set(value) = entityData.set(REACHES_TO, Vector3f(value.x.toFloat(), value.y.toFloat(), value.z.toFloat()))

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(REACHES_TO, Vector3f())
    }

    override fun tick() {
        // No movement, no gravity and no collision: it is drawn where it was put and then it is gone.
        if (!level().isClientSide && tickCount > LIVES_FOR) discard()
    }

    /** Nothing to hit and nothing to keep — a bolt that survived a save would be a bolt frozen in the air. */
    override fun isPickable(): Boolean = false

    /**
     * Seen from as far as vanilla's own bolt is, rather than as far as a thing this size would be — which
     * was a few blocks from the rod, so a bite landing any further off went undrawn.
     */
    override fun shouldRenderAtSqrDistance(distance: Double): Boolean {
        val seenWithin = SEEN_WITHIN * getViewScale()
        return distance < seenWithin * seenWithin
    }

    /** And nothing to hurt: it is light, not an object. */
    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean = false

    override fun readAdditionalSaveData(input: ValueInput) = Unit

    override fun addAdditionalSaveData(output: ValueOutput) = Unit

    companion object {
        private val REACHES_TO: EntityDataAccessor<Vector3fc> =
            SynchedEntityData.defineId(ArcBolt::class.java, EntityDataSerializers.VECTOR3)

        /** Vanilla's own bolt lasts about this long, and a bite is over well inside it. */
        private const val LIVES_FOR = 3

        /** Vanilla's lightning's reach, before the player's entity-distance setting scales it. */
        private const val SEEN_WITHIN = 64.0

        /**
         * Throw one from [from] to [to], or nothing where the two are the same place.
         *
         * The entity sits at the rod and carries the reach, rather than sitting between the two: what a
         * player's eye follows is the thing the arc comes *out of*.
         */
        fun thrown(level: Level, from: Vec3, to: Vec3) {
            val reach = to.subtract(from)
            if (reach.lengthSqr() < NOTHING) return
            val bolt = AgeContent.ARC_BOLT.create(level, EntitySpawnReason.TRIGGERED) ?: return
            bolt.snapTo(from.x, from.y, from.z)
            bolt.reachesTo = reach
            level.addFreshEntity(bolt)
        }

        private const val NOTHING = 1.0e-6
    }
}
