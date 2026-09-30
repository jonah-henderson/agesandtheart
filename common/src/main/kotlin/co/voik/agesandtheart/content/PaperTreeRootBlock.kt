package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.MangroveRootsBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * The heart of a paper tree: the root under its trunk, which is the plant and the thing a writer keeps
 * (design §7.1.2).
 *
 * Once a minute it looks at whether any root of its system touches water — rain never counts — remembers
 * the look, shows the share of the last tide it spent wet as [PaperTreeHealth.MOISTURE], and brings the tree
 * into line with how far out of its band it has been. A fixed beat rather than a random tick, because the
 * share has to be read over a whole tide (see [PaperTreeHealth]).
 *
 * **Felling the trunk leaves it**, and a healthy heart grows a new one: coppicing, so paper is a steady
 * trickle from a place you own.
 */
class PaperTreeRootBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(PaperTreeHealth.MOISTURE, PaperTreeHealth.SETTLED))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(PaperTreeHealth.MOISTURE)
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = PaperTreeRootBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? =
        if (level.isClientSide) null
        else createTickerHelper(type, AgeContent.PAPER_TREE_ROOT_ENTITY, PaperTreeRootBlockEntity::tick)
}

/**
 * The seed a tree's shape grows from, and its strain — the two numbers the heart needs to know its own
 * tree and how that tree is. Everything else is read off the world.
 */
class PaperTreeRootBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(AgeContent.PAPER_TREE_ROOT_ENTITY, pos, state) {

    private var seed: Long = 0L
    private var strain: Int = 0
    private var history: Int = PaperTreeHealth.SETTLED_HISTORY

    /** A new tree at this heart, remembering [history] of wet and dry — a sapling's, or the middle. */
    fun plant(seed: Long, history: Int = PaperTreeHealth.SETTLED_HISTORY) {
        this.seed = seed
        this.history = history
        strain = 0
        setChanged()
    }

    /** One look: wet or dry, then whatever the strain has reached. */
    private fun tend(level: ServerLevel, state: BlockState) {
        val shape = PaperTreeShape.grownFrom(blockPos, seed)
        history = PaperTreeHealth.remembered(history, touchesWater(level, shape))
        val was = state.getValue(PaperTreeHealth.MOISTURE)
        val moisture = PaperTreeHealth.moistureOf(history)
        if (moisture != was) {
            level.setBlock(blockPos, state.setValue(PaperTreeHealth.MOISTURE, moisture), Block.UPDATE_CLIENTS)
        }
        val band = PaperTreeHealth.bandOf(moisture)
        strain = PaperTreeHealth.strained(strain, band)
        setChanged()
        val stage = PaperTreeHealth.stageOf(strain)
        when (stage) {
            PaperTreeHealth.Stage.HEALTHY,
            PaperTreeHealth.Stage.OUTER_LEAVES_TURNING,
            PaperTreeHealth.Stage.ALL_LEAVES_TURNING,
            -> colourTheLeaves(level, shape, stage)
            PaperTreeHealth.Stage.LEAVES_FALLEN -> dropTheLeaves(level, shape)
            PaperTreeHealth.Stage.LOGS_DYING -> {
                dropTheLeaves(level, shape)
                killTheHighestLog(level, shape)
            }
            PaperTreeHealth.Stage.ROOT_DYING -> {
                dropTheLeaves(level, shape)
                if (!killTheHighestLog(level, shape)) die(level, shape)
            }
        }
        val isThriving = strain == 0 && band == PaperTreeHealth.Band.SUITS
        if (isThriving) growBack(level, shape)
    }

    /** Whether the heart, or any root of its own it can see, is in water or beside it. */
    private fun touchesWater(level: ServerLevel, shape: PaperTreeShape): Boolean {
        fun isWetAt(at: BlockPos): Boolean {
            val state = level.getBlockState(at)
            val isOurs = state.`is`(AgeContent.PAPER_TREE_ROOTS_BLOCK) || at == blockPos
            if (!isOurs) return false
            val isWaterlogged = state.getOptionalValue(BlockStateProperties.WATERLOGGED).orElse(false)
            fun isWaterBeside(side: Direction) = level.getFluidState(at.relative(side)).`is`(FluidTags.WATER)
            return isWaterlogged || Direction.entries.any(::isWaterBeside)
        }
        return isWetAt(blockPos) || shape.roots.any(::isWetAt)
    }

    /**
     * Every leaf of the tree's own the colour its distance from a log gives it at [stage] — and, short of the
     * leaves falling, back at that distance, so a leaf the tree had begun to shed stops falling when it
     * recovers.
     */
    private fun colourTheLeaves(level: ServerLevel, shape: PaperTreeShape, stage: PaperTreeHealth.Stage) {
        val drowning = strain > 0
        val isHoldingItsLeaves = stage < PaperTreeHealth.Stage.LEAVES_FALLEN
        for ((at, distance) in shape.leaves) {
            val state = level.getBlockState(at)
            if (!isOwnLeaf(state)) continue
            val held = if (isHoldingItsLeaves) state.setValue(LeavesBlock.DISTANCE, distance) else state
            val wanted = PaperTreeHealth.blightOf(stage, held.getValue(LeavesBlock.DISTANCE), drowning)
            val coloured = held.setValue(PaperTreeLeavesBlock.BLIGHT, wanted)
            if (coloured != state) level.setBlock(at, coloured, Block.UPDATE_CLIENTS)
        }
    }

