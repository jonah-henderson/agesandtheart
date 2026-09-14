package co.voik.agesandtheart.platform

import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry
import net.minecraft.client.color.block.BlockTintSource
import net.minecraft.client.renderer.block.FluidModel
import net.minecraft.client.resources.model.sprite.Material
import co.voik.agesandtheart.location

/**
 * What deep water looks like in the world.
 *
 * **Water's own textures under a very dark tint**, which is exactly how vanilla tints water by biome — so
 * the surface animation and the flow come for free and the only thing that is ours is the colour. Without
 * this an abyss draws as the missing texture, 26.1 rendering fluids from a model rather than a handler.
 *
 * A separate file from [FabricInkRendering] rather than another loop inside it: the inks share an identity
 * shape and this does not, and a client-only class is the wrong place to grow a special case.
 */
object FabricDeepWaterRendering {
    /**
     * **Our own sprites, and the only thing ours about them is the alpha.**
     *
     * They are Mojang's water frames at a uniform alpha of our own, animation `.mcmeta` and all.
     * `Material`'s `forceTranslucent` flag only ever *forces* translucency — it cannot take it away — so
     * whether a fluid can be seen through is decided by whether its sprite has any alpha in it, and
     * vanilla's water is a uniform 180. Referencing the shared sprite meant an abyss you could back away
     * from and look straight through to line up a swim (Jonah, walked 2026-09-10), which no amount of
     * tinting or fog was ever going to fix.
     *
     * **Solid on the still frames and 191 on the flowing ones.** The still sprite is the top a diver
     * sights along, so it hides what is past it outright; the sides are the ones that must still read as a
     * fluid. `FluidModel` picks the fluid's layer from both sprites together, so one translucent sprite puts
     * the whole fluid in the translucent layer — and under a tint this dark, a side at 215 read as a wall.
     *
     * **Two numbers because the renderer draws two sprites**, and that is the whole of how a fluid gets a
     * per-face alpha: vanilla puts the *still* sprite on the top and bottom faces and the *flow* sprite on
     * the four sides. So the sides are the more transparent ones, which is what a diver looking along the
     * boundary sees. The bottom face takes the top's number rather than the sides', being drawn from the
     * same sprite — it is only ever seen from under an overhang, and splitting it would mean a third
     * sprite for one face.
     *
     * They need no atlas file: vanilla's `blocks.json` stitches the `block/` directory of *every*
     * namespace, so dropping a texture there is the whole of getting it onto the atlas.
     */
    private fun ours(path: String) = Material("block/$path".location())

    fun register() {
        val model = FluidModel.Unbaked(
            ours("deep_water_still"),
            ours("deep_water_flow"),
            null,
            BlockTintSource { ALMOST_BLACK },
        )
        FluidRenderingRegistry.register(
            FabricDeepWaterFluids.still,
            FabricDeepWaterFluids.flowing,
            model,
        )
    }

    /**
     * Nearly black with the blue left in it.
     *
     * **Not black outright**, because a surface with no hue at all reads as a hole in the world rather than
     * as water — and the point of the abyss looking like water is that a diver can see they are still in
     * the sea. The fog does the blinding (`Depths`); this only has to say *that is not the water you came
     * down through*.
     */
    private const val ALMOST_BLACK = 0xFF06111A.toInt()
}
