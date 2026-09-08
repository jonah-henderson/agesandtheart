package co.voik.agesandtheart.client

import co.voik.agesandtheart.content.AgeContent
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
            registrar(listOf(BlockTintSources.constant(colour.tint)), block)
        }
        registrar(
            listOf(BlockTintSources.constant(AgeContent.ASTRITE_TINT)),
            AgeContent.ASTRITE_SHARD_BLOCK,
        )
    }
}
