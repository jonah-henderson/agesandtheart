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
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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

    /** How long it goes on dropping once it starts, in ticks. */
    var falling: Int = SHORTEST_FALL

    /** How fast the bodies come in, and so how hard they land. */
    var fury: Double = ORDINARY_FURY

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(BODIES, FEW)
    }

    /**
     * Whether the ground under this is lit right now — the last of the warning and the whole of the fall.
     *
     * Asked by the client as well as the server, and it is a pure function of the entity's own clock, so
     * neither has to be told: [tickCount] is ticked on both sides.
     */
    fun lighting(): Boolean = tickCount >= APPROACHING - LIGHTING_UP

    /** How far through its approach this is, nought to one — what a sky animation is drawn from. */
    fun approachedBy(partial: Float): Float =
        ((tickCount + partial) / APPROACHING).coerceIn(NONE_OF_IT, ALL_OF_IT)

    override fun tick() {
        super.tick()
        val level = level()
        if (level !is ServerLevel) return
        if (tickCount > APPROACHING + falling) {
            discard()
            return
        }
        // **Lit from the last of the warning right through the fall** (Jonah), and re-laid as it goes:
        // the bodies blow the markers up along with everything else, and a light that goes out halfway
        // through the pounding is worse than one that was never there.
        if (tickCount >= APPROACHING - LIGHTING_UP && tickCount % RELIGHTING == NOW) lightTheGround(level)
        if (tickCount <= APPROACHING) return
        dropSome(level)
    }

    /**
     * Light blocks over the ground this is about to hit, so a player can see where not to stand.
     *
     * **Placed on the surface rather than in the air**, so what is lit is the ground itself: an invisible
     * lamp hanging at the storm's own height would light nothing anybody is standing on.
     */
    private fun lightTheGround(level: ServerLevel) {
        forEachMarker { at ->
            val ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, at)
            if (!level.getBlockState(ground).isAir) return@forEachMarker
            level.setBlockAndUpdate(ground, Blocks.LIGHT.defaultBlockState())
        }
    }

    /**
     * And take them away when the storm is over.
     *
     * **On removal, whatever the reason** — a storm that runs its course puts its own lights out, and one
     * that goes with an unloading chunk would otherwise leave them burning for ever. A light block is
     * invisible and replaceable, so a stray one is untidy rather than harmful — but a hazard marker that
     * outlives the hazard is a lie, which is worse.
     */
    private fun putTheLightsOut(level: ServerLevel) {
        forEachMarker { at ->
            val ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, at).below()
            for (near in listOf(ground, ground.above(), ground.above(TWO))) {
                if (level.getBlockState(near).`is`(Blocks.LIGHT)) level.removeBlock(near, false)
            }
        }
    }

    /** The ring of places a marker goes — the edge of what is about to be hit, and its middle. */
    private fun forEachMarker(visit: (BlockPos) -> Unit) {
        visit(BlockPos.containing(x, y, z))
        for (mark in 0..<MARKERS) {
            val around = FULL_TURN * mark / MARKERS
            visit(BlockPos.containing(x + cos(around) * REACH, y, z + sin(around) * REACH))
        }
    }

    override fun remove(reason: RemovalReason) {
        val level = level()
        if (level is ServerLevel) putTheLightsOut(level)
        super.remove(reason)
    }

    /**
     * The bodies for this tick, spread over the ground under the storm.
     *
     * Spread by **drawing a bearing and a distance** rather than a square, so the fall is a disc rather
     * than a box and the middle of it is hit hardest — which is what a shower looks like and what makes
     * walking out of one a decision about direction rather than about corners.
     */
    private fun dropSome(level: ServerLevel) {
        val random = level.random
        repeat(bodies) {
            if (random.nextInt(SPREAD_OVER) != NOW) return@repeat
            val bearing = random.nextDouble() * FULL_TURN
            val away = random.nextDouble() * REACH
            val body = Meteor(AgeContent.METEOR, level)
            body.blast = (TWICE_TNT + (AT_FULL_FURY - TWICE_TNT) * fury).toFloat()
            body.setPos(x + cos(bearing) * away, y, z + sin(bearing) * away)
            // Nearly straight down, leaning a little so a streak reads as an arrival rather than a drop.
            val speed = SLOWEST_ARRIVAL + (FASTEST_ARRIVAL - SLOWEST_ARRIVAL) * fury
            val lean = (random.nextDouble() - HALF) * LEAN
            body.setDeltaMovement(lean, -speed, (random.nextDouble() - HALF) * LEAN)
            level.addFreshEntity(body)
        }
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

        /**
         * How long it hangs in the sky before anything falls, in ticks — **thirty seconds** (Jonah).
         *
         * Long enough to see it, read where it is going and *do something about it*: the counterplay is
         * spatial, so the warning has to leave time to walk somewhere else, and half a minute is what a
         * walk asked for over the four and a half seconds this first had.
         */
        const val APPROACHING = 600

        /**
         * How long before it falls the ground under it is lit, in ticks — the last ten seconds.
         *
         * **A cheap trick, and deliberately in the player's favour** (Jonah): the sky says a storm is
         * coming and roughly where, and this says *exactly* where not to stand. Light rather than a marker
         * because it costs nothing to understand — ground that is suddenly lit at night is a thing
         * anybody reads without being taught it.
         */
        const val LIGHTING_UP = 200

        /** How wide the pounding is. Small on purpose — this is a shower, not weather. */
        const val REACH = 28.0

        /** Ten to fifteen seconds of it (Jonah), which the storm draws between. */
        const val SHORTEST_FALL = 200
        const val LONGEST_FALL = 300

        private const val MARKERS = 12

        /** How often the markers are laid again, since the bodies keep blowing them up. */
        private const val RELIGHTING = 40
        private const val TWO = 2

        private const val SPREAD_OVER = 6
        private const val NOW = 0
        private const val FEW = 4

        /**
         * **Twice TNT at rest, and half as much again at full fury** (Jonah, 2026-09-06).
         *
         * The pack has been pricing blasts against a creeper at 3 and TNT at 4; this is deliberately past
         * the top of that scale, because a body arriving from outside the world should not land like
         * something a player could have crafted. It is the hardest thing the modpack throws.
         */
        private const val TWICE_TNT = 8.0
        private const val AT_FULL_FURY = 14.0

        private const val SLOWEST_ARRIVAL = 2.4
        private const val FASTEST_ARRIVAL = 4.2
        private const val LEAN = 0.5

        private const val FALLING_KEY = "falling"
        private const val FURY_KEY = "fury"
        private const val BODIES_KEY = "bodies"

        private const val ORDINARY_FURY = 0.0
        private const val HALF = 0.5
        private const val FULL_TURN = 2.0 * PI
        private const val NONE_OF_IT = 0.0f
        private const val ALL_OF_IT = 1.0f

        val ID: Identifier = "meteor_storm".location()

        /** Stand one up at [where], to approach and then fall. */
        fun gatherAt(level: ServerLevel, where: Vec3, bodies: Int, falling: Int, fury: Double) {
            val storm = MeteorStorm(AgeContent.METEOR_STORM, level)
            storm.setPos(where.x, where.y, where.z)
            storm.bodies = bodies
            storm.falling = falling
            storm.fury = fury
            level.addFreshEntity(storm)
        }
    }
}
