package co.voik.agesandtheart.client

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.client.Minecraft
import net.minecraft.client.color.block.BlockTintSources

/**
 * The colours our blocks are drawn in that their textures do not carry.
 *
 * **Vanilla's own `BlockColors`, called directly.** 26.1 took the block-colour hook off both loaders —
 * Fabric's `ColorProviderRegistry.BLOCK` is gone and NeoForge's `RegisterColorHandlersEvent.Block` with it,
 * leaving only registries for custom *kinds* of tint source. What is left underneath is vanilla's, it is
 * public, and it needs no wrapper: `BlockTintSources.constant` is exactly a flat colour.
 *
 * **Why a tint and not a texture.** The mod ships no art, and a recoloured copy of Mojang's amethyst would
 * be Mojang's art in an MIT jar. A `tintindex` in our model and one number here get the same picture and
 * redistribute nothing. Both go when the asset pass draws a real crystal (Phase 9).
 */
object AgeTints {
    fun register() {
        Minecraft.getInstance().blockColors.register(
            listOf(BlockTintSources.constant(AgeContent.RIME_CRYSTAL_TINT)),
            AgeContent.RIME_CRYSTAL_BLOCK,
        )
    }
}
