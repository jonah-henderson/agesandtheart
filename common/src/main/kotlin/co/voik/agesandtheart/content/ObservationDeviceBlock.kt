package co.voik.agesandtheart.content

import co.voik.agesandtheart.location
import co.voik.agesandtheart.page.Acquaintance
import co.voik.agesandtheart.page.Acquainted
import com.mojang.serialization.Codec
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.TagKey
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import java.util.UUID

/**
 * The D'ni observation device: set on a phasmium cage, it studies the creatures the cage holds and learns
 * what the Art calls them (design §8.3).
 *
 * The third acquaintance device, and it wears the same three stages as the other two ([DeviceStage]).
 * What differs is that its referent can walk away: a creature is studied only if it is still in the cage
 * when the study ends, having never left it, so the working stage is watched every tick by the block
 * entity rather than waited out on a scheduled tick.
 *
 * Nothing is consumed. The price is catching the creature and keeping it caged.
 */
class ObservationDeviceBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(DeviceStage.PROPERTY, DeviceStage.IDLE))
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        ObservationDeviceBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(DeviceStage.PROPERTY)
    }

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? =
        if (level.isClientSide) null
        else createTickerHelper(type, AgeContent.OBSERVATION_DEVICE_ENTITY, ObservationDeviceBlockEntity::tick)

    /** Only the empty-hand door, as the surveying device has: nothing is fed to it. */
    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val observer = player as? ServerPlayer ?: return InteractionResult.FAIL
        val serverLevel = level as? ServerLevel ?: return InteractionResult.FAIL
        val device = level.getBlockEntity(pos) as? ObservationDeviceBlockEntity ?: return InteractionResult.FAIL
        when (state.getValue(DeviceStage.PROPERTY)) {
            DeviceStage.IDLE -> begin(serverLevel, pos, state, observer, device)
            DeviceStage.WORKING -> DeviceWork.say(observer, "device.agesandtheart.observation_device.working")
            DeviceStage.READY -> hand(serverLevel, pos, state, observer, device)
        }
        return InteractionResult.SUCCESS
    }

    /**
     * Starts a study of every creature wholly inside the cage that would teach [observer] something, or
     * says why there is nothing to study.
     */
    private fun begin(
        level: ServerLevel,
        pos: BlockPos,
        state: BlockState,
        observer: ServerPlayer,
        device: ObservationDeviceBlockEntity,
    ) {
        val cage = Cages.around(level, pos)
            ?: return DeviceWork.say(observer, "device.agesandtheart.observation_device.no_cage")
        val held = Cages.creaturesIn(level, cage)
        if (held.isEmpty()) return DeviceWork.say(observer, "device.agesandtheart.observation_device.empty")
        val refusals = held.associateWith { Acquaintance.refusalFor(observer, Acquaintance.kindOf(it)) }
        val worthStudying = held.filter { refusals[it] == null }
        if (worthStudying.isEmpty()) return Acquaintance.tell(observer, refusals.values.filterNotNull().first())
        device.begin(cage, worthStudying, observer)
        level.setBlock(pos, state.setValue(DeviceStage.PROPERTY, DeviceStage.WORKING), UPDATE_ALL)
        level.playSound(null, pos, SoundEvents.SPYGLASS_USE, SoundSource.BLOCKS, DeviceWork.VOLUME, DeviceWork.PITCH)
        DeviceWork.say(observer, "device.agesandtheart.observation_device.started")
    }

    /**
     * Every word the study earned, handed over at once. Where nothing was learned, the first reason why is
     * what is said, since the action bar holds one line.
     */
    private fun hand(
        level: ServerLevel,
        pos: BlockPos,
        state: BlockState,
        observer: ServerPlayer,
        device: ObservationDeviceBlockEntity,
    ) {
        val outcomes = Acquaintance.teachEach(observer, device.studied)
        if (outcomes.any { it is Acquainted.Learned }) {
            level.playSound(
                null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, DeviceWork.VOLUME, DeviceWork.PITCH,
            )
        } else {
            outcomes.firstOrNull()?.let { Acquaintance.tell(observer, it) }
        }
        device.clear()
        level.setBlock(pos, state.setValue(DeviceStage.PROPERTY, DeviceStage.IDLE), UPDATE_ALL)
    }

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
        DeviceWork.workingParticles(level, pos, random, state)
    }
}

/**
 * A study in progress, and then its result: the cage found when it began, the creatures still being
 * watched, how long is left, and once it is done the kinds that stayed.
 */
class ObservationDeviceBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(AgeContent.OBSERVATION_DEVICE_ENTITY, pos, state) {

    private var cage: BoundingBox? = null
    private var watching: Set<UUID> = emptySet()
    private var ticksLeft: Int = 0
    private var startedBy: UUID? = null

    /** The kinds a finished study earned, which is what the device hands over. */
    var studied: List<Identifier> = emptyList()
        private set

    fun begin(cage: BoundingBox, creatures: List<Mob>, observer: ServerPlayer) {
        this.cage = cage
        watching = creatures.map(Entity::getUUID).toSet()
        ticksLeft = STUDY_TICKS
        startedBy = observer.uuid
        studied = emptyList()
        setChanged()
    }

    fun clear() {
        cage = null
        watching = emptySet()
        ticksLeft = 0
        startedBy = null
        studied = emptyList()
        setChanged()
    }

