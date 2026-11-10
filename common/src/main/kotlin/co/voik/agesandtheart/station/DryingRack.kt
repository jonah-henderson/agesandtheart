package co.voik.agesandtheart.station

import co.voik.agesandtheart.age.reward.DryingSky
import co.voik.agesandtheart.generation.AgeGeneration
import co.voik.agesandtheart.generation.Ages
import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.NonNullList
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.item.crafting.RecipeManager
import net.minecraft.world.item.crafting.SingleRecipeInput
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.util.Prediction
import co.voik.agesandtheart.client.KnownMachineRecipes
import net.minecraft.world.phys.Vec3
import net.minecraft.world.Containers
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.util.RandomSource
import net.minecraft.world.SimpleContainer
import net.minecraft.world.WorldlyContainerHolder
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * The drying rack (design §7.1.2): the masterwork grades' last step, curing or drying under a sky only a
 * written Age has. Six inputs and an output, each input drying its next item alongside the others and hung from
 * the rungs as it does ([co.voik.agesandtheart.client.DryingRackRenderer]); hoppers in from above and the sides and
 * out from below.
 *
 * **Out of its sky an item waits; wet, it starts again**: rain on the rack or water beside it puts every item
 * being dried back to nothing, while anything short of the Age simply pauses. Time is counted in ticks the
 * rack has watched, so nothing dries unloaded.
 *
 * **Two blocks tall, as a door is**: the lower half holds the rack's inventory and drops it, and the upper half is the
 * rest of the ladder. Breaking either breaks both. A rack placed before it stood tall is a lower half alone, which
 * grows its upper half as soon as there is room ([DryingRackBlockEntity.standTall]) and is never taken down for want
 * of one it never had.
 */
class DryingRackBlock(properties: Properties) : BaseEntityBlock(properties), WorldlyContainerHolder {

    init {
        registerDefaultState(stateDefinition.any().setValue(HALF, DoubleBlockHalf.LOWER).setValue(FACING, Direction.NORTH))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(HALF, FACING)
    }

    /** Where there is room above for the upper half, facing whoever places it. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val above = context.clickedPos.above()
        val hasRoomAbove = above.y < context.level.maxY && context.level.getBlockState(above).canBeReplaced(context)
        if (!hasRoomAbove) return null
        return defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)
    }

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), UPDATE_ALL)
        (level.getBlockEntity(pos) as? DryingRackBlockEntity)?.stoodTall()
    }

    /**
     * The upper half goes with the lower; the lower with the upper only where it once had one, so a rack from before
     * the rack stood tall is left standing.
     */
    override fun updateShape(
        state: BlockState,
        level: LevelReader,
        ticks: ScheduledTickAccess,
        pos: BlockPos,
        directionToNeighbour: Direction,
        neighbourPos: BlockPos,
        neighbourState: BlockState,
        random: RandomSource,
    ): BlockState {
        val half = state.getValue(HALF)
        val towardTheOtherHalf = if (half == DoubleBlockHalf.LOWER) Direction.UP else Direction.DOWN
        if (directionToNeighbour != towardTheOtherHalf) {
            return super.updateShape(state, level, ticks, pos, directionToNeighbour, neighbourPos, neighbourState, random)
        }
        val otherHalfStands = neighbourState.`is`(this) && neighbourState.getValue(HALF) != half
        val lostItsOtherHalf = when (half) {
            DoubleBlockHalf.UPPER -> !otherHalfStands
            DoubleBlockHalf.LOWER -> !otherHalfStands && (level.getBlockEntity(pos) as? DryingRackBlockEntity)?.standsTall == true
        }
        return if (lostItsOtherHalf) Blocks.AIR.defaultBlockState() else state
    }

