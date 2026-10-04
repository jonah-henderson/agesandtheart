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
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.MenuType
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
 * The drying rack (design §7.1.2): the masterwork grades' last step, curing or drying under a sky only a
 * written Age has. Six inputs and an output, as a furnace has, worked one item at a time; hoppers in from
 * above and the sides and out from below.
 *
 * **Out of its sky an item waits; wet, it starts again**: rain on the rack or water beside it puts the item
 * being dried back to nothing, while anything short of the Age simply pauses. Time is counted in ticks the
 * rack has watched, so nothing dries unloaded.
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
        MachineRecipeLists.open(player, rack)
        return InteractionResult.CONSUME
    }
}

class DryingRackBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(DryingRack.ENTITY, pos, state), WorldlyContainer, MenuProvider {

    private val items: NonNullList<ItemStack> = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY)

    /** The input being dried, or [NOTHING_DRYING]. */
    private var drying = NOTHING_DRYING

    /** How long it has been drying, in ticks watched. */
    private var dried = 0

    private val recipes: RecipeManager.CachedCheck<SingleRecipeInput, DryingRecipe> = RecipeManager.createCheck(Drying.TYPE)

    /** Which skies this rack's Age has, worked out once: an Age's recipe never changes. */
    private var skiesHere: Set<DryingSky>? = null

    fun dry(level: ServerLevel, pos: BlockPos) {
        if ((level.gameTime + pos.asLong()) % LOOKS_EVERY != 0L) return
        if (isWet(level, pos)) return startAgain()
        val skies = skiesHere ?: skiesOf(level).also { skiesHere = it }
        val slot = (0..<INPUT_SLOTS).firstOrNull { canDryNow(it, level, skies) }
        if (slot == null) return
        if (slot != drying) {
            drying = slot
            dried = 0
        }
        dried += LOOKS_EVERY.toInt()
        if (dried >= DRY_TIME) finish(slot, level)
        setChanged()
    }

    /** Whether the input in [slot] dries under one of [skies], with room in the output for what it makes. */
    private fun canDryNow(slot: Int, level: ServerLevel, skies: Set<DryingSky>): Boolean {
        val stack = items[slot]
        if (stack.isEmpty) return false
        val recipe = recipeFor(stack, level)?.value() ?: return false
        return recipe.driesUnder(skies) && outputHasRoomFor(recipe.assemble(SingleRecipeInput(stack)))
    }

    private fun outputHasRoomFor(made: ItemStack): Boolean {
        val output = items[OUTPUT]
        val stacksOn = ItemStack.isSameItemSameComponents(output, made) && output.count + made.count <= output.maxStackSize
        return output.isEmpty || stacksOn
    }

    private fun finish(slot: Int, level: ServerLevel) {
        val stack = items[slot]
        val made = recipeFor(stack, level)?.value()?.assemble(SingleRecipeInput(stack)) ?: return
        if (items[OUTPUT].isEmpty) items[OUTPUT] = made else items[OUTPUT].grow(made.count)
        stack.shrink(1)
        drying = NOTHING_DRYING
        dried = 0
    }

    /** Rain falling on it, or water against any face. */
    private fun isWet(level: ServerLevel, pos: BlockPos): Boolean {
        val isRainedOn = level.isRainingAt(pos.above())
        val isBesideWater = Direction.entries.any { level.getFluidState(pos.relative(it)).`is`(FluidTags.WATER) }
        return isRainedOn || isBesideWater
    }

    private fun startAgain() {
        if (dried > 0) setChanged()
        dried = 0
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

    override fun getContainerSize(): Int = SLOT_COUNT

    override fun isEmpty(): Boolean = items.all { it.isEmpty }

    override fun getItem(slot: Int): ItemStack = items[slot]

    override fun removeItem(slot: Int, count: Int): ItemStack =
        ContainerHelper.removeItem(items, slot, count).also { if (!it.isEmpty) setChanged() }

    override fun removeItemNoUpdate(slot: Int): ItemStack = ContainerHelper.takeItem(items, slot)

    /** Something else put in the place being dried starts it again; more of the same does not. */
    override fun setItem(slot: Int, itemStack: ItemStack) {
        val isSomethingElse = !ItemStack.isSameItemSameComponents(items[slot], itemStack)
        items[slot] = itemStack
        itemStack.limitSize(getMaxStackSize(itemStack))
        if (slot == drying && isSomethingElse) dried = 0
        setChanged()
    }

    override fun getDisplayName(): Component = blockState.block.name

    override fun createMenu(containerId: Int, inventory: Inventory, player: Player): AbstractContainerMenu =
        DryingRackMenu(containerId, inventory, this, progress)

    /** Read live, so it needs no syncing of its own. */
    private val progress = object : ContainerData {
        override fun get(index: Int): Int = if (index == DryingRackMenu.DRIED) dried else DRY_TIME

        override fun set(index: Int, value: Int) = Unit

        override fun getCount(): Int = DryingRackMenu.DATA_COUNT
    }

    override fun stillValid(player: Player): Boolean = Container.stillValidBlockEntity(this, player)

    override fun clearContent() {
        items.clear()
        drying = NOTHING_DRYING
        dried = 0
    }

    /** Only what dries goes in, and never into the output. */
    override fun canPlaceItem(slot: Int, itemStack: ItemStack): Boolean = slot != OUTPUT && dries(itemStack)

    override fun getSlotsForFace(direction: Direction): IntArray = if (direction == Direction.DOWN) OUTPUT_SLOTS else INPUT_SLOT_INDICES

    override fun canPlaceItemThroughFace(slot: Int, itemStack: ItemStack, direction: Direction?): Boolean =
        direction != Direction.DOWN && canPlaceItem(slot, itemStack)

    /** Only what is finished comes out, and only underneath. */
    override fun canTakeItemThroughFace(slot: Int, itemStack: ItemStack, direction: Direction): Boolean =
        direction == Direction.DOWN && slot == OUTPUT

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        items.clear()
        ContainerHelper.loadAllItems(input, items)
        drying = input.getIntOr(DRYING_KEY, NOTHING_DRYING)
        dried = input.getIntOr(DRIED_KEY, 0)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        ContainerHelper.saveAllItems(output, items)
        output.putInt(DRYING_KEY, drying)
        output.putInt(DRIED_KEY, dried)
    }

    companion object {
        const val INPUT_SLOTS = 6
        const val OUTPUT = INPUT_SLOTS
        const val SLOT_COUNT = INPUT_SLOTS + 1

        /** Twice a furnace's 200 ticks an item (Jonah). */
        const val DRY_TIME = 400

        private val INPUT_SLOT_INDICES = IntArray(INPUT_SLOTS) { it }
        private val OUTPUT_SLOTS = intArrayOf(OUTPUT)

        /** Often enough that the arrow moves smoothly. */
        private const val LOOKS_EVERY = 4L

        private const val NOTHING_DRYING = -1

        private const val DRYING_KEY = "drying"
        private const val DRIED_KEY = "dried"
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

    val MENU: MenuType<DryingRackMenu> = MenuType(
        { containerId, inventory -> DryingRackMenu(containerId, inventory) },
        FeatureFlags.VANILLA_SET,
    )
}
