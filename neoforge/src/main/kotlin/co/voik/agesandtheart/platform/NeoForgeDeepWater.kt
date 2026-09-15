package co.voik.agesandtheart.platform

import co.voik.agesandtheart.content.AgeFluids
import co.voik.agesandtheart.content.DeepWater
import co.voik.agesandtheart.content.DeepWaterBlock
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.material.Fluid
import net.neoforged.neoforge.fluids.BaseFlowingFluid
import net.neoforged.neoforge.fluids.FluidType
import net.neoforged.neoforge.registries.NeoForgeRegistries
import net.neoforged.neoforge.registries.RegisterEvent

/**
 * NeoForge's half of deep water — design §7.1.2.
 *
 * Shorter than Fabric's for the reason [NeoForgeInkFluids] records: NeoForge ships `BaseFlowingFluid` and
 * its builder, so the behaviour Fabric needs a hand-written class for is four calls. What it adds instead
 * is [FluidType], which is mandatory and is the whole reason none of this can live in common.
 *
 * **The rule that makes it deep is not here.** Settling back to water lives on the block
 * ([DeepWaterBlock]) and the pressure in `DeepWater`, both in common, so the two loaders cannot drift on
 * the thing a player actually experiences.
 */
object NeoForgeDeepWater {

    /**
     * **Any water counts as the same fluid** — [DeepWater.countsAsTheSameFluid], and the one thing
     * `BaseFlowingFluid` gives no builder hook for, which is the reason these two subclasses exist at all.
     */
    private class DeepSource(properties: BaseFlowingFluid.Properties) : BaseFlowingFluid.Source(properties) {
        override fun isSame(fluid: Fluid): Boolean = DeepWater.countsAsTheSameFluid(fluid)
    }

    /** [DeepSource]'s twin; `BaseFlowingFluid` splits the pair and the rule belongs to both. */
    private class DeepFlowing(properties: BaseFlowingFluid.Properties) : BaseFlowingFluid.Flowing(properties) {
        override fun isSame(fluid: Fluid): Boolean = DeepWater.countsAsTheSameFluid(fluid)
    }

    private var type: FluidType? = null
    private var stillFluid: DeepSource? = null
    private var flowingFluid: DeepFlowing? = null
    private var liquid: LiquidBlock? = null

    val still: BaseFlowingFluid.Source get() = checkNotNull(stillFluid) { "Deep water asked for before build()" }
    val flowing: BaseFlowingFluid.Flowing get() = checkNotNull(flowingFluid) { "Deep water asked for before build()" }
    val block: LiquidBlock get() = checkNotNull(liquid) { "Deep water asked for before build()" }

    /**
     * Builds the pair and its block.
     *
     * Suppliers throughout, because the pair is mutually recursive — the still fluid names the flowing one
     * and back again — which is [NeoForgeInkFluids]' constraint arriving unchanged.
     */
    fun build() {
        if (stillFluid != null) return
        val identity = AgeFluids.DEEP_WATER
        // **Left at the defaults deliberately.** `FluidType.Properties` starts water-shaped — swimmable,
        // drownable, pushing like water — and every one of those is wanted here: deep water is water, and
        // what is different about it is a rule rather than a physics.
        type = FluidType(FluidType.Properties.create())

        val properties = BaseFlowingFluid.Properties(
            { checkNotNull(type) },
            { still },
            { flowing },
        )
            .bucket { Items.WATER_BUCKET }
            .block { block }
            .slopeFindDistance(identity.flow.slopeFindDistance)
            .levelDecreasePerBlock(identity.flow.dropOff)
            .tickRate(identity.flow.tickDelay)
            .explosionResistance(identity.flow.explosionResistance)

        stillFluid = DeepSource(properties)
        flowingFluid = DeepFlowing(properties)

        liquid = DeepWaterBlock(still, AgeFluids.liquidBlockProperties(identity.block))
    }

    /** Registration is a mod-bus event here, so it is driven from the entrypoint rather than from init. */
    fun register(event: RegisterEvent) {
        build()
        val identity = AgeFluids.DEEP_WATER
        event.register(NeoForgeRegistries.Keys.FLUID_TYPES) { helper ->
            helper.register(identity.still, checkNotNull(type))
        }
        event.register(Registries.FLUID) { helper ->
            helper.register(identity.still, still)
            helper.register(identity.flowing, flowing)
        }
        event.register(Registries.BLOCK) { helper ->
            helper.register(identity.block, block)
        }
    }
}
