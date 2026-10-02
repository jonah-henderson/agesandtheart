package co.voik.agesandtheart.station

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.NonNullList
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.Containers
import net.minecraft.world.MenuProvider
import net.minecraft.world.WorldlyContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.item.crafting.RecipeManager
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.HopperBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * The fusion-compounder's four input stacks, and the result they make (design §7.1.2).
 *
 * **The result is worked out, not stored.** Its slot shows what the inputs make while what the recipe
 * needs stands beside the compounder, and taking it spends them — at once, which is the machine's whole
 * offer. Hoppers fill the inputs, and the compounder passes the result down into a container beneath it
 * ([passResultDown]) rather than letting one take it: a hopper puts back what it cannot hold, and the
 * loaders' transfer APIs take a slot's stack without ever asking the container to remove it, so a taken
 * result would be inputs spent for nothing or a result got for free.
 */
class CompounderBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(Compounder.ENTITY, pos, state), WorldlyContainer, MenuProvider {

    private val inputs: NonNullList<ItemStack> = NonNullList.withSize(INPUT_SLOTS, ItemStack.EMPTY)

    private val recipes: RecipeManager.CachedCheck<CompounderInput, CompoundingRecipe> =
        RecipeManager.createCheck(Compounding.TYPE)

    /** The recipe the inputs make, whether or not what it needs is there. */
    private fun recipeForTheInputs(level: ServerLevel): RecipeHolder<CompoundingRecipe>? =
        if (inputs.all { it.isEmpty }) null else recipes.getRecipeFor(CompounderInput(inputs), level).orElse(null)

    /** What [recipe] needs that no face of the compounder has. */
    fun missingNeeds(recipe: CompoundingRecipe): List<CompounderNeed> {
        val level = level ?: return listOf(CompounderNeed.POWER)
        val needed = listOf(CompounderNeed.POWER) + recipe.alsoNeeds
        fun isMetOnSomeFace(need: CompounderNeed) =
            Direction.entries.any { face -> need.isMetBy(level.getBlockState(blockPos.relative(face))) }
        return needed.distinct().filterNot(::isMetOnSomeFace)
    }

    /** What taking the result would give now, or nothing. */
    private fun result(): ItemStack {
        val level = level as? ServerLevel ?: return ItemStack.EMPTY
        val recipe = recipeForTheInputs(level)?.value() ?: return ItemStack.EMPTY
        return if (missingNeeds(recipe).isEmpty()) recipe.assemble(CompounderInput(inputs)) else ItemStack.EMPTY
    }

    /** Spends the inputs and answers what they made, or nothing where they make nothing here. */
    private fun compound(): ItemStack {
        val level = level as? ServerLevel ?: return ItemStack.EMPTY
        val recipe = recipeForTheInputs(level)?.value() ?: return ItemStack.EMPTY
        if (missingNeeds(recipe).isNotEmpty()) return ItemStack.EMPTY
        val input = CompounderInput(inputs)
        val slots = recipe.slotsFor(input) ?: return ItemStack.EMPTY
        val made = recipe.assemble(input)
        for ((ingredient, slot) in recipe.ingredients.zip(slots)) inputs[slot].shrink(ingredient.count)
        setChanged()
        level.playSound(null, blockPos, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.BLOCKS, VOLUME, PITCH)
        return made
    }

    /**
     * On a hopper's beat, compounds into the container beneath — a hopper, a chest — where it has room for
     * the whole result, and does nothing where it has not.
     */
    fun passResultDown(level: ServerLevel) {
        if (level.gameTime % HopperBlockEntity.MOVE_ITEM_SPEED != 0L) return
        val beneath = HopperBlockEntity.getContainerAt(level, blockPos.below()) ?: return
        val made = result()
        if (made.isEmpty || !hasRoomFor(beneath, made)) return
        val left = HopperBlockEntity.addItem(this, beneath, compound(), Direction.UP)
        // Only where the room counted above was not there after all; nothing compounded is ever lost.
        if (!left.isEmpty) Containers.dropItemStack(level, blockPos.x + HALF, blockPos.y + HALF, blockPos.z + HALF, left)
        beneath.setChanged()
    }

