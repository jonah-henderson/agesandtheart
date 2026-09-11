package co.voik.agesandtheart.platform

import co.voik.agesandtheart.content.AgeFluids
import co.voik.agesandtheart.content.DeepWaterBlock
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.material.Fluid
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction
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
     * **Any water counts as the same fluid** — the one thing `BaseFlowingFluid` gives no builder hook for,
     * and the reason these two subclasses exist at all.
     *
     * `FluidRenderer.getHeight` fills a fluid's block to the brim only when the fluid above it is the same
     * one, so deep water under an ordinary sea rendered an eighth of a block short and left a visible
     * horizontal gap between the two bodies (Jonah, walked 2026-09-11 on Fabric). The other reader is
     * `FlowingFluid.hasSameAbove`, which decides the fluid's *physical* height — and a block of deep water
     * with a sea on top of it is plainly full.
     *
     * Nothing about flow moves: every other `isSame` in the fluid engine asks the *neighbour's*
     * implementation with deep water as the argument, and vanilla's water still answers no.
     *
     * Kept in step with `FabricDeepWater.isSame` by hand, which is what the note at the top of this file
     * means about the two loaders not drifting on what a player experiences.
     */
    private class DeepSource(properties: BaseFlowingFluid.Properties) : BaseFlowingFluid.Source(properties) {
        override fun isSame(fluid: Fluid): Boolean = fluid.`is`(FluidTags.WATER)
    }

    /** [DeepSource]'s twin; `BaseFlowingFluid` splits the pair and the rule belongs to both. */
    private class DeepFlowing(properties: BaseFlowingFluid.Properties) : BaseFlowingFluid.Flowing(properties) {
        override fun isSame(fluid: Fluid): Boolean = fluid.`is`(FluidTags.WATER)
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
            // Water's own figures, against the inks' deliberately thick ones — see the Fabric side.
            .bucket { Items.WATER_BUCKET }
            .block { block }
            .slopeFindDistance(WATERS_OWN_SLOPE)
            .levelDecreasePerBlock(WATERS_OWN_DROP_OFF)
            .tickRate(WATERS_OWN_TICK_DELAY)
            .explosionResistance(EXPLOSION_RESISTANCE)

        stillFluid = DeepSource(properties)
        flowingFluid = DeepFlowing(properties)

        liquid = DeepWaterBlock(
            still,
            // Water's own properties but for the map colour — an abyss should not draw on a map as the sea
            // standing over it.
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

    private const val WATERS_OWN_SLOPE = 4
    private const val WATERS_OWN_DROP_OFF = 1
    private const val WATERS_OWN_TICK_DELAY = 5
    private const val EXPLOSION_RESISTANCE = 100.0f
    private const val WORLD_STRENGTH = 100.0f
}
