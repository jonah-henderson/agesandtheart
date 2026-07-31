package co.voik.agesandtheart.platform

import co.voik.agesandtheart.content.AgeFluids
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry
import net.minecraft.client.renderer.block.FluidModel
import net.minecraft.client.color.block.BlockTintSource
import net.minecraft.client.resources.model.sprite.Material
import net.minecraft.resources.Identifier

/**
 * What ink looks like in the world.
 *
 * Water's textures, tinted per ink — which is exactly how vanilla tints water by biome, so the flow and
 * surface animation come for free and only the colour is ours. Without this an ink pool draws as the
 * missing texture, since 26.1 renders fluids from a model rather than a handler.
 */
object FabricInkRendering {
    /** Water's own sprites. A `Material` is just the sprite plus a translucency flag in 26.1. */
    private fun water(path: String) = Material(Identifier.withDefaultNamespace("block/$path"))

    fun register() {
        for ((tier, identity) in AgeFluids.INKS) {
            val model = FluidModel.Unbaked(
                water("water_still"),
                water("water_flow"),
                null,
                BlockTintSource { identity.tint },
            )
            FluidRenderingRegistry.register(
                FabricInkFluids.still(tier),
                FabricInkFluids.flowing(tier),
                model,
            )
        }
    }
}