    /** Whether [into] can take all of [stack] through its top, as a hopper would put it there. */
    private fun hasRoomFor(into: Container, stack: ItemStack): Boolean {
        val slots = (into as? WorldlyContainer)?.getSlotsForFace(Direction.UP)?.toList() ?: (0..<into.containerSize).toList()
        var room = 0
        for (slot in slots) {
            val isPlaceable = into.canPlaceItem(slot, stack) &&
                (into !is WorldlyContainer || into.canPlaceItemThroughFace(slot, stack, Direction.UP))
            if (!isPlaceable) continue
            val there = into.getItem(slot)
            val mostThere = minOf(into.getMaxStackSize(stack), stack.maxStackSize)
            room += when {
                there.isEmpty -> mostThere
                ItemStack.isSameItemSameComponents(there, stack) -> (mostThere - there.count).coerceAtLeast(0)
                else -> 0
            }
            if (room >= stack.count) return true
        }
        return false
    }

    /**
     * Which needs the screen should name: one bit per [CompounderNeed] the inputs' recipe lacks. Nothing is
     * named while the inputs make nothing.
     */
    private fun missingNeedsAsBits(): Int {
        val level = level as? ServerLevel ?: return 0
        val recipe = recipeForTheInputs(level)?.value() ?: return 0
        return missingNeeds(recipe).fold(0) { bits, need -> bits or (1 shl need.ordinal) }
    }

    override fun getContainerSize(): Int = SLOT_COUNT

    override fun isEmpty(): Boolean = inputs.all { it.isEmpty }

    override fun getItem(slot: Int): ItemStack = if (slot == RESULT) result() else inputs[slot]

    override fun removeItem(slot: Int, count: Int): ItemStack =
        if (slot == RESULT) compound()
        else ContainerHelper.removeItem(inputs, slot, count).also { if (!it.isEmpty) setChanged() }

    override fun removeItemNoUpdate(slot: Int): ItemStack =
        if (slot == RESULT) ItemStack.EMPTY else ContainerHelper.takeItem(inputs, slot)

    override fun setItem(slot: Int, itemStack: ItemStack) {
        if (slot == RESULT) return
        inputs[slot] = itemStack
        itemStack.limitSize(getMaxStackSize(itemStack))
        setChanged()
    }

    override fun getDisplayName(): Component = blockState.block.name

    override fun createMenu(containerId: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
        CompounderMenu(containerId, inventory, this, lacking)

    /** Read live, so it needs no syncing of its own. */
    private val lacking = object : ContainerData {
        override fun get(index: Int): Int = missingNeedsAsBits()

        override fun set(index: Int, value: Int) = Unit

        override fun getCount(): Int = CompounderMenu.DATA_COUNT
    }

    override fun stillValid(player: Player): Boolean = Container.stillValidBlockEntity(this, player)

    override fun clearContent() {
        inputs.clear()
    }

    override fun canPlaceItem(slot: Int, itemStack: ItemStack): Boolean = slot != RESULT

    override fun canTakeItem(into: Container, slot: Int, itemStack: ItemStack): Boolean = false

    override fun getSlotsForFace(direction: Direction): IntArray = INPUT_SLOT_INDICES

    override fun canPlaceItemThroughFace(slot: Int, itemStack: ItemStack, direction: Direction?): Boolean =
        canPlaceItem(slot, itemStack)

    override fun canTakeItemThroughFace(slot: Int, itemStack: ItemStack, direction: Direction): Boolean = false

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        inputs.clear()
        ContainerHelper.loadAllItems(input, inputs)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, inputs)
    }

    companion object {
        const val INPUT_SLOTS = 4
        const val RESULT = INPUT_SLOTS
        const val SLOT_COUNT = INPUT_SLOTS + 1

        private val INPUT_SLOT_INDICES = IntArray(INPUT_SLOTS) { it }

        private const val HALF = 0.5

        private const val VOLUME = 0.8f
        private const val PITCH = 0.6f
    }
}
