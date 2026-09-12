package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.light.TintedLights
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.client.color.block.BlockTintSource
import net.minecraft.client.color.block.BlockTintSources
import net.minecraft.world.level.block.Block

/**
 * The colours our blocks are drawn in that their textures do not carry.
 *
 * **Which block is what colour is all that is shared**, because the two loaders reach
 * `BlockColors.register` by different routes and at a moment neither lets us choose. Fabric queues into
 * `BlockColorRegistry`; NeoForge fires `RegisterColorHandlersEvent.BlockTintSources` from inside
 * `BlockColors.createDefault`. Both take the same pair, so each passes its own [registrar] in.
 *
 * **Calling `Minecraft.getInstance().blockColors` from a client entrypoint does not work**, whatever the
 * signature suggests: `Minecraft.<init>` runs the entrypoints ninety lines before it assigns `blockColors`,
 * so the getter returns null and the crash report that would say so cannot be written either — the report
 * asks for a render device that does not exist yet, and only that second failure reaches the console.
 *
 * A later hook is not an option either. Tints are read while models bake, so anything registered after the
 * first resource reload is registered for nothing.
 *
 * **Why a tint and not a texture.** The mod ships no art, and a recoloured copy of Mojang's amethyst would
 * be Mojang's art in an MIT jar. A `tintindex` in our model and one number here get the same picture and
 * redistribute nothing. Both go when the asset pass draws a real crystal (Phase 9).
 */
object AgeTints {

    fun register(registrar: (List<BlockTintSource>, Block) -> Unit) {
        // One tint apiece, which is the whole of what makes eight colours cost one model.
        for ((colour, block) in AgeContent.RIME_CRYSTAL_BLOCKS) {
            registrar(listOf(BlockTintSources.constant(opaque(colour.tint))), block)
            // And the same colour cast on what stands near it, but only while it is lit — a crystal at
            // rest glows too faintly for its colour to be doing anything to the wall behind it.
            TintedLights.cast(block) { state ->
                if (state.getValue(BlockStateProperties.POWERED)) colour.tint else null
            }
        }
        registrar(
            listOf(BlockTintSources.constant(opaque(AgeContent.ASTRITE_TINT))),
            AgeContent.ASTRITE_SHARD_BLOCK,
        )
        registrar(listOf(BlockTintSources.constant(opaque(AgeContent.ALGAE_TINT))), AgeContent.ALGAE_BLOCK)
        registrar(
            listOf(BlockTintSources.constant(opaque(AgeContent.GLOOMGRIT_TINT))),
            AgeContent.GLOOMGRIT_CLUSTER,
        )
    }

    /**
     * [rgb] with a full alpha byte on it — **and without this a tinted block is invisible**, which is a
     * thing worth stating plainly because nothing about it looks like a colour bug.
     *
     * Vanilla multiplies a tint into the quad with `ARGB.multiply`, which multiplies **alpha along with
     * the colour channels**. A constant written the way anybody writes a colour — six hex digits,
     * `0xE0575B` — has an alpha byte of zero, so the quad comes out `255 * 0 / 255` and the block does not
     * draw at all. It renders perfectly in the hand, because the item pipeline applies its tints
     * differently, which is what makes the symptom so misleading (Jonah, walked 2026-09-08).
     *
     * Done here rather than in the constants so a colour stays readable as a colour, and so the one place
     * that hands them to the renderer is the one place that has to know about the alpha byte.
     */
    private fun opaque(rgb: Int): Int = rgb or ALPHA

    /** A full alpha byte, which is what an `AARRGGBB` tint needs and an `RRGGBB` constant lacks. */
    private const val ALPHA = 0xFF shl 24
}