    /** Broken from above by somebody who takes no drops, the lower half goes without dropping, as a door's does. */
    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        val isTheUpperHalf = state.getValue(HALF) == DoubleBlockHalf.UPPER
        if (!level.isClientSide && player.preventsBlockDrops() && isTheUpperHalf) {
            val below = pos.below()
            val lower = level.getBlockState(below)
            if (lower.`is`(this) && lower.getValue(HALF) == DoubleBlockHalf.LOWER) {
                level.setBlock(below, Blocks.AIR.defaultBlockState(), UPDATE_ALL or UPDATE_SUPPRESS_DROPS)
            }
        }
        return super.playerWillDestroy(level, pos, state, player)
    }

    override fun rotate(state: BlockState, rotation: Rotation): BlockState =
        state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape {
        val shapes = if (state.getValue(HALF) == DoubleBlockHalf.LOWER) LOWER_SHAPES else UPPER_SHAPES
        return shapes.getValue(state.getValue(FACING))
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? =
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER) DryingRackBlockEntity(pos, state) else null

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? =
        if (level.isClientSide) null
        else createTickerHelper(type, DryingRack.ENTITY) { tickLevel, pos, _, rack ->
            (tickLevel as? ServerLevel)?.let { rack.dry(it, pos) }
        }

    /**
     * As a campfire takes food: something that dries is hung, one at a time, at the free spot nearest the click, and
     * anything else is used as it would be anywhere. A client knows what dries from the recipes it was sent. Holding
     * what something dried into gathers the nearest of it that hangs there into the hand, or the inventory once the
     * hand is full.
     */
    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (stack.isEmpty) return InteractionResult.TRY_WITH_EMPTY_HAND
        val rack = rackOf(state, level, pos) ?: return InteractionResult.PASS
        val driedLikeIt = rack.spotHoldingDried(stack, hitResult.location)
        if (driedLikeIt != null) {
            if (!level.isClientSide) gather(rack.takeFrom(driedLikeIt), stack, player)
            return InteractionResult.SUCCESS
        }
        if (level.isClientSide) return if (KnownMachineRecipes.dries(stack, level)) InteractionResult.SUCCESS else InteractionResult.PASS
        if (!rack.dries(stack)) return InteractionResult.PASS
        val spot = rack.spotToHang(hitResult.location) ?: return InteractionResult.SUCCESS
        rack.setItem(spot, stack.consumeAndReturn(1, player))
        return InteractionResult.SUCCESS
    }

    /** [taken] onto [held], which it is like, or into [player]'s inventory where [held] is full. */
    private fun gather(taken: ItemStack, held: ItemStack, player: Player) {
        if (held.count < held.maxStackSize) {
            held.grow(taken.count)
        } else {
            player.inventory.placeItemBackInInventory(taken, Prediction.SERVER_ONLY)
        }
    }

    /** An empty hand takes back what hangs nearest the click, dried or not. */
    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        val rack = rackOf(state, level, pos) ?: return InteractionResult.PASS
        val spot = rack.spotToTakeFrom(hitResult.location) ?: return InteractionResult.PASS
        if (level.isClientSide) return InteractionResult.SUCCESS
        player.inventory.placeItemBackInInventory(rack.takeFrom(spot), Prediction.SERVER_ONLY)
        return InteractionResult.SUCCESS
    }

    /** What a hopper at either half reaches: the lower half's inventory. */
    override fun getContainer(state: BlockState, level: LevelAccessor, pos: BlockPos): WorldlyContainer =
        rackOf(state, level, pos) ?: NO_CONTAINER

    private fun rackOf(state: BlockState, level: BlockGetter, pos: BlockPos): DryingRackBlockEntity? {
        val lower = if (state.getValue(HALF) == DoubleBlockHalf.UPPER) pos.below() else pos
        return level.getBlockEntity(lower) as? DryingRackBlockEntity
    }

    companion object {
        val HALF: EnumProperty<DoubleBlockHalf> = BlockStateProperties.DOUBLE_BLOCK_HALF
        val FACING: EnumProperty<Direction> = HorizontalDirectionalBlock.FACING

        /** The base, and the ladder over it, facing north as the model does: its front legs at the north edge. */
        private val LOWER_SHAPES: Map<Direction, VoxelShape> =
            Shapes.rotateHorizontal(Shapes.or(box(0.0, 0.0, 0.0, 16.0, 2.0, 16.0), box(1.0, 2.0, 2.0, 15.0, 16.0, 14.0)))

        /** The ladder's top, leaning back toward its props. */
        private val UPPER_SHAPES: Map<Direction, VoxelShape> = Shapes.rotateHorizontal(box(1.0, 0.0, 3.0, 15.0, 13.0, 9.0))

        /** What a hopper finds at a half whose lower half is somehow gone: nothing it can put in or take out. */
        private val NO_CONTAINER = SimpleContainerWithoutSlots()
    }
}

/** A container with no slots at all, for a hopper to find nothing in. */
private class SimpleContainerWithoutSlots : SimpleContainer(0), WorldlyContainer {
    override fun getSlotsForFace(direction: Direction): IntArray = IntArray(0)

    override fun canPlaceItemThroughFace(slot: Int, itemStack: ItemStack, direction: Direction?): Boolean = false

    override fun canTakeItemThroughFace(slot: Int, itemStack: ItemStack, direction: Direction): Boolean = false
}

class DryingRackBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(DryingRack.ENTITY, pos, state), WorldlyContainer {