    /**
     * The leaves cut off from their logs, as vanilla's are when a tree is felled — so they fall **a leaf at a
     * time on vanilla's own random ticks**, over a few minutes and dropping what decaying leaves drop, rather
     * than all at once. Set without a shape update, and a shed leaf skips the recount that would put it back
     * on the tree ([PaperTreeLeavesBlock.tick]).
     */
    private fun dropTheLeaves(level: ServerLevel, shape: PaperTreeShape) {
        for ((at, _) in shape.leaves) {
            val state = level.getBlockState(at)
            if (!isOwnLeaf(state) || state.getValue(LeavesBlock.DISTANCE) == LeavesBlock.DECAY_DISTANCE) continue
            val shed = state.setValue(LeavesBlock.DISTANCE, LeavesBlock.DECAY_DISTANCE)
            level.setBlock(at, shed, Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE)
        }
    }

    /** The highest of the tree's own living logs, into dead yema. False where there is none left. */
    private fun killTheHighestLog(level: ServerLevel, shape: PaperTreeShape): Boolean {
        val highest = shape.logs.map { it.at }.filter { isOwnLog(level.getBlockState(it)) }.maxByOrNull { it.y }
            ?: return false
        val axis = level.getBlockState(highest).getValue(RotatedPillarBlock.AXIS)
        level.setBlock(highest, deadWood(axis), Block.UPDATE_ALL)
        return true
    }

    /** The root last: the heart and its roots into dead yema, and the plant is gone. */
    private fun die(level: ServerLevel, shape: PaperTreeShape) {
        for (at in shape.roots) {
            if (level.getBlockState(at).`is`(AgeContent.PAPER_TREE_ROOTS_BLOCK)) {
                level.setBlock(at, deadWood(Direction.Axis.Y), Block.UPDATE_ALL)
            }
        }
        level.setBlock(blockPos, deadWood(Direction.Axis.Y), Block.UPDATE_ALL)
    }

    /**
     * One step of growing back what is missing: the lowest log whose parent stands, then — once the logs
     * are all there — the leaves of one terrace whose logs all are. A felled trunk comes back a log a tick
     * from the root; wood that died stays dead, and nothing regrows past it until it is cleared.
     */
    private fun growBack(level: ServerLevel, shape: PaperTreeShape) {
        for (log in shape.logs) {
            if (isOwnLog(level.getBlockState(log.at))) continue
            val parentStands = log.grownFrom == PaperTreeShape.FROM_THE_ROOT ||
                isOwnLog(level.getBlockState(shape.logs[log.grownFrom].at))
            if (!parentStands || !PaperTreeGrowth.isRoomFor(level, log.at)) continue
            level.setBlock(log.at, PaperTreeGrowth.livingLog(log.axis), Block.UPDATE_ALL)
            return
        }
        for (terrace in shape.terraces) {
            val isCarried = terrace.logs.all { isOwnLog(level.getBlockState(shape.logs[it].at)) }
            if (!isCarried) continue
            fun isMissing(at: BlockPos) = PaperTreeGrowth.isRoomFor(level, at) && !isOwnLeaf(level.getBlockState(at))
            val missing = terrace.leaves.filterKeys(::isMissing)
            if (missing.isEmpty()) continue
            for ((at, distance) in missing) level.setBlock(at, PaperTreeGrowth.livingLeaf(distance), Block.UPDATE_ALL)
            return
        }
    }

    private fun isOwnLog(state: BlockState): Boolean =
        state.`is`(AgeContent.PAPER_TREE_LOG_BLOCK) && state.getValue(PaperTreeLogBlock.OF_THE_TREE)

    private fun isOwnLeaf(state: BlockState): Boolean =
        state.`is`(AgeContent.PAPER_TREE_LEAVES_BLOCK) && !state.getValue(LeavesBlock.PERSISTENT)

    private fun deadWood(axis: Direction.Axis): BlockState =
        AgeContent.DEAD_PAPER_TREE_LOG_BLOCK.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis)

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        seed = input.getLongOr(SEED_KEY, 0L)
        strain = input.getIntOr(STRAIN_KEY, 0)
        history = input.getIntOr(HISTORY_KEY, PaperTreeHealth.SETTLED_HISTORY)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putLong(SEED_KEY, seed)
        output.putInt(STRAIN_KEY, strain)
        output.putInt(HISTORY_KEY, history)
    }

    /** For `/age yema`: how the tree stands, as the root sees it. */
    fun describe(level: Level): String {
        val moisture = level.getBlockState(blockPos).getOptionalValue(PaperTreeHealth.MOISTURE).orElse(-1)
        return "moisture $moisture (${PaperTreeHealth.bandOf(moisture)}), strain $strain " +
            "(${PaperTreeHealth.stageOf(strain)}), seed $seed"
    }

    companion object {
        fun tick(level: Level, pos: BlockPos, state: BlockState, heart: PaperTreeRootBlockEntity) {
            val serverLevel = level as? ServerLevel ?: return
            // Spread across the minute by position, so a grove does not all look on the same tick.
            val isTimeToLook = (level.gameTime + pos.asLong()) % PaperTreeHealth.SAMPLE_EVERY == 0L
            if (isTimeToLook) heart.tend(serverLevel, state)
        }

        private const val SEED_KEY = "seed"
        private const val STRAIN_KEY = "strain"
        private const val HISTORY_KEY = "history"
    }
}

/**
 * The roots a paper tree spreads from its heart: a mangrove's to look at, waterloggable like one, and what
 * feels the water for the heart — any one of them in water or beside it wets the tree.
 */
class PaperTreeRootsBlock(properties: Properties) : MangroveRootsBlock(properties)
