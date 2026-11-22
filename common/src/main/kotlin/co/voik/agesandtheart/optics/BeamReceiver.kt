package co.voik.agesandtheart.optics

import co.voik.agesandtheart.content.RimeColour
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.DirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult

/**
 * A beam receiver: a full redstone signal on every side while full light reaches it, by a beam or through the conduit
 * on its one conduit side, of exactly the colour of the rime crystal slotted in, or of any colour without one. A beam
 * reaching it is also light put into that conduit's network.
 */
class BeamReceiverBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(POWERED, false).setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(POWERED, FACING)
    }

    /** Its conduit side toward the block it was placed against. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.clickedFace.opposite)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = BeamReceiverBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide) null
        else createTickerHelper(type, Optics.RECEIVER_ENTITY) { tickedIn, _, _, receiver -> receiver.tick(tickedIn as ServerLevel) }

    override fun isSignalSource(state: BlockState): Boolean = true

    override fun getSignal(state: BlockState, level: BlockGetter, pos: BlockPos, direction: Direction): Int =
        if (state.getValue(POWERED)) FULL_SIGNAL else 0

    override fun useItemOn(
        itemStack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        val receiver = level.getBlockEntity(pos) ?: return InteractionResult.TRY_WITH_EMPTY_HAND
        val used = CrystalHolder.use(receiver, itemStack, player, level, pos)
        return if (used == InteractionResult.PASS) InteractionResult.TRY_WITH_EMPTY_HAND else used
    }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hitResult: BlockHitResult): InteractionResult {
        val receiver = level.getBlockEntity(pos) ?: return InteractionResult.PASS
        return CrystalHolder.use(receiver, ItemStack.EMPTY, player, level, pos)
    }

    companion object {
        val POWERED: BooleanProperty = BlockStateProperties.POWERED

        /** Its conduit side. */
        val FACING: EnumProperty<Direction> = DirectionalBlock.FACING
        private const val FULL_SIGNAL = 15
    }
}

class BeamReceiverBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(Optics.RECEIVER_ENTITY, pos, state), CrystalHolder, TakesBeams {

    override var crystal: RimeColour? = null

    private val reaching = IncomingBeams()

    override fun takeBeam(source: BlockPos, travelling: Direction, light: Light, diffuse: Boolean, until: Long) {
        reaching.take(source, travelling, light, diffuse, until)
    }

    /** The light of the beams reaching it, or null where none is. */
    fun beamLight(level: Level): Light? = reaching.lightAt(level.gameTime)

    fun tick(level: ServerLevel) {
        val light = beamLight(level) ?: LightNetworks.lightFor(level, worldPosition)
        val isLit = light != null && isLitBy(light)
        if (blockState.getValue(BeamReceiverBlock.POWERED) == isLit) return
        level.setBlock(worldPosition, blockState.setValue(BeamReceiverBlock.POWERED, isLit), Block.UPDATE_ALL)
    }

    private fun isLitBy(light: Light): Boolean {
        val isTheColourAsked = crystal == null || light.colour == crystal
        return light.isStrongEnough && isTheColourAsked
    }

    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) {
        level?.let { CrystalHolder.drop(this, it, pos) }
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        crystal = CrystalHolder.load(input)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        CrystalHolder.save(this, output)
    }
}
