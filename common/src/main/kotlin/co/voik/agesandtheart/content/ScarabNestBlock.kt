package co.voik.agesandtheart.content

import co.voik.agesandtheart.Constants
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.UUIDUtil
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.ProblemReporter
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.EntityProcessor
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.component.TypedEntityData
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.TagValueOutput
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import java.util.UUID

/**
 * The foot of a scarab pillar (design §7.1.2): the mud a colony began a pillar on, mud to look at. It holds
 * the pillar's plan — how tall it is to rise, and which course is its nest — and belongs to nobody: any
 * scarab of the colony works on it.
 */
class ScarabPillarBlock(properties: Properties) : BaseEntityBlock(properties) {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = ScarabPillarBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL
}

/**
 * How tall a pillar is meant to rise and which course of it is the nest, both drawn when it was begun. How
 * high it has got is counted rather than stored, so a pillar somebody quarries is simply built again.
 */
class ScarabPillarBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(AgeContent.SCARAB_PILLAR_ENTITY, pos, state) {

    /** How many courses the colony means to stack on this. */
    var goal: Int = 0
        private set

    /** Which course is the nest, counted up from the foot: never the first nor the last. */
    var nestAt: Int = 0
        private set

    fun plan(random: RandomSource) {
        goal = random.nextIntBetweenInclusive(SHORTEST_PILLAR, TALLEST_PILLAR)
        nestAt = random.nextIntBetweenInclusive(LOWEST_NEST, goal - 1)
        setChanged()
    }

    /** How many courses stand on the foot, counted up from it — mud, and the nest. */
    fun height(level: Level): Int {
        fun isACourse(height: Int): Boolean {
            val state = level.getBlockState(blockPos.above(height))
            return state.`is`(Blocks.MUD) || state.`is`(AgeContent.SCARAB_NEST_BLOCK)
        }
        var height = 0
        while (height < TALLEST_PILLAR && isACourse(height + 1)) height++
        return height
    }

    /**
     * As tall as it was meant to be, or as tall as it can get: a pillar under a roof stops where the next
     * course, and the air a scarab lays it from, would not fit.
     */
    fun isFinished(level: Level): Boolean {
        val next = topOf(level)
        val hasRoomToGrow = level.getBlockState(next).canBeReplaced() && level.getBlockState(next.above()).canBeReplaced()
        return height(level) >= goal || !hasRoomToGrow
    }

    /** Where the next course goes. */
    fun topOf(level: Level): BlockPos = blockPos.above(height(level) + 1)

    /** Whether the next course laid is the nest. */
    fun nextIsTheNest(level: Level): Boolean = height(level) + 1 == nestAt

    /** What the course [height] up the pillar is: the nest, or mud. */
    fun courseAt(height: Int): BlockState =
        if (height == nestAt) AgeContent.SCARAB_NEST_BLOCK.defaultBlockState() else Blocks.MUD.defaultBlockState()

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        goal = input.getIntOr(GOAL_KEY, 0)
        nestAt = input.getIntOr(NEST_AT_KEY, 0)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putInt(GOAL_KEY, goal)
        output.putInt(NEST_AT_KEY, nestAt)
    }

    companion object {
        /** Design §7.1.2's starting range, to be tuned by playtest. */
        const val SHORTEST_PILLAR = 4
        const val TALLEST_PILLAR = 7

        /** The nest sits at least this far up, so the first course is always plain mud. */
        private const val LOWEST_NEST = 2

        private const val GOAL_KEY = "goal"
        private const val NEST_AT_KEY = "nest_at"
    }
}

/**
 * A scarab's nest: the course of packed mud a colony laid partway up a pillar, **laid empty** and claimed
 * by the first homeless scarab to reach it, as a bed is a villager's. Its owner sleeps inside it, as a bee
 * in its hive. Broken, it is mud, and its sleeper is turned out.
 */
class ScarabNestBlock(properties: Properties) : BaseEntityBlock(properties) {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = ScarabNestBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? =
        if (level.isClientSide) null
        else createTickerHelper(type, AgeContent.SCARAB_NEST_ENTITY, ScarabNestBlockEntity::tick)
}

