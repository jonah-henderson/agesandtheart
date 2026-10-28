package co.voik.agesandtheart.station

import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.GrindstoneBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.IntegerProperty

/**
 * How worn a grindstone is by the grinder beside it: [RUNS_A_GRINDSTONE_LASTS] runs, counted, then it breaks.
 *
 * The count is a block state property vanilla's grindstone lacks, added by `StateDefinitionBuilderMixin` as
 * [belongsOn] says. The grindstone's loot table copies it onto the item, as a chipped anvil keeps its damage,
 * and placing it puts it back. Players using the grindstone by hand never wear it.
 *
 * Wear shows on the block as vanilla's mining cracks (`client/WornGrindstones`) and on the item as a
 * durability bar (`GrindstoneBarMixin`).
 */
object GrindstoneWear {

    const val RUNS_A_GRINDSTONE_LASTS = 128

    /** Runs already ground; the run that would reach [RUNS_A_GRINDSTONE_LASTS] breaks it instead. */
    @JvmField
    val WEAR: IntegerProperty = IntegerProperty.create("wear", UNWORN, RUNS_A_GRINDSTONE_LASTS - 1)

    /** Whether a state definition being built belongs to a grindstone. */
    @JvmStatic
    fun belongsOn(owner: Any?): Boolean = owner is GrindstoneBlock

    /** One run's wear on the grindstone at [at], breaking it on its last. */
    fun wearDown(level: ServerLevel, at: BlockPos) {
        val state = level.getBlockState(at)
        if (!state.hasProperty(WEAR)) return
        val worn = state.getValue(WEAR) + 1
        if (worn >= RUNS_A_GRINDSTONE_LASTS) {
            level.destroyBlock(at, false)
        } else {
            level.setBlock(at, state.setValue(WEAR, worn), Block.UPDATE_CLIENTS)
        }
    }

    /** Which of vanilla's ten crack stages [state] shows, or null for a grindstone with no wear. */
    fun crackStageOf(state: BlockState): Int? {
        val worn = state.getValueOrElse(WEAR, UNWORN)
        if (worn == UNWORN) return null
        return worn * CRACK_STAGES / RUNS_A_GRINDSTONE_LASTS
    }

    /** The wear a grindstone item carries, or null for anything else and for one with none. */
    @JvmStatic
    fun wearCarriedBy(stack: ItemStack): Int? {
        if (!stack.`is`(Items.GRINDSTONE)) return null
        val worn = stack.get(DataComponents.BLOCK_STATE)?.get(WEAR) ?: return null
        return worn.takeIf { it > UNWORN }
    }

    /** How long the item's bar is: the share of its runs left, as vanilla draws durability left. */
    @JvmStatic
    fun barWidthFor(worn: Int): Int = Mth.clamp(Math.round(Item.MAX_BAR_WIDTH * shareLeft(worn)), 0, Item.MAX_BAR_WIDTH)

    /** Green to red as the runs go, by vanilla's durability colours. */
    @JvmStatic
    fun barColourFor(worn: Int): Int = Mth.hsvToRgb(shareLeft(worn) / HUE_AT_FULL, 1.0f, 1.0f)

    private fun shareLeft(worn: Int): Float = (RUNS_A_GRINDSTONE_LASTS - worn).toFloat() / RUNS_A_GRINDSTONE_LASTS

    private const val UNWORN = 0

    /** `destroy_stage_0` to `destroy_stage_9`. */
    private const val CRACK_STAGES = 10

    /** Vanilla divides durability left by three, so a whole bar is green and an empty one red. */
    private const val HUE_AT_FULL = 3.0f
}
