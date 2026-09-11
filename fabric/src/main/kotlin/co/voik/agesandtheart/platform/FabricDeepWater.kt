package co.voik.agesandtheart.platform

import co.voik.agesandtheart.content.AgeFluids
import co.voik.agesandtheart.content.DeepWaterBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.FlowingFluid
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
import net.minecraft.world.entity.InsideBlockEffectApplier
import net.minecraft.world.entity.InsideBlockEffectType

/**
 * Deep water, as a fluid Fabric can register — design §7.1.2.
 *
 * **Water's behaviour, unchanged, and that is deliberate.** The inks are thick and needed every constant
 * retuned; this is water that happens to be under a great deal of itself, so a diver should find it moves,
 * spreads, extinguishes and drowns exactly as water does. Everything that makes it *deep* lives in the
 * block ([DeepWaterBlock]) and in the rules ([co.voik.agesandtheart.content.DeepWater]), where both loaders
 * can share it.
 *
 * **No bucket of its own** — `getBucket` is water's. See `AgeFluids.DEEP_WATER` for why that is a ruling
 * rather than a shortcut.
 *
 * **No service, unlike the inks.** Nothing in common needs the *object*: the rules read a fluid tag and the
 * generator names the block by id, so there is nothing for a `Services` entry to carry.
 */
sealed class FabricDeepWater : FlowingFluid() {

    override fun getBucket(): Item = Items.WATER_BUCKET

    override fun getFlowing(): Fluid = FabricDeepWaterFluids.flowing

    override fun getSource(): Fluid = FabricDeepWaterFluids.still

    /**
     * Never, where vanilla water reads a game rule.
     *
     * Two sources meeting must not make a third: the abyss is deposited by a sea being deep, and the one
     * thing it may never be is manufactured. It cannot be farmed anyway — unsupported deep water settles
     * back to water — but the rule should say so where somebody reading the fluid will see it.
     */
    override fun canConvertToSource(level: ServerLevel): Boolean = false

    override fun beforeDestroyingBlock(level: LevelAccessor, pos: BlockPos, state: BlockState) {
        val blockEntity: BlockEntity? = if (state.hasBlockEntity()) level.getBlockEntity(pos) else null
        Block.dropResources(state, level, pos, blockEntity)
    }

    override fun entityInside(level: Level, pos: BlockPos, entity: Entity, effectApplier: InsideBlockEffectApplier) {
        effectApplier.apply(InsideBlockEffectType.EXTINGUISH)
    }

    override fun getSlopeFindDistance(level: LevelReader): Int = WATERS_OWN_SLOPE

    override fun getDropOff(level: LevelReader): Int = WATERS_OWN_DROP_OFF

    override fun getTickDelay(level: LevelReader): Int = WATERS_OWN_TICK_DELAY

    override fun getExplosionResistance(): Float = EXPLOSION_RESISTANCE

    /**
     * Water's own rule, and it is what keeps the two from fighting along their seam.
     *
     * Deep water is in `#minecraft:water`, so neither will displace the other sideways and an abyss under
     * an ordinary sea is two still bodies touching rather than a boundary that churns forever.
     */
    override fun canBeReplacedWith(
        state: FluidState,
        level: BlockGetter,
        pos: BlockPos,
        other: Fluid,
        direction: Direction,
    ): Boolean = direction == Direction.DOWN && !other.`is`(FluidTags.WATER)

    override fun createLegacyBlock(state: FluidState): BlockState =
        FabricDeepWaterFluids.block.defaultBlockState()
            .setValue(BlockStateProperties.LEVEL, getLegacyLevel(state))

    /**
     * **Any water, not just ours** — which closes the seam an abyss under a sea was drawn with.
     *
     * `FluidRenderer.getHeight` fills a fluid's block to the brim only when the fluid above it is the same
     * one, and otherwise drops it to `getOwnHeight` — about seven eighths. With the identity answer here,
     * deep water under ordinary water was a *different* fluid, so it rendered an eighth of a block short
     * and left a visible horizontal gap between the two bodies (Jonah, walked 2026-09-11).
     *
     * **It is read in exactly two places and both want this answer.** The renderer is one; the other is
     * `FlowingFluid.hasSameAbove`, which decides the fluid's *physical* height — and a block of deep water
     * with a sea on top of it is plainly full.
     *
     * **Nothing about flow moves, because `isSame` is asymmetric here and that is fine.** Every other call
     * in the fluid engine — spreading, levels, `canPassThroughWall` — asks the *neighbour's*
     * implementation with deep water as the argument, and vanilla's water still answers no. What governs
     * the seam is [canBeReplacedWith], which already reads the same tag.
     *
     * One consequence worth knowing: the water above stops drawing its **bottom** face, since that test
     * does route through here. The abyss keeps its own top face, so the surface a diver sees under the sea
     * is still there — it is simply flush now instead of floating an eighth of a block below.
     */
    override fun isSame(fluid: Fluid): Boolean = fluid.`is`(FluidTags.WATER)

    class Source : FabricDeepWater() {
        override fun getAmount(state: FluidState): Int = FULL
        override fun isSource(state: FluidState): Boolean = true
    }

    class Flowing : FabricDeepWater() {
        override fun createFluidStateDefinition(builder: StateDefinition.Builder<Fluid, FluidState>) {
            super.createFluidStateDefinition(builder)
            builder.add(LEVEL)
        }

        override fun getAmount(state: FluidState): Int = state.getValue(LEVEL)
        override fun isSource(state: FluidState): Boolean = false
    }

    private companion object {
        const val FULL = 8
        const val WATERS_OWN_SLOPE = 4
        const val WATERS_OWN_DROP_OFF = 1
        const val WATERS_OWN_TICK_DELAY = 5
        const val EXPLOSION_RESISTANCE = 100.0f
    }
}

/**
 * The registered pair and its block.
 *
 * Built eagerly and wired by id afterwards, exactly as [FabricInkFluids] is and for the same reason: the
 * fluid names its block and the block names its fluid.
 */
object FabricDeepWaterFluids {
    lateinit var still: FabricDeepWater.Source
        private set

    lateinit var flowing: FabricDeepWater.Flowing
        private set

    lateinit var block: LiquidBlock
        private set

    fun register() {
        val identity = AgeFluids.DEEP_WATER
        still = FabricDeepWater.Source()
        flowing = FabricDeepWater.Flowing()
        block = DeepWaterBlock(
            still,
            // **Water's own properties**, so it reads as water to everything that asks a block a question.
            // The map colour is the one departure: black rather than `MapColor.WATER`, since an abyss on a
            // map should not look like the sea over it.
            BlockBehaviour.Properties.of()
                .setId(ResourceKey.create(Registries.BLOCK, identity.block))
                .mapColor(MapColor.COLOR_BLACK)
                .replaceable()
                .noCollision()
                .strength(WORLD_STRENGTH)
                .pushReaction(PushReaction.DESTROY)
                .noLootTable()
                .liquid()
                .sound(SoundType.EMPTY),
        )

        Registry.register(BuiltInRegistries.FLUID, identity.still, still)
        Registry.register(BuiltInRegistries.FLUID, identity.flowing, flowing)
        Registry.register(BuiltInRegistries.BLOCK, identity.block, block)
    }

    /** Water's, so the abyss behaves like any other liquid to a shovel. */
    private const val WORLD_STRENGTH = 100.0f
}