/**
 * Whose nest this is, and the scarab asleep inside it.
 *
 * A sleeper is kept as its saved data, as a beehive keeps its bees, and **keeps its UUID** where a bee's is
 * thrown away: the owner is recorded by UUID, and a scarab let out as a stranger would no longer own the
 * nest it slept in.
 */
class ScarabNestBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(AgeContent.SCARAB_NEST_ENTITY, pos, state) {

    var owner: UUID? = null
        private set

    private var sleeper: TypedEntityData<EntityType<*>>? = null
    private var sleptFor: Int = 0

    val isVacant: Boolean get() = owner == null

    val isOccupied: Boolean get() = sleeper != null

    fun belongsTo(scarab: Scarab): Boolean = owner == scarab.uuid

    fun claimFor(scarab: Scarab) {
        owner = scarab.uuid
        setChanged()
    }

    /** Gives the nest up, if [scarab] is the one holding it. */
    fun vacate(scarab: Scarab) {
        if (!belongsTo(scarab)) return
        owner = null
        setChanged()
    }

    /**
     * A nest the Age grew with its colony: claimed for [scarab], who is **asleep inside it** as if it had
     * slept the night, so a colony a chunk was generated with comes out at the first daylight after it loads.
     * The scarab was never in the world, which is why it is kept as data and never discarded.
     */
    fun grownWith(scarab: Scarab) {
        claimFor(scarab)
        sleeper = asleep(scarab)
        sleptFor = SHORTEST_SLEEP + 1
        setChanged()
    }

    /**
     * Where a scarab goes in and comes out: **an open side of the nest**, as a bee goes into the face of its
     * hive, turned by where the nest stands so a colony's doors do not all face one way. Where every side is
     * shut, the top of the pillar above it, or the nest's own top.
     */
    fun doorOf(level: Level): BlockPos {
        val sides = Direction.Plane.HORIZONTAL.toList()
        val turn = Math.floorMod(blockPos.x * SIDE_MIXER_X + blockPos.z * SIDE_MIXER_Z, sides.size)
        val beside = sides.indices.map { sides[(it + turn) % sides.size] }.map(blockPos::relative)
        return beside.firstOrNull { isOpen(level, it) } ?: aboveThePillar(level)
    }

    private fun aboveThePillar(level: Level): BlockPos {
        var above = blockPos.above()
        while (!isOpen(level, above) && above.y < level.maxY) above = above.above()
        return above
    }

    private fun isOpen(level: Level, at: BlockPos): Boolean = level.getBlockState(at).getCollisionShape(level, at).isEmpty

    /** Takes [scarab] in for the night, or refuses where the nest is not its own or somebody is already in. */
    fun admit(scarab: Scarab): Boolean {
        if (!belongsTo(scarab) || isOccupied) return false
        scarab.stopRiding()
        scarab.ejectPassengers()
        scarab.dropLeash()
        sleeper = asleep(scarab)
        sleptFor = 0
        scarab.discard()
        level?.playSound(null, blockPos, SoundEvents.MUD_PLACE, SoundSource.BLOCKS, SOUND_VOLUME, SOUND_PITCH)
        setChanged()
        return true
    }

    /**
     * Sends the sleeper out of its door. **In an emergency**, which is the nest being broken, whatever the
     * hour and whatever is standing there.
     */
    fun letOut(level: ServerLevel, emergency: Boolean) {
        val data = sleeper ?: return
        val door = doorOf(level)
        if (!emergency && !isOpen(level, door)) return
        val scarab = awake(level, data) ?: return abandon()
        scarab.snapTo(door.x + HALF, door.y + CLEAR_OF_THE_FLOOR, door.z + HALF, scarab.yRot, scarab.xRot)
        scarab.wokeAfter(sleptFor)
        if (!level.addFreshEntity(scarab)) return
        sleeper = null
        sleptFor = 0
        level.playSound(null, door, SoundEvents.MUD_BREAK, SoundSource.BLOCKS, SOUND_VOLUME, SOUND_PITCH)
        setChanged()
    }

