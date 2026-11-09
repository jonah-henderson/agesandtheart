package co.voik.agesandtheart.age.phenomena

import co.voik.agesandtheart.age.aspect.Atmosphere
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.generation.Ages
import net.minecraft.core.BlockPos
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.FallingBlockEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * One tornado (`notes/wind-plan.md` §4), standing at the foot of its funnel and wandering along the Age's wind.
 *
 * Its overcast brews over [BREWS_OVER], it reaches down from it over [TOUCHDOWN_TICKS], and it travels free: **only
 * the blocks it throws and the ground it climbs spend its strength** ([spend]), and over the last [ROPES_OUT_FROM] of
 * it the funnel thins and draws back up into the overcast, which clears once it is gone ([cleared]), so no
 * clock ends it and it lasts as far as the ground it tears up lets it. **Its strength is its width**: a strong one is a wide one, and how far it has
 * roped out says how near its end it is. Its foot rides the ground up and over hills, and it breaks up at once where
 * the ground reaches its overcast.
 *
 * An entity as the sand column is, for the same reasons: where it goes depends on nothing the world records, it stops
 * while nobody is near, and it is lost harmlessly. What the renderer needs is synced; what decides it is saved.
 */
class Tornado(type: EntityType<out Tornado>, level: Level) : Entity(type, level) {

    /** Half the funnel's side at the ground, in blocks, once it is down and before it ropes out. */
    var footHalfWidth: Float
        get() = entityData.get(FOOT_HALF_WIDTH)
        private set(value) = entityData.set(FOOT_HALF_WIDTH, value)

    /** How far its overcast has brewed: nought as it begins, one before the funnel starts down. */
    var brewed: Float
        get() = entityData.get(BREWED)
        private set(value) = entityData.set(BREWED, value)

    /** How far the funnel has reached down from the cloud: nought until the overcast has brewed, one on the ground. */
    var descended: Float
        get() = entityData.get(DESCENDED)
        private set(value) = entityData.set(DESCENDED, value)

    /**
     * How far it has roped out: nought while it has strength to spare, one as the funnel is gone back up into the
     * overcast. It draws back as it thins, so the funnel leaves the ground as soon as it starts.
     */
    var roped: Float
        get() = entityData.get(ROPED)
        private set(value) = entityData.set(ROPED, value)

    /** How far its overcast has cleared once the funnel is gone: nought until then, one as it breaks up. */
    var cleared: Float
        get() = entityData.get(CLEARED)
        private set(value) = entityData.set(CLEARED, value)

    /** Its own phase, by its id, which the client knows too: where its sway starts. */
    val phase: Float
        get() = ((id * PHASE_STEP) and PHASE_MASK).toFloat() / PHASE_WHOLE

    /** How many ticks it has stood. */
    var age: Int = 0
        private set

    /** How much strength it has left, in what a block of dirt costs to throw. */
    var strength: Double = 0.0
        private set

    /** How much it was born with, which its roping out is measured against. */
    var startingStrength: Double = 0.0
        private set

    /** How fast it travels, in blocks per tick. */
    var speed: Double = 0.0
        private set

    /** The way the Age's wind blows, as a yaw; it wanders about this. */
    private var windYaw: Float = 0.0f

    private var sinceSeen: Int = 0

    /** What its core has taken up, and how — never saved: a reload drops whatever was in the air. */
    private val lifted = HashMap<Entity, TornadoWinds.Lift>()

    /** The blocks it has thrown that are still in the air. */
    private val thrown = HashSet<FallingBlockEntity>()

    /** How many blocks it has yet to tear up, and places yet to strip, a fraction of each carried from tick to tick. */
    private var tearing = 0.0
    private var stripping = 0.0

    /** How far it has travelled, the ground under its foot along the last stretch of it, and what that climb has cost. */
    private var travelled = 0.0
    private val groundBehind = ArrayDeque<Pair<Double, Double>>()
    private var climbPaid = 0.0

