package co.voik.agesandtheart.station

import co.voik.agesandtheart.age.reward.DryingSky
import co.voik.agesandtheart.generation.AgeGeneration
import co.voik.agesandtheart.generation.Ages
import co.voik.agesandtheart.location
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.NonNullList
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.InteractionResult
import net.minecraft.world.MenuProvider
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.HopperMenu
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

/**
 * The drying rack (design §7.1.2): the masterwork grades' last step, a day's curing or drying under a sky
 * only a written Age has. Five places, a hopper's screen, and hoppers in from above and the sides and out
 * from below, so a clever enough writer can keep one fed.
 *
 * **Out of its sky an item waits; wet, it starts again** (Jonah): rain on the rack or water beside it puts
 * everything back to nothing, while anything short of the Age simply pauses. Time is counted in ticks the
 * rack has watched, as the advanced analysis machine's studies are, so nothing dries unloaded.
 */
class DryingRackBlock(properties: Properties) : BaseEntityBlock(properties) {

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = DryingRackBlockEntity(pos, state)

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

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hitResult: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) return InteractionResult.SUCCESS
        val rack = level.getBlockEntity(pos) as? DryingRackBlockEntity ?: return InteractionResult.FAIL
        player.openMenu(rack)
        return InteractionResult.CONSUME
    }
}

class DryingRackBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(DryingRack.ENTITY, pos, state), WorldlyContainer, MenuProvider {

    private val items: NonNullList<ItemStack> = NonNullList.withSize(SLOTS, ItemStack.EMPTY)

    /** How long each place has been drying, in ticks watched. */
    private val dried = IntArray(SLOTS)

    private val recipes: RecipeManager.CachedCheck<SingleRecipeInput, DryingRecipe> = RecipeManager.createCheck(Drying.TYPE)

    /** Which skies this rack's Age has, worked out once: an Age's recipe never changes. */
    private var skiesHere: Set<DryingSky>? = null

    fun dry(level: ServerLevel, pos: BlockPos) {
        if ((level.gameTime + pos.asLong()) % LOOKS_EVERY != 0L) return
        if (isWet(level, pos)) return startAgain()
        val skies = skiesHere ?: skiesOf(level).also { skiesHere = it }
        var changed = false
        for (slot in 0..<SLOTS) {
            val stack = items[slot]
            if (stack.isEmpty) continue
            val recipe = recipeFor(stack, level)?.value() ?: continue
            if (recipe.under !in skies) continue
            dried[slot] += LOOKS_EVERY.toInt()
            if (dried[slot] >= A_DAY) {
                items[slot] = recipe.assemble(SingleRecipeInput(stack)).copyWithCount(stack.count)
                dried[slot] = 0
            }
            changed = true
        }
        if (changed) setChanged()
    }

    /** Rain falling on it, or water against any face. */
    private fun isWet(level: ServerLevel, pos: BlockPos): Boolean {
        val isRainedOn = level.isRainingAt(pos.above())
        val isBesideWater = Direction.entries.any { level.getFluidState(pos.relative(it)).`is`(FluidTags.WATER) }
        return isRainedOn || isBesideWater
    }

    private fun startAgain() {
        if (dried.any { it > 0 }) setChanged()
        dried.fill(0)
    }

    private fun skiesOf(level: ServerLevel): Set<DryingSky> {
        val recipe = Ages.recipeOf(level) ?: return emptySet()
        val sky = AgeGeneration.skySpec(recipe)
        return DryingSky.entries.filter { it.isMetBy(recipe, sky) }.toSet()
    }

    private fun recipeFor(stack: ItemStack, level: ServerLevel): RecipeHolder<DryingRecipe>? =
        recipes.getRecipeFor(SingleRecipeInput(stack), level).orElse(null)

    private fun dries(stack: ItemStack): Boolean {
        val serverLevel = level as? ServerLevel ?: return false
        return recipeFor(stack, serverLevel) != null
    }

    override fun getContainerSize(): Int = SLOTS

    override fun isEmpty(): Boolean = items.all { it.isEmpty }

    override fun getItem(slot: Int): ItemStack = items[slot]

    override fun removeItem(slot: Int, count: Int): ItemStack =
        ContainerHelper.removeItem(items, slot, count).also {
            if (items[slot].isEmpty) dried[slot] = 0
            if (!it.isEmpty) setChanged()
        }

    override fun removeItemNoUpdate(slot: Int): ItemStack = ContainerHelper.takeItem(items, slot).also { dried[slot] = 0 }

    /** Something else put in a place starts that place again; more of the same does not. */
    override fun setItem(slot: Int, itemStack: ItemStack) {
        val isSomethingElse = !ItemStack.isSameItemSameComponents(items[slot], itemStack)
        items[slot] = itemStack
        itemStack.limitSize(getMaxStackSize(itemStack))
        if (isSomethingElse) dried[slot] = 0
        setChanged()
    }

    override fun getDisplayName(): Component = blockState.block.name

    override fun createMenu(containerId: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
        HopperMenu(containerId, inventory, this)

    override fun stillValid(player: Player): Boolean = Container.stillValidBlockEntity(this, player)

    override fun clearContent() {
        items.clear()
        dried.fill(0)
    }

    override fun getSlotsForFace(direction: Direction): IntArray = ALL_SLOTS

    /** Only what dries goes in by hopper, and only into an empty place. */
    override fun canPlaceItemThroughFace(slot: Int, itemStack: ItemStack, direction: Direction?): Boolean =
        direction != Direction.DOWN && items[slot].isEmpty && dries(itemStack)

    /** Only what is finished comes out underneath: whatever no longer has drying to do. */
    override fun canTakeItemThroughFace(slot: Int, itemStack: ItemStack, direction: Direction): Boolean =
        direction == Direction.DOWN && !dries(itemStack)

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items.clear()
        ContainerHelper.loadAllItems(input, items)
        val saved = input.getIntArray(DRIED_KEY).orElse(IntArray(SLOTS))
        for (slot in 0..<SLOTS) dried[slot] = saved.getOrElse(slot) { 0 }
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, items)
        output.putIntArray(DRIED_KEY, dried)
    }

    private companion object {
        /** A hopper's five, so a hopper's screen serves. */
        const val SLOTS = HopperMenu.CONTAINER_SIZE

        val ALL_SLOTS = IntArray(SLOTS) { it }

        /** A full day (Jonah): the ink cures, the paper dries, as slowly as the real ones do. */
        const val A_DAY = 24_000

        const val LOOKS_EVERY = 20L

        const val DRIED_KEY = "dried"
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
