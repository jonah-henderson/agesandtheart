package co.voik.agesandtheart.platform

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.content.AgeFluids
import co.voik.agesandtheart.platform.services.InkFluids
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.TagKey
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction

/**
 * Fabric's half of [InkFluids].
 *
 * Everything is built eagerly and registered from the mod's init, the way [co.voik.agesandtheart.content.AgeContent]
 * is — the pieces reference each other (a fluid names its bucket, a bucket names its fluid), so they are
 * constructed first and wired by id afterwards.
 */
class FabricInkFluids : InkFluids {
    /** Fabric counts droplets: 81,000 to the bucket, 27,000 to the bottle — a third, not a quarter. */
    override val unitsPerBucket: Long = FluidConstants.BUCKET
    override val unitsPerBottle: Long = FluidConstants.BOTTLE

    override fun still(tier: InkTier): Fluid = Companion.still(tier)
    override fun flowing(tier: InkTier): Fluid = Companion.flowing(tier)
    override fun bucket(tier: InkTier): Item = Companion.bucket(tier)
    override fun tierOf(fluid: Fluid): InkTier? = byFluid[fluid]

    companion object {
        private val still = mutableMapOf<InkTier, FabricInkFluid.Source>()
        private val flowing = mutableMapOf<InkTier, FabricInkFluid.Flowing>()
        private val blocks = mutableMapOf<InkTier, LiquidBlock>()
        private val buckets = mutableMapOf<InkTier, Item>()
        private val tags = mutableMapOf<InkTier, TagKey<Fluid>>()
        private val byFluid = mutableMapOf<Fluid, InkTier>()

        fun still(tier: InkTier): FabricInkFluid.Source = still.getValue(tier)
        fun flowing(tier: InkTier): FabricInkFluid.Flowing = flowing.getValue(tier)
        fun block(tier: InkTier): LiquidBlock = blocks.getValue(tier)
        fun bucket(tier: InkTier): Item = buckets.getValue(tier)

        /** Each ink is its own tag, so an ink never displaces a different ink. */
        fun tag(tier: InkTier): TagKey<Fluid> = tags.getValue(tier)

        /**
         * Builds and registers all three inks. Called once from mod init, **before** anything asks for a
         * fluid — the accessors throw rather than return a half-built object if that order is broken.
         */
        fun register() {
            for ((tier, identity) in AgeFluids.INKS) {
                tags[tier] = TagKey.create(Registries.FLUID, identity.still)
                val source = FabricInkFluid.Source(tier)
                val flow = FabricInkFluid.Flowing(tier)
                still[tier] = source
                flowing[tier] = flow
                byFluid[source] = tier
                byFluid[flow] = tier

                blocks[tier] = LiquidBlock(
                    source,
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
                buckets[tier] = BucketItem(
                    source,
                    Item.Properties()
                        .setId(ResourceKey.create(Registries.ITEM, identity.bucket))
                        .craftRemainder(Items.BUCKET)
                        .stacksTo(1),
                )

                Registry.register(BuiltInRegistries.FLUID, identity.still, source)
                Registry.register(BuiltInRegistries.FLUID, identity.flowing, flow)
                Registry.register(BuiltInRegistries.BLOCK, identity.block, blocks.getValue(tier))
                Registry.register(BuiltInRegistries.ITEM, identity.bucket, buckets.getValue(tier))
            }
        }

        /** Water's, so an ink pool behaves like any other liquid to a shovel. */
        private const val WORLD_STRENGTH = 100.0f
    }
}
