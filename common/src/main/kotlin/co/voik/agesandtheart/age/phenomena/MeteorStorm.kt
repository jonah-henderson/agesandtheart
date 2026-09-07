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

    /**
     * Where the markers go — **across the whole disc, not round its edge**.
     *
     * A ring of a dozen lights over ground fifty-six blocks across lit almost nothing, and a walk could
     * not make it out at all (Jonah, 2026-09-06). A lattice inside the disc is what actually lights the
     * area, which is the entire point of the thing being in the player's favour.
     */
    private fun forEachMarker(visit: (BlockPos) -> Unit) {
        val step = (REACH * ACROSS_THE_DISC / MARKERS_ACROSS).toInt().coerceAtLeast(AT_LEAST_ONE)
        var awayX = -REACH.toInt()
        while (awayX <= REACH) {
            var awayZ = -REACH.toInt()
            while (awayZ <= REACH) {
                if (awayX * awayX + awayZ * awayZ <= REACH * REACH) {
                    visit(BlockPos.containing(x + awayX, y, z + awayZ))
                }
                awayZ += step
            }
            awayX += step
        }
    }

    override fun remove(reason: RemovalReason) {
        val level = level()
        if (level is ServerLevel) putTheLightsOut(level)
        super.remove(reason)
    }

    /** What body [number] of this storm does — the one answer a client works out for itself as well. */
    fun flightOf(number: Int): MeteorFlight =
        MeteorFlight.of(uuid.leastSignificantBits, number, bodies, falling, APPROACHING)

    /**
     * The bodies whose moment is this tick.
     *
     * **Read off the same list the sky is drawing**, rather than rolled here: a body announced in the sky
     * and a body dropped on the ground have to be the same body, and the only way to promise that without
     * sending anything is for both sides to ask the same pure function.
     */
    private fun dropSome(level: ServerLevel) {
        for (number in 0..<bodies) {
            if (flightOf(number).fallsAt != tickCount) continue
            throwOne(level, flightOf(number))
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
        val (offsetX, offsetY, offsetZ) = flight.entryOffset(ENTRY_RANGE)
        val body = Meteor(AgeContent.METEOR, level)
        body.blast = (AT_REST + (AT_FULL_FURY - AT_REST) * fury).toFloat()
        body.setPos(ground.x + offsetX, ground.y + offsetY, ground.z + offsetZ)
        val speed = SLOWEST_ARRIVAL + (FASTEST_ARRIVAL - SLOWEST_ARRIVAL) * fury
        val toTheGround = ENTRY_RANGE
        body.setDeltaMovement(
            -offsetX / toTheGround * speed,
            -offsetY / toTheGround * speed,
            -offsetZ / toTheGround * speed,
        )
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

        /** How many markers span the disc, so the lattice below is about six blocks apart. */
        private const val MARKERS_ACROSS = 10
        private const val ACROSS_THE_DISC = 2.0
        private const val AT_LEAST_ONE = 1

        /** How often the markers are laid again, since the bodies keep blowing them up. */
        private const val RELIGHTING = 40
        private const val TWO = 2

        private const val NOW = 0
        private const val FEW = 4

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
        private const val ENTRY_RANGE = 150.0

        /** Quick, and a walk asked for quicker still: a body should streak rather than sail. */
        private const val SLOWEST_ARRIVAL = 3.6
        private const val FASTEST_ARRIVAL = 6.0

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