    /** A sleeper whose data will not load is lost, rather than kept forever blocking the nest. */
    private fun abandon() {
        Constants.LOG.warn("A scarab asleep in the nest at {} could not be woken, and is lost", blockPos)
        sleeper = null
        owner = null
        setChanged()
    }

    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) {
        (level as? ServerLevel)?.let { letOut(it, emergency = true) }
        super.preRemoveSideEffects(pos, state)
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        owner = input.read(OWNER_KEY, UUIDUtil.CODEC).orElse(null)
        sleeper = input.read(SLEEPER_KEY, SLEEPER_CODEC).orElse(null)
        sleptFor = input.getIntOr(SLEPT_FOR_KEY, 0)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        owner?.let { output.store(OWNER_KEY, UUIDUtil.CODEC, it) }
        sleeper?.let { output.store(SLEEPER_KEY, SLEEPER_CODEC, it) }
        if (sleeper != null) output.putInt(SLEPT_FOR_KEY, sleptFor)
    }

    companion object {
        fun tick(level: Level, pos: BlockPos, state: BlockState, nest: ScarabNestBlockEntity) {
            val serverLevel = level as? ServerLevel ?: return
            if (!nest.isOccupied) return
            nest.sleptFor++
            val hasSleptEnough = nest.sleptFor > SHORTEST_SLEEP
            if (hasSleptEnough && !isTimeToRoost(serverLevel)) nest.letOut(serverLevel, emergency = false)
        }

        /**
         * Night on the Age's clock, or rain. Read off the clock itself rather than vanilla's bee attribute,
         * which a roofed Age answers as night at noon, so its scarabs slept and never came out.
         */
        fun isTimeToRoost(level: Level): Boolean {
            val timeOfDay = Math.floorMod(level.overworldClockTime, A_DAY)
            val isNight = timeOfDay >= NIGHT_FALLS && timeOfDay < DAY_BREAKS
            return isNight || level.isRaining
        }

        private fun asleep(scarab: Scarab): TypedEntityData<EntityType<*>> =
            ProblemReporter.ScopedCollector(scarab.problemPath(), Constants.LOG).use { reporter ->
                val output = TagValueOutput.createWithContext(reporter, scarab.registryAccess())
                scarab.save(output)
                FORGOTTEN_INSIDE.forEach(output::discard)
                TypedEntityData.of<EntityType<*>>(scarab.type, output.buildResult())
            }

        private fun awake(level: ServerLevel, data: TypedEntityData<EntityType<*>>): Scarab? {
            val tag = data.copyTagWithoutId()
            FORGOTTEN_INSIDE.forEach(tag::remove)
            return EntityType.loadEntityRecursive(data.type(), tag, level, EntitySpawnReason.LOAD, EntityProcessor.NOP)
                as? Scarab
        }

        /**
         * What a beehive forgets about a bee, less its UUID: where it was and how it was moving, and what
         * it was tied to.
         */
        private val FORGOTTEN_INSIDE: List<String> = listOf(
            "Air", "drop_chances", "equipment", "Brain", "CanPickUpLoot", "DeathTime", "fall_distance",
            "FallFlying", "Fire", "HurtTime", "LeftHanded", "Motion", "NoGravity", "OnGround", "PortalCooldown",
            "Pos", "Rotation", "sleeping_pos", "Passengers", "leash",
        )

        private val SLEEPER_CODEC = TypedEntityData.codec(EntityType.CODEC)

        /** Half a minute, so a scarab caught out at dawn does not go in and straight out again. */
        private const val SHORTEST_SLEEP = 600

        /** A day on the clock, and when a bee's night begins and ends on it (vanilla's `day` timeline). */
        private const val A_DAY = 24000L
        private const val NIGHT_FALLS = 12542L
        private const val DAY_BREAKS = 23460L

        private const val HALF = 0.5
        private const val CLEAR_OF_THE_FLOOR = 0.1

        private const val SIDE_MIXER_X = 31
        private const val SIDE_MIXER_Z = 17

        private const val SOUND_VOLUME = 0.6f
        private const val SOUND_PITCH = 1.3f

        private const val OWNER_KEY = "owner"
        private const val SLEEPER_KEY = "sleeper"
        private const val SLEPT_FOR_KEY = "slept_for"
    }
}
