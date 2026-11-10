package co.voik.agesandtheart.content

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.BlockItemStateProperties
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A case of shelves holding spares — see [Toolbox] for what carrying one does.
 *
 * **Furniture and luggage at once**, which is the whole of it: placed in a study it is one of the desk's
 * implements, and carried it is what puts a fresh pickaxe in your hand when the last one snaps.
 */
class ToolboxBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(WOOD, WoodKind.OAK))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, WOOD)
    }

    /** Its wood is the item's, which [net.minecraft.world.item.BlockItem] sets once it is placed. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPES.getValue(state.getValue(FACING))

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun getCloneItemStack(level: LevelReader, pos: BlockPos, state: BlockState, includeData: Boolean): ItemStack =
        inItsWood(super.getCloneItemStack(level, pos, state, includeData), state)

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = ToolboxBlockEntity(pos, state)

    /** Opening it is vanilla's own container flow; the nine compartments are a dispenser's screen. */
    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val toolbox = level.getBlockEntity(pos) as? ToolboxBlockEntity ?: return InteractionResult.PASS
        player.openMenu(toolbox)
        return InteractionResult.CONSUME
    }

    /**
     * Broken by somebody who gets no drops — a creative player — the box hands itself over anyway.
     *
     * **The other half of suppressing the scatter**, and the shulker box's own answer. Once
     * [ToolboxBlockEntity.preRemoveSideEffects] stops emptying the box onto the floor, the loot table is
     * the only thing carrying the spares away — and a creative break runs no loot table, so without this
     * the contents would simply cease to exist. Vanilla's chests get away with having neither because they
     * scatter.
     */
    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val toolbox = level.getBlockEntity(pos) as? ToolboxBlockEntity
        if (toolbox != null && !level.isClientSide && player.preventsBlockDrops() && !toolbox.isEmpty) {
            val handed = inItsWood(ItemStack(state.block), state)
            handed.applyComponents(toolbox.collectComponents())
            val dropped = ItemEntity(level, pos.x + MIDDLE, pos.y + MIDDLE, pos.z + MIDDLE, handed)
            dropped.setDefaultPickUpDelay()
            level.addFreshEntity(dropped)
        }
        return super.playerWillDestroy(level, pos, state, player)
    }

    companion object {
        val FACING: EnumProperty<Direction> = HorizontalDirectionalBlock.FACING
        val WOOD: EnumProperty<WoodKind> = EnumProperty.create("wood", WoodKind::class.java)

        /** The name of a toolbox of [wood]: "Dark Oak Toolbox". */
        fun nameOf(wood: WoodKind): Component = Component.translatable("block.agesandtheart.toolbox.${wood.serializedName}")

        /** A toolbox of [wood], as its recipe makes one. */
        fun stackOf(wood: WoodKind): ItemStack {
            val stack = ItemStack(AgeContent.TOOLBOX)
            stack.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(WOOD, wood))
            return stack
        }

        /** [stack], marked with the wood of the toolbox in [state], as the loot table's `copy_state` marks a broken one. */
        fun inItsWood(stack: ItemStack, state: BlockState): ItemStack {
            val properties = stack.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY)
            stack.set(DataComponents.BLOCK_STATE, properties.with(WOOD, state))
            return stack
        }

        /** The box and the bar of its handle, facing north as the model does: an end, and the handle, toward you. */
        private val SHAPES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(
            Shapes.or(box(4.0, 0.0, 2.0, 12.0, 5.0, 14.0), box(7.5, 10.0, 2.0, 8.5, 11.0, 14.0)),
        )

        private const val MIDDLE = 0.5
    }
}
