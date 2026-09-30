package co.voik.agesandtheart.content

import co.voik.agesandtheart.Constants
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.ProblemReporter
import net.minecraft.util.RandomSource
import net.minecraft.world.attribute.EnvironmentAttributes
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
 * The base of a scarab's pillar: the mud a scarab claimed, become a nest that is its own, as a bed is a
 * villager's (design §7.1.2). It looks like the mud it was.
 *
 * A point of interest with one ticket, so a colony finds its columns without every mud block being
 * indexed. The pillar standing on it is mud with **one course of packed mud partway up — the chamber, which
 * is what a player sees of a nest** (Jonah, 2026-09-30) — and how high it has got is counted rather than
 * stored, so a pillar somebody quarries is simply built again.
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
 * Whose nest this is, how high its pillar is meant to rise, and the scarab asleep inside it.
 *
 * A sleeper is kept as its saved data, as a beehive keeps its bees, and **keeps its UUID** where a bee's is
 * thrown away: the owner is recorded by UUID, and a scarab let out as a stranger would no longer own the
 * nest it slept in.
 */
class ScarabNestBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(AgeContent.SCARAB_NEST_ENTITY, pos, state) {

    var owner: UUID? = null
        private set

    /** How many blocks of mud the owner means to stack on this, drawn when it was claimed. */
    var pillarGoal: Int = 0
        private set

    /**
     * Which course of the pillar is the chamber, counted up from the nest: never the first nor the last,
     * drawn when it was claimed.
     */
    private var chamberAt: Int = 0

    private var sleeper: TypedEntityData<EntityType<*>>? = null
    private var sleptFor: Int = 0

    val isVacant: Boolean get() = owner == null

    val isOccupied: Boolean get() = sleeper != null

    fun belongsTo(scarab: Scarab): Boolean = owner == scarab.uuid

    fun claimFor(scarab: Scarab, random: RandomSource) {
        owner = scarab.uuid
        pillarGoal = random.nextIntBetweenInclusive(SHORTEST_PILLAR, TALLEST_PILLAR)
        chamberAt = random.nextIntBetweenInclusive(LOWEST_CHAMBER, pillarGoal - 1)
        setChanged()
    }

    /** Gives the nest up, if [scarab] is the one holding it. */
    fun vacate(scarab: Scarab) {
        if (!belongsTo(scarab)) return
        owner = null
        setChanged()
    }

    /** How many courses of the pillar stand on the nest, counted up from it — mud, and the chamber. */
    fun pillarHeight(level: Level): Int {
        fun isACourse(height: Int): Boolean {
            val state = level.getBlockState(blockPos.above(height))
            return state.`is`(Blocks.MUD) || state.`is`(Blocks.PACKED_MUD)
        }
        var height = 0
        while (height < TALLEST_PILLAR && isACourse(height + 1)) height++
        return height
    }

    /** What the next course laid is: the chamber where it has got to, mud everywhere else. */
    fun nextCourse(level: Level): BlockState {
        val isTheChamber = pillarHeight(level) + 1 == chamberAt
        return if (isTheChamber) Blocks.PACKED_MUD.defaultBlockState() else Blocks.MUD.defaultBlockState()
    }

    /** Where the next block of the pillar goes, which is also where a scarab goes in and comes out. */
    fun topOfThePillar(level: Level): BlockPos = blockPos.above(pillarHeight(level) + 1)

    fun isPillarFinished(level: Level): Boolean = pillarHeight(level) >= pillarGoal

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
     * Sends the sleeper out at the top of the pillar. **In an emergency**, which is the nest being broken,
     * whatever the hour and whatever is standing there.
     */
    fun letOut(level: ServerLevel, emergency: Boolean) {
        val data = sleeper ?: return
        val door = topOfThePillar(level)
        val doorIsOpen = level.getBlockState(door).getCollisionShape(level, door).isEmpty
        if (!emergency && !doorIsOpen) return
        val scarab = awake(level, data) ?: return abandon()
        scarab.snapTo(door.x + HALF, door.y + CLEAR_OF_THE_TOP, door.z + HALF, scarab.yRot, scarab.xRot)
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
        pillarGoal = input.getIntOr(PILLAR_GOAL_KEY, 0)
        chamberAt = input.getIntOr(CHAMBER_AT_KEY, 0)
        sleeper = input.read(SLEEPER_KEY, SLEEPER_CODEC).orElse(null)
        sleptFor = input.getIntOr(SLEPT_FOR_KEY, 0)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        owner?.let { output.store(OWNER_KEY, UUIDUtil.CODEC, it) }
        output.putInt(PILLAR_GOAL_KEY, pillarGoal)
        output.putInt(CHAMBER_AT_KEY, chamberAt)
        sleeper?.let { output.store(SLEEPER_KEY, SLEEPER_CODEC, it) }
        if (sleeper != null) output.putInt(SLEPT_FOR_KEY, sleptFor)
    }

    companion object {
        /** Design §7.1.2's starting range, to be tuned by playtest. */
        const val SHORTEST_PILLAR = 4
        const val TALLEST_PILLAR = 7

        /** The chamber sits at least this far up, so the first course is always plain mud. */
        private const val LOWEST_CHAMBER = 2

        fun tick(level: Level, pos: BlockPos, state: BlockState, nest: ScarabNestBlockEntity) {
            val serverLevel = level as? ServerLevel ?: return
            if (!nest.isOccupied) return
            nest.sleptFor++
            val hasSleptEnough = nest.sleptFor > SHORTEST_SLEEP
            if (hasSleptEnough && !isTimeToRoost(serverLevel, pos)) nest.letOut(serverLevel, emergency = false)
        }

        /** Night, and weather a bee would stay in for — vanilla's own attribute, which an Age's timeline drives. */
        fun isTimeToRoost(level: Level, at: BlockPos): Boolean =
            level.environmentAttributes().getValue(EnvironmentAttributes.BEES_STAY_IN_HIVE, at)

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

        private const val HALF = 0.5
        private const val CLEAR_OF_THE_TOP = 0.1

        private const val SOUND_VOLUME = 0.6f
        private const val SOUND_PITCH = 1.3f

        private const val OWNER_KEY = "owner"
        private const val PILLAR_GOAL_KEY = "pillar_goal"
        private const val CHAMBER_AT_KEY = "chamber_at"
        private const val SLEEPER_KEY = "sleeper"
        private const val SLEPT_FOR_KEY = "slept_for"
    }
}