    private val items: NonNullList<ItemStack> = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY)

    /** How long each input's next item has been drying, in ticks watched. */
    private val dried = IntArray(INPUT_SLOTS)

    private val recipes: RecipeManager.CachedCheck<SingleRecipeInput, DryingRecipe> = RecipeManager.createCheck(Drying.TYPE)

    /** Which skies this rack's Age has, worked out once: an Age's recipe never changes. */
    private var skiesHere: Set<DryingSky>? = null

    /** Whether this rack has had its upper half, so that losing it takes the rack down. */
    var standsTall: Boolean = false
        private set

    fun stoodTall() {
        standsTall = true
        setChanged()
    }

    fun dry(level: ServerLevel, pos: BlockPos) {
        if ((level.gameTime + pos.asLong()) % LOOKS_EVERY != 0L) return
        if (!standsTall) standTall(level, pos)
        if (!items[OLD_OUTPUT].isEmpty) dropTheOldOutput(level, pos)
        if (isWet(level, pos)) return startAgain()
        val skies = skiesHere(level)
        val dryingNow = (0..<INPUT_SLOTS).filter { canDryNow(it, level, skies) }
        if (dryingNow.isEmpty()) return
        showDripping(level, pos, dryingNow)
        var anyFinished = false
        for (slot in dryingNow) {
            dried[slot] = (dried[slot] + LOOKS_EVERY.toInt()).coerceAtMost(DRY_TIME)
            if (dried[slot] >= DRY_TIME && finish(slot, level)) anyFinished = true
        }
        if (anyFinished) itemsChanged() else setChanged()
    }

    /**
     * A rack placed before the rack stood two blocks tall, given its upper half as soon as there is room for it; one
     * with its upper half already there is only noted as standing tall.
     */
    private fun standTall(level: ServerLevel, pos: BlockPos) {
        val above = pos.above()
        val upper = blockState.setValue(DryingRackBlock.HALF, DoubleBlockHalf.UPPER)
        val aboveState = level.getBlockState(above)
        val hasItsUpperHalf = aboveState.`is`(blockState.block) && aboveState.getValue(DryingRackBlock.HALF) == DoubleBlockHalf.UPPER
        val hasRoom = aboveState.canBeReplaced() && above.y < level.maxY
        if (!hasItsUpperHalf && !hasRoom) return
        if (!hasItsUpperHalf) level.setBlock(above, upper, Block.UPDATE_ALL)
        stoodTall()
    }

    /** What changed is what the rack holds, which clients near it draw hanging from its rungs. */
    private fun itemsChanged() {
        setChanged()
        val level = level ?: return
        if (!level.isClientSide) level.sendBlockUpdated(blockPos, blockState, blockState, Block.UPDATE_CLIENTS)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> = ClientboundBlockEntityDataPacket.create(this)

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag = saveCustomOnly(registries)

    private fun skiesHere(level: ServerLevel): Set<DryingSky> = skiesHere ?: skiesOf(level).also { skiesHere = it }

    /** Whether the item in [slot] dries under one of [skies]: not where it is already what it dried into. */
    private fun canDryNow(slot: Int, level: ServerLevel, skies: Set<DryingSky>): Boolean {
        val stack = items[slot]
        if (stack.isEmpty || isDone(slot)) return false
        val recipe = recipeFor(stack, level)?.value() ?: return false
        return recipe.driesUnder(skies)
    }

    /** Whether the item in [slot] is what something dried into, waiting to be taken. */
    fun isDone(slot: Int): Boolean = dried[slot] == DONE

    /** The free spot nearest a click landing at [clicked], or none where the rack is full. */
    fun spotToHang(clicked: Vec3): Int? = nearest(clicked) { items[it].isEmpty }

    /** The spot nearest [clicked] holding something dried just like [like], or none where nothing like it has dried. */
    fun spotHoldingDried(like: ItemStack, clicked: Vec3): Int? =
        nearest(clicked) { slot -> isDone(slot) && ItemStack.isSameItemSameComponents(items[slot], like) }

    /** The spot holding something nearest a click landing at [clicked], or none where nothing hangs. */
    fun spotToTakeFrom(clicked: Vec3): Int? = nearest(clicked) { !items[it].isEmpty }

    /** Of the spots that are [wanted], the one nearest [clicked] across the face of the rack. */
    private fun nearest(clicked: Vec3, wanted: (Int) -> Boolean): Int? {
        val facing = blockState.getValue(DryingRackBlock.FACING)
        val atTheModel = DryingRackHangers.toModel(blockPos, facing, clicked)
        fun distanceTo(slot: Int): Double {
            val spot = DryingRackHangers.spotOf(slot)
            return Vec3(spot.across, spot.up, 0.0).distanceToSqr(atTheModel.x, atTheModel.y, 0.0)
        }
        return (0..<INPUT_SLOTS).filter(wanted).minByOrNull(::distanceTo)
    }

    /** What hangs at [slot], taken off by hand. */
    fun takeFrom(slot: Int): ItemStack = removeItem(slot, 1)

    /**
     * What a rack placed before the rack hung its items held in its output, dropped where it stands: the output is
     * gone, and what dries now waits on its rung.
     */
    private fun dropTheOldOutput(level: ServerLevel, pos: BlockPos) {
        Containers.dropItemStack(level, pos.x + MIDDLE, pos.y + 1.0, pos.z + MIDDLE, items[OLD_OUTPUT])
        items[OLD_OUTPUT] = ItemStack.EMPTY
        itemsChanged()
    }

    /** Now and then a drop from under each item in [drying], somewhere along its lower edge, each in its own time. */
    private fun showDripping(level: ServerLevel, pos: BlockPos, drying: List<Int>) {
        val facing = blockState.getValue(DryingRackBlock.FACING)
        for (slot in drying) {
            if (level.random.nextInt(LOOKS_A_DRIP) != 0) continue
            val middle = DryingRackHangers.inWorld(pos, facing, slot)
            val across = (level.random.nextDouble() - MIDDLE) * ITEM_WIDTH
            val underneath = middle.add(0.0, -DryingRackHangers.HALF_AN_ITEM / DryingRackHangers.UNITS_PER_BLOCK, 0.0)
            val alongTheEdge = Vec3(across, 0.0, 0.0).yRot(-Math.toRadians(DryingRackHangers.clockwiseTurnOf(facing)).toFloat())
            val drop = underneath.add(alongTheEdge)
            level.sendParticles(ParticleTypes.DRIPPING_WATER, drop.x, drop.y, drop.z, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    /** Vanilla's failed taming, as [stack] arrives: it will never dry under this sky. */
    private fun warnIfThisSkyRefuses(stack: ItemStack) {
        val serverLevel = level as? ServerLevel ?: return
        if (stack.isEmpty) return
        val recipe = recipeFor(stack, serverLevel)?.value() ?: return
        if (recipe.driesUnder(skiesHere(serverLevel))) return
        val pos = blockPos
        serverLevel.sendParticles(ParticleTypes.SMOKE, pos.x + MIDDLE, pos.y + 1.0, pos.z + MIDDLE, PUFFS, PUFF_SPREAD, PUFF_SPREAD, PUFF_SPREAD, PUFF_SPEED)
    }

    /** Done: the item replaced where it hangs by what it dried into, and whether it was. */
    private fun finish(slot: Int, level: ServerLevel): Boolean {
        val stack = items[slot]
        val made = recipeFor(stack, level)?.value()?.assemble(SingleRecipeInput(stack)) ?: return false
        items[slot] = made
        dried[slot] = DONE
        return true
    }

    /** Rain falling on it, above its upper half where it has one, or water against any face. */
    private fun isWet(level: ServerLevel, pos: BlockPos): Boolean {
        val top = if (standsTall) pos.above() else pos
        val isRainedOn = level.isRainingAt(top.above())
        val isBesideWater = Direction.entries.any { level.getFluidState(pos.relative(it)).`is`(FluidTags.WATER) }
        return isRainedOn || isBesideWater
    }

    /** Every item still drying back to nothing; what is already dried stays so. */
    private fun startAgain() {
        if (dried.any { it > 0 }) setChanged()
        for (slot in dried.indices) if (dried[slot] > 0) dried[slot] = 0
    }

    private fun skiesOf(level: ServerLevel): Set<DryingSky> {
        val recipe = Ages.recipeOf(level) ?: return emptySet()
        val sky = AgeGeneration.skySpec(recipe)
        return DryingSky.entries.filter { it.isMetBy(recipe, sky) }.toSet()
    }

    private fun recipeFor(stack: ItemStack, level: ServerLevel): RecipeHolder<DryingRecipe>? =
        recipes.getRecipeFor(SingleRecipeInput(stack), level).orElse(null)

    fun dries(stack: ItemStack): Boolean {
        val serverLevel = level as? ServerLevel ?: return false
        return recipeFor(stack, serverLevel) != null
    }

    override fun getContainerSize(): Int = SLOT_COUNT

    override fun isEmpty(): Boolean = items.all { it.isEmpty }

    override fun getItem(slot: Int): ItemStack = items[slot]

    override fun removeItem(slot: Int, count: Int): ItemStack {
        val taken = ContainerHelper.removeItem(items, slot, count)
        if (slot < INPUT_SLOTS && items[slot].isEmpty) dried[slot] = 0
        if (!taken.isEmpty) itemsChanged()
        return taken
    }

    override fun removeItemNoUpdate(slot: Int): ItemStack = ContainerHelper.takeItem(items, slot)

    /** Something else hung in a spot starts it again; the same thing back, as a hopper's returning, does not. */
    override fun setItem(slot: Int, itemStack: ItemStack) {
        val isSomethingElse = !ItemStack.isSameItemSameComponents(items[slot], itemStack)
        items[slot] = itemStack
        itemStack.limitSize(getMaxStackSize(itemStack))
        if (slot < INPUT_SLOTS && isSomethingElse) {
            dried[slot] = 0
            warnIfThisSkyRefuses(itemStack)
        }
        itemsChanged()
    }


    override fun stillValid(player: Player): Boolean = Container.stillValidBlockEntity(this, player)

    override fun clearContent() {
        items.clear()
        dried.fill(0)
    }

    /** One thing to a spot, as a campfire takes one to each of its sides. */
    override fun getMaxStackSize(): Int = 1

    /** Only what dries is hung, and only on the rungs. */
    override fun canPlaceItem(slot: Int, itemStack: ItemStack): Boolean = slot < INPUT_SLOTS && dries(itemStack)

    override fun getSlotsForFace(direction: Direction): IntArray = INPUT_SLOT_INDICES

    override fun canPlaceItemThroughFace(slot: Int, itemStack: ItemStack, direction: Direction?): Boolean =
        direction != Direction.DOWN && canPlaceItem(slot, itemStack)

    /** Only what is dried comes out, and only underneath. */
    override fun canTakeItemThroughFace(slot: Int, itemStack: ItemStack, direction: Direction): Boolean =
        direction == Direction.DOWN && slot < INPUT_SLOTS && isDone(slot)

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items.clear()
        ContainerHelper.loadAllItems(input, items)
        dried.fill(0)
        input.getIntArray(DRIED_KEY).ifPresent { saved -> saved.copyInto(dried, endIndex = minOf(saved.size, INPUT_SLOTS)) }
        standsTall = input.getBooleanOr(STANDS_TALL_KEY, false)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, items)
        output.putIntArray(DRIED_KEY, dried)
        output.putBoolean(STANDS_TALL_KEY, standsTall)
    }

    companion object {
        const val INPUT_SLOTS = 6

        /**
         * Where a rack placed before the rack hung its items kept what it had dried, kept in the count so that loading
         * one finds it, and emptied onto the ground on its first look.
         */
        private const val OLD_OUTPUT = INPUT_SLOTS
        const val SLOT_COUNT = INPUT_SLOTS + 1

        /** What [dried] holds for a spot whose item has dried. */
        private const val DONE = -1

        /** Twice a furnace's 200 ticks an item (Jonah). */
        const val DRY_TIME = 400

        private val INPUT_SLOT_INDICES = IntArray(INPUT_SLOTS) { it }

        /** Often enough that the arrow moves smoothly. */
        private const val LOOKS_EVERY = 4L

        /** One look in this many drips from a drying item, on average, each item drawn for alone. */
        private const val LOOKS_A_DRIP = 3

        private const val MIDDLE = 0.5

        /** Six pixels: what a drop may fall from anywhere along. */
        private const val ITEM_WIDTH = 6.0 / 16.0
        private const val PUFFS = 7
        private const val PUFF_SPREAD = 0.3
        private const val PUFF_SPEED = 0.02

        private const val DRIED_KEY = "dried"
        private const val STANDS_TALL_KEY = "stands_tall"
    }
}

/** The rack's registrations, for [co.voik.agesandtheart.content.AgeContent]'s lists. */
object DryingRack {
    val ID: Identifier = "drying_rack".location()

    private const val STRENGTH = 2.0f

    val BLOCK: DryingRackBlock = DryingRackBlock(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, ID))
            .mapColor(MapColor.WOOD)
            .strength(STRENGTH)
            .sound(SoundType.WOOD)
            .ignitedByLava(),
    )

    val ITEM: Item = BlockItem(BLOCK, Item.Properties().setId(ResourceKey.create(Registries.ITEM, ID)).useBlockDescriptionPrefix())

    val ENTITY: BlockEntityType<DryingRackBlockEntity> =
        BlockEntityType({ pos, state -> DryingRackBlockEntity(pos, state) }, setOf(BLOCK))

}