    init {
        noPhysics = true
        isNoGravity = true
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {
        builder.define(FOOT_HALF_WIDTH, 0.0f)
        builder.define(BREWED, 0.0f)
        builder.define(DESCENDED, 0.0f)
        builder.define(ROPED, 0.0f)
        builder.define(CLEARED, 0.0f)
    }

    override fun tick() {
        super.tick()
        val level = level()
        if (level !is ServerLevel) return
        age += 1
        if (age == 1) thunderAsTheOvercastGathers(level)
        sinceSeen = if (level.getNearestPlayer(this, FORGOTTEN_AT) != null) 0 else sinceSeen + 1
        val reachesTheCloud = y >= BREAKS_UP_AT
        if (cleared >= WHOLLY || reachesTheCloud || sinceSeen > FORGOTTEN_AFTER) {
            discard()
            return
        }
        brewed = (age.toFloat() / BREWS_OVER).coerceAtMost(1.0f)
        descended = Mth.smoothstep(((age - BREWS_OVER).toFloat() / TOUCHDOWN_TICKS).coerceIn(0.0f, 1.0f))
        // Eased, so a mouthful of stone taken at once thins the funnel over a second or two rather than in a frame.
        val ropedBy = (1.0 - strength / (startingStrength * ROPES_OUT_FROM)).coerceIn(0.0, 1.0).toFloat()
        // Never faster than RETREATS_OVER, so one that is spent at a stroke still draws back over its fifteen seconds.
        roped += ((ropedBy - roped) * ROPING_EASES).coerceAtMost(WHOLLY / RETREATS_OVER.toFloat())
        // Off the ground it takes nothing, so once it starts back up it spends itself, and is gone in RETREATS_OVER.
        if (roped > NOTHING_YET) spend(startingStrength * ROPES_OUT_FROM / RETREATS_OVER)
        val isGone = strength <= SPENT && roped >= BROKEN_UP
        if (isGone) cleared = (cleared + WHOLLY / CLEARS_OVER).coerceAtMost(WHOLLY)
        wander()
        val ground = advance(level)
        TornadoWinds.blowOn(level, this, lifted)
        val vortex = TornadoWinds.of(this) ?: return
        climb(ground, vortex)
        tear(level, vortex)
    }

    /**
     * Spends what climbing has cost since last tick: the rise of the ground across the last stretch it travelled, as
     * long as its foot is wide, priced as [TornadoGround.climbCostOf] and paid as it grows, so a cliff in three steps
     * costs as one.
     */
    private fun climb(ground: Double, vortex: TornadoWinds.Vortex) {
        travelled += speed
        groundBehind.addLast(travelled to ground)
        val stretch = vortex.core * BOTH_SIDES
        while (groundBehind.size > 1 && travelled - groundBehind.first().first > stretch) groundBehind.removeFirst()
        val lowest = groundBehind.minOf { it.second }
        val climbCost = TornadoGround.climbCostOf(ground - lowest)
        spend((climbCost - climbPaid).coerceAtLeast(0.0))
        climbPaid = climbCost
    }

    /**
     * Tears up as many blocks as it sweeps new ground for, down to a trench under the untouched ground just ahead of
     * its foot ([TornadoGround.tear]), and spends what they cost; nothing once it has started back up into the overcast.
     */
    private fun tear(level: ServerLevel, vortex: TornadoWinds.Vortex) {
        if (roped > NOTHING_YET) return
        tearing += vortex.core * BOTH_SIDES * speed * LAYERS_TORN * vortex.might
        stripping += (vortex.spiralReach * vortex.spiralReach * STRIPPED_PER_COLUMN * vortex.might)
        val stripped = stripping.toInt()
        stripping -= stripped
        if (stripped > 0) spend(TornadoGround.strip(level, vortex, stripped))
        val howMany = tearing.toInt()
        tearing -= howMany
        if (howMany <= 0) return
        val heading = yRot.toDouble() * Mth.DEG_TO_RAD
        val ahead = vortex.core + AHEAD_OF_THE_FOOT
        val aheadX = Mth.floor(x - sin(heading) * ahead)
        val aheadZ = Mth.floor(z + cos(heading) * ahead)
        val hasGroundAhead = level.chunkSource.getChunkNow(aheadX shr CHUNK_BITS, aheadZ shr CHUNK_BITS) != null
        val untouched = if (hasGroundAhead) TornadoGround.groundAt(level, aheadX, aheadZ) else Mth.floor(y)
        spend(TornadoGround.tear(level, vortex, untouched, howMany, thrown))
    }

    /** A thunderclap from the cloud over it as its overcast first gathers, heard across the land as a warning. */
    private fun thunderAsTheOvercastGathers(level: ServerLevel) {
        val pitch = THUNDER_PITCH + random.nextFloat() * THUNDER_PITCH_SPREAD
        level.playSound(null, x, OVERCAST_FLOOR, z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, THUNDER_HEARD_ACROSS, pitch)
    }

    /** Spends [cost] of its strength, as throwing a block does. */
    fun spend(cost: Double) {
        strength = (strength - cost).coerceAtLeast(SPENT)
    }

    /** Turns now and then, about the wind's own way and never far from it. */
    private fun wander() {
        if (age % TURN_EVERY != 0) return
        val off = (random.nextDouble() - random.nextDouble()) * WANDER_MOST_DEGREES
        yRot = Mth.wrapDegrees(windYaw + off.toFloat())
    }

    /**
     * One step along its heading, the foot easing up and over the ground under it where there is a chunk to ask, so a
     * cliff is climbed over a few ticks rather than leapt.
     */
    private fun advance(level: ServerLevel): Double {
        val heading = yRot.toDouble() * Mth.DEG_TO_RAD
        val nextX = x - sin(heading) * speed
        val nextZ = z + cos(heading) * speed
        val ground = groundUnderTheFoot(level, nextX, nextZ) ?: y
        setPos(nextX, y + (ground - y) * FOOT_FOLLOWS, nextZ)
        return ground
    }

    /**
     * The ground under the whole foot at [atX], [atZ]: its middle and a ring round it most of the way to its edge,
     * averaged, so a ravine narrower than the foot is a dip it crosses rather than a cliff it climbs out of. Null where
     * none of it is loaded.
     */
    private fun groundUnderTheFoot(level: ServerLevel, atX: Double, atZ: Double): Double? {
        val reach = footHalfWidthOf(footHalfWidth, roped) * FOOT_SAMPLED_TO
        var sum = 0.0
        var counted = 0
        for (sample in 0..FOOT_SAMPLES) {
            val angle = sample * Mth.TWO_PI / FOOT_SAMPLES
            val out = if (sample == 0) 0.0f else reach
            val blockX = Mth.floor(atX + cos(angle) * out)
            val blockZ = Mth.floor(atZ + sin(angle) * out)
            if (level.chunkSource.getChunkNow(blockX shr CHUNK_BITS, blockZ shr CHUNK_BITS) == null) continue
            sum += TornadoGround.groundAt(level, blockX, blockZ)
            counted += 1
        }
        return if (counted == 0) null else sum / counted
    }

    override fun readAdditionalSaveData(input: ValueInput) {
        age = input.getIntOr(AGE_KEY, 0)
        strength = input.getDoubleOr(STRENGTH_KEY, 0.0)
        startingStrength = input.getDoubleOr(STARTING_STRENGTH_KEY, 0.0)
        speed = input.getDoubleOr(SPEED_KEY, 0.0)
        windYaw = input.getFloatOr(WIND_YAW_KEY, 0.0f)
        sinceSeen = input.getIntOr(SINCE_SEEN_KEY, 0)
        footHalfWidth = input.getFloatOr(FOOT_HALF_WIDTH_KEY, 0.0f)
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        output.putInt(AGE_KEY, age)
        output.putDouble(STRENGTH_KEY, strength)
        output.putDouble(STARTING_STRENGTH_KEY, startingStrength)
        output.putDouble(SPEED_KEY, speed)
        output.putFloat(WIND_YAW_KEY, windYaw)
        output.putInt(SINCE_SEEN_KEY, sinceSeen)
        output.putFloat(FOOT_HALF_WIDTH_KEY, footHalfWidth)
    }

    override fun shouldBeSaved(): Boolean = true

    override fun isPickable(): Boolean = false

    /** Seen as far as it is tracked, rather than as far as vanilla draws something its one-block size. */
    override fun shouldRenderAtSqrDistance(distance: Double): Boolean = true

    override fun hurtServer(level: ServerLevel, source: DamageSource, damage: Float): Boolean = false

    companion object {
        private val FOOT_HALF_WIDTH: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(Tornado::class.java, EntityDataSerializers.FLOAT)
        private val BREWED: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(Tornado::class.java, EntityDataSerializers.FLOAT)
        private val DESCENDED: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(Tornado::class.java, EntityDataSerializers.FLOAT)
        private val ROPED: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(Tornado::class.java, EntityDataSerializers.FLOAT)
        private val CLEARED: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(Tornado::class.java, EntityDataSerializers.FLOAT)

        /** Four to fifteen blocks either side of the middle at the foot: eight to thirty across. */
        const val NARROWEST_FOOT = 4.0
        const val WIDEST_FOOT = 15.0

        /** Vanilla's cloud height, which every Age's tornado hangs from whatever its own clouds do. */
        const val CLOUD_Y = 192.0

        /** How far under the cloud the floor of its overcast hangs, and how thick the overcast is. */
        const val OVERCAST_BELOW = 10.0
        const val OVERCAST_THICKNESS = 6.0
        const val OVERCAST_FLOOR = CLOUD_Y - OVERCAST_BELOW

        /** Where ground under its foot meets the overcast, and it breaks up. */
        const val BREAKS_UP_AT = OVERCAST_FLOOR

        /** As loud as thunder is, which is how far it carries: some sixteen hundred blocks. A little low, as lightning's is. */
        private const val THUNDER_HEARD_ACROSS = 100.0f
        private const val THUNDER_PITCH = 0.7f
        private const val THUNDER_PITCH_SPREAD = 0.2f

        /** Fifteen seconds for the overcast to spread before the funnel starts down. */
        const val BREWS_OVER = 300

        /** Twenty-five seconds from the cloud to the ground. */
        const val TOUCHDOWN_TICKS = 500

        /**
         * The funnel's top flares to a little over twice its foot, forty to sixty across, and to this power of the way
         * up, so it keeps narrow for most of its length; roped out, a rope at the foot and a little wider at the cloud.
         * Its winds follow the flare, so its core is as wide as it is drawn at every height.
         */
        const val TOP_PER_FOOT = 2.2f
        const val NARROWEST_TOP = 20.0f
        const val WIDEST_TOP = 30.0f
        const val FLARE = 3.0f
        const val ROPE_FOOT = 1.2f
        const val ROPE_TOP = 6.0f

        /** Half the funnel's side at the ground, born [footHalfWidth] and [roped] this far out. */
        fun footHalfWidthOf(footHalfWidth: Float, roped: Float): Float = Mth.lerp(roped, footHalfWidth, ROPE_FOOT)

        /** Half the funnel's side at the cloud. */
        fun topHalfWidthOf(footHalfWidth: Float, roped: Float): Float =
            Mth.lerp(roped, (footHalfWidth * TOP_PER_FOOT).coerceIn(NARROWEST_TOP, WIDEST_TOP), ROPE_TOP)

        /**
         * How far the funnel's top leans ahead of its foot, as a share of its length, and a rope's far more; and how it
         * curves, the lean growing as this power of the way up. Its winds follow the lean, so what it takes up stays in it.
         */
        const val LEAN = 0.1f
        const val ROPE_LEAN = 0.15f
        const val LEAN_CURVE = 1.5f

        /**
         * It ropes out and draws back into the overcast over the last sixth of its strength, spending itself as it goes
         * so it is gone in no more than fifteen seconds; then its overcast clears over ten.
         */
        const val ROPES_OUT_FROM = 0.15
        private const val RETREATS_OVER = 300.0
        private const val CLEARS_OVER = 200.0f
        private const val NOTHING_YET = 0.001f
        private const val WHOLLY = 1.0f

        /**
         * How far the funnel's middle stands from straight over its foot, [up] of the way from the foot to the cloud of a
         * funnel [length] long: leaning ahead along [headingRadians], and swaying across it at [time] by the world's
         * clock, from its own [phase]. Its renderer, its winds and the crumbs it whirls all read this, so they agree.
         */
        fun middleOffset(up: Float, length: Float, headingRadians: Float, roped: Float, time: Double, phase: Float): Pair<Float, Float> {
            val forwardX = -sin(headingRadians)
            val forwardZ = cos(headingRadians)
            val down = 1.0f - up
            val ahead = (LEAN + ROPE_LEAN * roped) * length * up.pow(LEAN_CURVE)
            val swing = sin(time * SWAY_RATE + down * SWAY_WAVES + phase * Mth.TWO_PI).toFloat()
            val swaying = (SWAY + ROPE_SWAY * roped) * length * swing * down * up * BOTH_ENDS
            return (forwardX * ahead + forwardZ * swaying) to (forwardZ * ahead - forwardX * swaying)
        }

        /** How far it sways from side to side, as a share of its length, and how fast, by the world's clock. */
        private const val SWAY = 0.025f
        private const val ROPE_SWAY = 0.08f
        private const val SWAY_RATE = 0.03
        private const val SWAY_WAVES = 4.0f
        private const val BOTH_ENDS = 4.0f

        private const val PHASE_STEP = 2654435761L
        private const val PHASE_MASK = 0xFFFFL
        private const val PHASE_WHOLE = 65536.0f

        /**
         * The strength it is born with, the narrowest to the widest, each drawn from half again either side: set so the
         * narrowest carries about two hundred blocks over grassland (Jonah: twice the hundred first asked, for longevity),
         * cutting a trench eight columns wide, two to four blocks and what grew deep, at about a block apiece, and the
         * widest some four hundred.
         */
        const val NARROWEST_STRENGTH = 4300.0
        const val WIDEST_STRENGTH = 31400.0
        private const val STRENGTH_SPREAD = 0.5

        /** How much of the way to how far it should have roped out it goes each tick. */
        private const val ROPING_EASES = 0.03f
        /** Spent, its funnel is gone once it has roped out this far, and its overcast starts to clear. */
        private const val BROKEN_UP = 0.99f

        /**
         * How many blocks it tears up for each column it sweeps, enough for the trench, and where it reads the untouched
         * ground the trench is cut under: a block past the edge of its foot ahead.
         */
        private const val LAYERS_TORN = 3.5
        private const val AHEAD_OF_THE_FOOT = 1.0
        /**
         * How many places across its spiral it tries for what is flimsy each tick, for every column the spiral covers,
         * reckoned on the square of its reach: about one try per column every hundred and fifty ticks.
         */
        private const val STRIPPED_PER_COLUMN = 0.02
        private const val BOTH_SIDES = 2.0

        /** The ground is read at its foot's middle and at eight places round it, four fifths of the way to its edge. */
        private const val FOOT_SAMPLES = 8
        private const val FOOT_SAMPLED_TO = 0.8f

        /** How much of the way to the ground under it the foot goes each tick. */
        private const val FOOT_FOLLOWS = 0.25

        /** A walk to a slow jog, so it can be outrun. */
        const val SLOWEST_SPEED = 0.08
        const val FASTEST_SPEED = 0.16

        private const val TURN_EVERY = 80
        private const val WANDER_MOST_DEGREES = 35.0

        private const val FORGOTTEN_AT = 384.0
        private const val FORGOTTEN_AFTER = 600

        private const val SPENT = 0.0
        private const val CHUNK_BITS = 4
        /** The yaw of a bearing from east turning to south. */
        private const val YAW_OF_EAST = -90.0f

        private const val AGE_KEY = "age"
        private const val STRENGTH_KEY = "strength"
        private const val STARTING_STRENGTH_KEY = "starting_strength"
        private const val SPEED_KEY = "speed"
        private const val WIND_YAW_KEY = "wind_yaw"
        private const val SINCE_SEEN_KEY = "since_seen"
        private const val FOOT_HALF_WIDTH_KEY = "foot_half_width"

        /**
         * Stands one up at [atX], [atZ], [size] of the way from the narrowest to the widest — or null where nothing
         * there would tick yet, or the ground there already reaches the overcast.
         */
        fun raise(level: ServerLevel, atX: Double, atZ: Double, size: Double): Tornado? {
            val blockX = Mth.floor(atX)
            val blockZ = Mth.floor(atZ)
            if (!level.isPositionEntityTicking(BlockPos(blockX, level.minY, blockZ))) return null
            val ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ).toDouble()
            if (ground >= BREAKS_UP_AT) return null
            val tornado = AgeContent.TORNADO.create(level, EntitySpawnReason.EVENT) ?: return null
            val share = size.coerceIn(0.0, 1.0)
            tornado.setPos(atX, ground, atZ)
            tornado.windYaw = Mth.wrapDegrees(windBearingIn(level).toFloat() + YAW_OF_EAST)
            tornado.yRot = tornado.windYaw
            tornado.speed = SLOWEST_SPEED + level.random.nextDouble() * (FASTEST_SPEED - SLOWEST_SPEED)
            tornado.footHalfWidth = Mth.lerp(share, NARROWEST_FOOT, WIDEST_FOOT).toFloat()
            val drawn = 1.0 + (level.random.nextDouble() * 2.0 - 1.0) * STRENGTH_SPREAD
            tornado.startingStrength = Mth.lerp(share, NARROWEST_STRENGTH, WIDEST_STRENGTH) * drawn
            tornado.strength = tornado.startingStrength
            level.addFreshEntity(tornado)
            return tornado
        }

        /** The way an Age's wind blows, from its seed, whether or not a book wrote one; the level's own elsewhere. */
        private fun windBearingIn(level: ServerLevel): Double =
            Atmosphere.prevailingBearingDegrees(Ages.recipeOf(level)?.seed ?: level.seed).toDouble()
    }
}
