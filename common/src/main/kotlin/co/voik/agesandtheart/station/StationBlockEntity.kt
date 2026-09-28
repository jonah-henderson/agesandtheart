package co.voik.agesandtheart.station

import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.MenuProvider
import net.minecraft.network.chat.Component
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.NonNullList
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.item.crafting.RecipeManager
import net.minecraft.world.item.crafting.SingleRecipeInput
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * A station's two slots, and how far through a run it is.
 *
 * **A furnace without the fuel**: a screen with an input and a result ([StationMenu]), and hoppers feed the
 * top and sides and take from the bottom, as a furnace's do.
 */
class StationBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(AgeContent.STATION_ENTITY, pos, state), WorldlyContainer, MenuProvider {

    val station: Station = (state.block as? StationBlock)?.station ?: error("A station entity on ${state.block}")

    private val items: NonNullList<ItemStack> = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY)

    private var progress = 0

    private val recipes: RecipeManager.CachedCheck<SingleRecipeInput, StationRecipe> =
        RecipeManager.createCheck(StationRecipes.typeFor(station))

    val inputStack: ItemStack get() = items[INPUT]
    val outputStack: ItemStack get() = items[OUTPUT]

    fun accepts(stack: ItemStack): Boolean {
        val serverLevel = level as? ServerLevel ?: return false
        return !stack.isEmpty && recipeFor(stack, serverLevel) != null
    }

    /** One tick of work, answering what the station is doing now. */
    fun work(level: ServerLevel): StationActivity {
        val recipe = if (inputStack.isEmpty) null else recipeFor(inputStack, level)
        if (recipe == null) {
            progress = 0
            return StationActivity.IDLE
        }
        val result = recipe.value().assemble(SingleRecipeInput(inputStack))
        val isMissingANeed = station.missingNeeds { face -> level.getBlockState(blockPos.relative(face)) }.isNotEmpty()
        if (isMissingANeed || !outputHasRoomFor(result)) return StationActivity.STALLED
        progress++
        if (progress >= station.workTicks) finishRun(level, result)
        setChanged()
        return StationActivity.WORKING
    }

    private fun outputHasRoomFor(result: ItemStack): Boolean {
        val isSameItem = ItemStack.isSameItemSameComponents(outputStack, result)
        val fits = outputStack.count + result.count <= outputStack.maxStackSize
        return outputStack.isEmpty || (isSameItem && fits)
    }

    private fun finishRun(level: ServerLevel, result: ItemStack) {
        progress = 0
        inputStack.shrink(1)
        if (outputStack.isEmpty) items[OUTPUT] = result else outputStack.grow(result.count)
        station.spendNeeds(level, blockPos)
        level.playSound(null, blockPos, station.finishSound, SoundSource.BLOCKS, FINISH_VOLUME, FINISH_PITCH)
    }

    private fun recipeFor(stack: ItemStack, level: ServerLevel): RecipeHolder<StationRecipe>? =
        recipes.getRecipeFor(SingleRecipeInput(stack), level).orElse(null)

    override fun getContainerSize(): Int = SLOT_COUNT

    override fun isEmpty(): Boolean = items.all { it.isEmpty }

    override fun getItem(slot: Int): ItemStack = items[slot]

    override fun removeItem(slot: Int, count: Int): ItemStack =
        ContainerHelper.removeItem(items, slot, count).also { if (!it.isEmpty) setChanged() }

    override fun removeItemNoUpdate(slot: Int): ItemStack = ContainerHelper.takeItem(items, slot)

    /** A different input starts the run again; more of the same one does not, as in a furnace. */
    override fun setItem(slot: Int, itemStack: ItemStack) {
        val isAnotherInput = slot == INPUT && !ItemStack.isSameItemSameComponents(items[INPUT], itemStack)
        items[slot] = itemStack
        itemStack.limitSize(getMaxStackSize(itemStack))
        if (isAnotherInput) progress = 0
        setChanged()
    }

    override fun getDisplayName(): Component = blockState.block.name

    override fun createMenu(containerId: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
        StationMenu(containerId, inventory, this, runSoFar)

    /** What the screen's arrow is drawn from. Read live, so it needs no syncing of its own. */
    private val runSoFar = object : ContainerData {
        override fun get(index: Int): Int = when (index) {
            StationMenu.PROGRESS -> progress
            else -> station.workTicks
        }

        override fun set(index: Int, value: Int) {
            if (index == StationMenu.PROGRESS) progress = value
        }

        override fun getCount(): Int = StationMenu.DATA_COUNT
    }

    override fun stillValid(player: Player): Boolean = Container.stillValidBlockEntity(this, player)

    override fun clearContent() {
        items.clear()
    }

    override fun canPlaceItem(slot: Int, itemStack: ItemStack): Boolean = slot == INPUT && accepts(itemStack)

    override fun canTakeItem(into: Container, slot: Int, itemStack: ItemStack): Boolean = slot == OUTPUT

    override fun getSlotsForFace(direction: Direction): IntArray =
        if (direction == Direction.DOWN) SLOTS_FOR_BOTTOM else SLOTS_FOR_TOP_AND_SIDES

    /** A hopper's own insert path passes no direction, and is let through. */
    override fun canPlaceItemThroughFace(slot: Int, itemStack: ItemStack, direction: Direction?): Boolean =
        direction != Direction.DOWN && canPlaceItem(slot, itemStack)

    override fun canTakeItemThroughFace(slot: Int, itemStack: ItemStack, direction: Direction): Boolean =
        direction == Direction.DOWN && slot == OUTPUT

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items.clear()
        ContainerHelper.loadAllItems(input, items)
        progress = input.getIntOr(PROGRESS_KEY, 0)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, items)
        output.putInt(PROGRESS_KEY, progress)
    }

    companion object {
        const val INPUT = 0
        const val OUTPUT = 1
        const val SLOT_COUNT = 2

        private val SLOTS_FOR_BOTTOM = intArrayOf(OUTPUT)
        private val SLOTS_FOR_TOP_AND_SIDES = intArrayOf(INPUT)

        private const val PROGRESS_KEY = "progress"
        private const val FINISH_VOLUME = 0.6f
        private const val FINISH_PITCH = 1.0f
    }
}
