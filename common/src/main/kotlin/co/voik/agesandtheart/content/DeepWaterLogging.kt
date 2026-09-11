package co.voik.agesandtheart.content

import co.voik.agesandtheart.Constants
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.SimpleWaterloggedBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.material.FluidState

/**
 * A second waterlogging, for the fluid vanilla's cannot name (design §7.1.2).
 *
 * **Vanilla's waterlogging is water and only water.** `SimpleWaterloggedBlock` gates on the fluid's
 * *identity* — `type == Fluids.WATER`, not the `#minecraft:water` tag — so a shipwreck placed in an abyss
 * comes out with every stair, slab, fence and trapdoor dry, and the wreck reads as full of air pockets.
 * The fluid a waterlogged block reports cannot be made positional either: `BlockStateBase.getFluidState()`
 * takes no position at all, so "waterlogged means deep water *here*" is unsayable and a second property is
 * the only route.
 *
 * **Three seams carry it, rather than the thirty-nine blocks that would otherwise need editing.** Each is
 * a funnel every waterloggable block already passes through, so this reaches modded blocks as well:
 *
 * - [belongsOn] is asked by `StateDefinitionBuilderMixin` as a block builds its state definition.
 * - [drained] is asked by `BlockDefaultStateMixin`, because the default would otherwise come out logged.
 * - [fluidIn] is asked by `BlockStateFluidMixin`, once per state at bootstrap rather than per lookup.
 *
 * **What sets it is ours and needs no seam at all.** `DeepWater.settleTheAbyss` logs what generation left
 * dry and `DeepWaterBlock` logs and drains what changes afterwards, exactly as they already take ordinary
 * water into the abyss and give it back.
 *
 * Measured before it was built (`./gradlew :common:waterlogging`): of vanilla's 29,873 block states, 20,976
 * belong to the 410 waterloggable blocks, so this is 70% more block states — memory and one bit of the
 * global palette, not per-tick work. Every one of those 410 implements `SimpleWaterloggedBlock` and nothing
 * else carries the property, so [belongsOn] is exact rather than approximate.
 */
object DeepWaterLogging {

    /**
     * Set where a block holds the abyss instead of water.
     *
     * Never true at the same time as vanilla's `WATERLOGGED`: the two name the same space and the readers
     * of one know nothing of the other, so what sets this clears that.
     */
    @JvmField
    val DEEP_WATERLOGGED: BooleanProperty = BooleanProperty.create("deep_waterlogged")

    /** Whether a state definition being built belongs to something that can hold the abyss. */
    @JvmStatic
    fun belongsOn(owner: Any?): Boolean = owner is SimpleWaterloggedBlock

    /**
     * [state] with the abyss taken back out, for a block registering its default.
     *
     * **A block's default would otherwise be logged.** `BooleanProperty` orders its values `true, false`
     * and `StateDefinition.any()` hands back the first state, so every waterloggable block would default
     * to full of abyss and place that way anywhere in the world.
     */
    @JvmStatic
    fun drained(state: BlockState): BlockState =
        if (holds(state)) state.setValue(DEEP_WATERLOGGED, false) else state

    /** The fluid a deep-logged [state] reports, or null where it holds nothing of ours. */
    @JvmStatic
    fun fluidIn(state: BlockState): FluidState? = if (holds(state)) source() else null

    /** Whether [state] carries the property and has it set. */
    @JvmStatic
    fun holds(state: BlockState): Boolean =
        state.hasProperty(DEEP_WATERLOGGED) && state.getValue(DEEP_WATERLOGGED)

    /** Whether [state] could hold the abyss and does not yet. */
    fun couldHold(state: BlockState): Boolean =
        state.hasProperty(DEEP_WATERLOGGED) && !state.getValue(DEEP_WATERLOGGED)

    /** [state] holding the abyss, with vanilla's waterlogging cleared so the two cannot both claim it. */
    fun holding(state: BlockState): BlockState = drainedOfWater(state).setValue(DEEP_WATERLOGGED, true)

    /** [state] holding ordinary water again — what a depressurised column leaves behind. */
    fun released(state: BlockState): BlockState {
        val shallow = state.setValue(DEEP_WATERLOGGED, false)
        return if (shallow.hasProperty(WATERLOGGED)) shallow.setValue(WATERLOGGED, true) else shallow
    }

    private fun drainedOfWater(state: BlockState): BlockState =
        if (state.hasProperty(WATERLOGGED)) state.setValue(WATERLOGGED, false) else state

    private val WATERLOGGED = net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED

    /**
     * Bring the cached fluid states into line, where the fluid was registered too late for them.
     *
     * **A state's fluid is a field settled once**, in `BlockStateBase.initCache`, which `Blocks` runs from
     * a static block — so whether the abyss existed by then is a question about loader startup order rather
     * than about anything here. `initCache` is public, which turns that from a seam into a pass: probe one
     * state, and redo them only if the probe says the fluid was missing.
     *
     * Costs nothing where the order was already favourable, and about two hundred milliseconds where it
     * was not (measured over the 20,976 waterloggable states).
     */
    fun settleTheCache() {
        val deep = source() ?: run {
            Constants.LOG.warn("Deep water is not registered; nothing can be logged with it")
            return
        }
        var brought = 0
        for (block in BuiltInRegistries.BLOCK) {
            if (block !is SimpleWaterloggedBlock) continue
            for (state in block.stateDefinition.possibleStates) {
                if (!holds(state) || state.fluidState === deep) continue
                state.initCache()
                brought++
            }
        }
        if (brought > 0) Constants.LOG.info("Deep waterlogging: brought {} block states into line", brought)
    }

    /**
     * The abyss as a source, looked up through the block for the reason `DeepWater.deepWater` records —
     * nothing in common needs the `Fluid` object, and a `LiquidBlock`'s own state carries it.
     */
    private fun source(): FluidState? = BuiltInRegistries.BLOCK
        .getOptional(AgeFluids.DEEP_WATER.block)
        .map { it.defaultBlockState().fluidState }
        .orElse(null)
}
