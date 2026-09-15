package co.voik.agesandtheart.platform

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.content.AgeFluids
import co.voik.agesandtheart.platform.services.InkFluids
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.material.Fluid
import net.neoforged.neoforge.fluids.BaseFlowingFluid
import net.neoforged.neoforge.fluids.FluidType
import net.neoforged.neoforge.registries.RegisterEvent

/**
 * NeoForge's half of [InkFluids].
 *
 * Shorter than Fabric's because NeoForge ships `BaseFlowingFluid` and its builder — the behaviour Fabric
 * needed a hand-written class for is four calls here. What NeoForge adds instead is [FluidType], which is
 * mandatory and is the reason none of this could live in common.
 */
class NeoForgeInkFluids : InkFluids {
    /** NeoForge counts millibuckets: 1,000 to the bucket, and 250 for a bottle by modded convention. */
    override val unitsPerBucket: Long = FluidType.BUCKET_VOLUME.toLong()
    override val unitsPerBottle: Long = FluidType.BUCKET_VOLUME.toLong() / 4

    override fun still(tier: InkTier): Fluid = Companion.still(tier)
    override fun flowing(tier: InkTier): Fluid = Companion.flowing(tier)
    override fun bucket(tier: InkTier): Item = Companion.bucket(tier)
    override fun tierOf(fluid: Fluid): InkTier? = byFluid[fluid]

    companion object {
        private val types = mutableMapOf<InkTier, FluidType>()
        private val still = mutableMapOf<InkTier, BaseFlowingFluid.Source>()
        private val flowing = mutableMapOf<InkTier, BaseFlowingFluid.Flowing>()
        private val blocks = mutableMapOf<InkTier, LiquidBlock>()
        private val buckets = mutableMapOf<InkTier, Item>()
        private val byFluid = mutableMapOf<Fluid, InkTier>()

        fun still(tier: InkTier): BaseFlowingFluid.Source = still.getValue(tier)
        fun flowing(tier: InkTier): BaseFlowingFluid.Flowing = flowing.getValue(tier)
        fun block(tier: InkTier): LiquidBlock = blocks.getValue(tier)
        fun bucket(tier: InkTier): Item = buckets.getValue(tier)

        /**
         * Builds every ink. Suppliers rather than direct references throughout, because the pair is
         * mutually recursive — the still fluid names the flowing one and back again.
         */
        fun build() {
            if (still.isNotEmpty()) return
            for ((tier, identity) in AgeFluids.INKS) {
                types[tier] = FluidType(FluidType.Properties.create())

                val properties = BaseFlowingFluid.Properties(
                    { types.getValue(tier) },
                    { still.getValue(tier) },
                    { flowing.getValue(tier) },
                )
                    .bucket { buckets.getValue(tier) }
                    .block { blocks.getValue(tier) }
                    .slopeFindDistance(identity.flow.slopeFindDistance)
                    .levelDecreasePerBlock(identity.flow.dropOff)
                    .tickRate(identity.flow.tickDelay)
                    .explosionResistance(identity.flow.explosionResistance)

                still[tier] = BaseFlowingFluid.Source(properties)
                flowing[tier] = BaseFlowingFluid.Flowing(properties)
                byFluid[still.getValue(tier)] = tier
                byFluid[flowing.getValue(tier)] = tier

                blocks[tier] = LiquidBlock(still.getValue(tier), AgeFluids.liquidBlockProperties(identity.block))
                buckets[tier] = BucketItem(still.getValue(tier), AgeFluids.bucketProperties(identity.bucket))
            }
        }

        /** Registration is a mod-bus event here, so it is driven from the entrypoint rather than init. */
        fun register(event: RegisterEvent) {
            build()
            event.register(net.neoforged.neoforge.registries.NeoForgeRegistries.Keys.FLUID_TYPES) { helper ->
                AgeFluids.INKS.forEach { (tier, identity) -> helper.register(identity.still, types.getValue(tier)) }
            }
            event.register(Registries.FLUID) { helper ->
                AgeFluids.INKS.forEach { (tier, identity) ->
                    helper.register(identity.still, still.getValue(tier))
                    helper.register(identity.flowing, flowing.getValue(tier))
                }
            }
            event.register(Registries.BLOCK) { helper ->
                AgeFluids.INKS.forEach { (tier, identity) -> helper.register(identity.block, blocks.getValue(tier)) }
            }
            event.register(Registries.ITEM) { helper ->
                AgeFluids.INKS.forEach { (tier, identity) -> helper.register(identity.bucket, buckets.getValue(tier)) }
            }
        }
    }
}