    /**
     * Lets go of every creature that has left the cage or died, and ends the study early once none are
     * left. At the end the cage is found again, so a frame broken mid-study undoes it.
     */
    private fun watch(level: ServerLevel, pos: BlockPos, state: BlockState) {
        watching = watching.filterTo(mutableSetOf()) { stillCaged(level, it, cage) }
        if (watching.isEmpty()) return abandon(level, pos, state)
        if (ticksLeft % PUFF_EVERY == 0) watching.forEach { puffOver(level, it) }
        ticksLeft--
        setChanged()
        if (ticksLeft > 0) return
        val cageNow = Cages.around(level, pos)
        val stayed = watching.filter { stillCaged(level, it, cageNow) }
        if (stayed.isEmpty()) return abandon(level, pos, state)
        studied = stayed.mapNotNull { level.getEntity(it) }.map(Acquaintance::kindOf).distinct()
        DeviceWork.finishWork(level, pos, state)
    }

    private fun stillCaged(level: ServerLevel, creature: UUID, cage: BoundingBox?): Boolean {
        val found = level.getEntity(creature) ?: return false
        return cage != null && found.isAlive && Cages.hold(cage, found)
    }

    private fun abandon(level: ServerLevel, pos: BlockPos, state: BlockState) {
        val observer = startedBy?.let(level::getPlayerByUUID) as? ServerPlayer
        observer?.let { DeviceWork.say(it, "device.agesandtheart.observation_device.abandoned") }
        clear()
        level.setBlock(pos, state.setValue(DeviceStage.PROPERTY, DeviceStage.IDLE), Block.UPDATE_ALL)
        level.playSound(null, pos, SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, DeviceWork.VOLUME, DeviceWork.PITCH)
    }

    /** Shows which creatures are being studied, since nothing else about them says so. */
    private fun puffOver(level: ServerLevel, creature: UUID) {
        val found = level.getEntity(creature) ?: return
        level.sendParticles(
            ParticleTypes.ENCHANT, found.x, found.y + found.bbHeight, found.z, MOTES, SPREAD, SPREAD, SPREAD, DRIFT,
        )
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        cage = input.read(CAGE_KEY, BoundingBox.CODEC).orElse(null)
        watching = input.read(WATCHING_KEY, UUIDUtil.CODEC_SET).orElse(emptySet())
        ticksLeft = input.getIntOr(TICKS_LEFT_KEY, 0)
        startedBy = input.read(STARTED_BY_KEY, UUIDUtil.CODEC).orElse(null)
        studied = input.read(STUDIED_KEY, Identifier.CODEC.listOf()).orElse(emptyList())
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        cage?.let { output.store(CAGE_KEY, BoundingBox.CODEC, it) }
        if (watching.isNotEmpty()) output.store(WATCHING_KEY, UUIDUtil.CODEC_SET, watching)
        if (ticksLeft > 0) output.putInt(TICKS_LEFT_KEY, ticksLeft)
        startedBy?.let { output.store(STARTED_BY_KEY, UUIDUtil.CODEC, it) }
        if (studied.isNotEmpty()) output.store(STUDIED_KEY, Identifier.CODEC.listOf(), studied)
    }

    companion object {
        /** Ten seconds, which is as long as a creature has to be kept. */
        const val STUDY_TICKS = 200

        fun tick(level: Level, pos: BlockPos, state: BlockState, device: ObservationDeviceBlockEntity) {
            val serverLevel = level as? ServerLevel ?: return
            if (state.getValue(DeviceStage.PROPERTY) != DeviceStage.WORKING) return
            device.watch(serverLevel, pos, state)
        }

        private const val PUFF_EVERY = 20
        private const val MOTES = 6
        private const val SPREAD = 0.3
        private const val DRIFT = 0.5

        private const val CAGE_KEY = "cage"
        private const val WATCHING_KEY = "watching"
        private const val TICKS_LEFT_KEY = "ticks_left"
        private const val STARTED_BY_KEY = "started_by"
        private const val STUDIED_KEY = "studied"
    }
}

/** [CageShape] asked of a level, and what the cage it finds is holding. */
object Cages {
    /** What a cage is built of: phasmium, and nara once it exists. */
    val FRAME: TagKey<Block> = TagKey.create(Registries.BLOCK, "cage_frame".location())

    fun around(level: ServerLevel, device: BlockPos): BoundingBox? =
        CageShape.around({ level.isLoaded(it) && level.getBlockState(it).`is`(FRAME) }, device)

    /** Every creature wholly inside [cage]. */
    fun creaturesIn(level: ServerLevel, cage: BoundingBox): List<Mob> =
        level.getEntitiesOfClass(Mob::class.java, AABB.of(cage)) { hold(cage, it) }

    /**
     * Whether [creature] is wholly inside [cage], counting the frame's own blocks, so a creature
     * standing on the ground inside a ring of frame laid on it is in the cage.
     */
    fun hold(cage: BoundingBox, creature: Entity): Boolean {
        val body = creature.boundingBox.deflate(TOUCHING)
        val inside = AABB.of(cage)
        return inside.minX <= body.minX && inside.minY <= body.minY && inside.minZ <= body.minZ &&
            body.maxX <= inside.maxX && body.maxY <= inside.maxY && body.maxZ <= inside.maxZ
    }

    /** Allows a body flush against a face, which floating point would otherwise put a hair outside it. */
    private const val TOUCHING = 1.0e-4
}
