package co.voik.agesandtheart.client

import co.voik.agesandtheart.content.AgeFluids
import co.voik.agesandtheart.location
import net.minecraft.client.color.block.BlockTintSource
import net.minecraft.client.renderer.BiomeColors
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.client.renderer.block.FluidModel
import net.minecraft.client.resources.model.sprite.Material
import net.minecraft.resources.Identifier

/**
 * What our fluids look like in the world. Without a model a fluid draws as the missing texture, since 26.1
 * renders fluids from a model rather than a handler.
 *
 * Each loader's client entrypoint pairs these with its own still and flowing fluid objects.
 */
// NeoForge deprecates the `BlockTintSource` constructor in favour of one taking its own
// `FluidTintSource`, which `common` compiles too far from to name. Vanilla's is not deprecated and is
// what both loaders build from.
@Suppress("DEPRECATION")
object AgeFluidLooks {

    /**
     * An ink: water's textures, tinted per ink — which is exactly how vanilla tints water by biome, so the
     * flow and surface animation come for free and only the colour is ours.
     */
    fun ink(identity: AgeFluids.InkIdentity): FluidModel.Unbaked = FluidModel.Unbaked(
        water("water_still"),
        water("water_flow"),
        null,
        BlockTintSource { identity.tint },
    )

    /**
     * Deep water: our own sprites under a very dark tint, so the surface animation and the flow come from
     * water's frames and the colour and opacity are ours.
     */
    fun deepWater(): FluidModel.Unbaked = FluidModel.Unbaked(
        ours("deep_water_still"),
        ours("deep_water_flow"),
        null,
        DeepWaterTint,
    )

    /**
     * Nearly black, leaning a little towards whatever colour this Age's water is (Jonah, 2026-10-06).
     *
     * It leans by how far the water here is from vanilla's own blue rather than by the water itself, so an
     * ordinary sea's abyss keeps exactly [ALMOST_BLACK] and only an Age that recoloured its water moves it.
     */
    private object DeepWaterTint : BlockTintSource {
        override fun color(state: BlockState): Int = ALMOST_BLACK

        override fun colorInWorld(state: BlockState, level: BlockAndTintGetter, pos: BlockPos): Int {
            val water = BiomeColors.getAverageWaterColor(level, pos)
            fun leaned(shift: Int): Int {
                val channel = (ALMOST_BLACK shr shift and BYTE) +
                    ((water shr shift and BYTE) - (VANILLA_WATER shr shift and BYTE)) * DEEP_WATER_LEAN
                return channel.toInt().coerceIn(0, BYTE) shl shift
            }
            return OPAQUE or leaned(RED) or leaned(GREEN) or leaned(BLUE)
        }
    }

    /** Water's own sprites. A `Material` is just the sprite plus a translucency flag in 26.1. */
    private fun water(path: String) = Material(Identifier.withDefaultNamespace("block/$path"))

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

    /**
     * Nearly black with the blue left in it.
     *
     * **Not black outright**, because a surface with no hue at all reads as a hole in the world rather than
     * as water — and the point of the abyss looking like water is that a diver can see they are still in
     * the sea. The fog does the blinding (`Depths`); this only has to say *that is not the water you came
     * down through*.
     */
    private const val ALMOST_BLACK = 0xFF06111A.toInt()

    /** Vanilla's own water, which an abyss leans away from rather than towards. */
    private const val VANILLA_WATER = 0xFF3F76E4.toInt()

    /** How much of the water's difference from vanilla's the abyss takes on: enough to see, never enough to light it. */
    private const val DEEP_WATER_LEAN = 0.12f

    private const val OPAQUE = 0xFF000000.toInt()
    private const val BYTE = 0xFF
    private const val RED = 16
    private const val GREEN = 8
    private const val BLUE = 0
}
