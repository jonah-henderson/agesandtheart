package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.light.TintedLights
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.PalmBeach
import co.voik.agesandtheart.content.PalmWood
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.client.color.block.BlockTintSource
import net.minecraft.client.color.block.BlockTintSources
import co.voik.agesandtheart.content.PaperTreeHealth
import co.voik.agesandtheart.content.PaperTreeLeavesBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property

/**
 * The colours our blocks are drawn in that their textures do not carry.
 *
 * **Which block is what colour is all that is shared**, because the two loaders reach
 * `BlockColors.register` by different routes and at a moment neither lets us choose. Fabric queues into
 * `BlockColorRegistry`; NeoForge fires `RegisterColorHandlersEvent.BlockTintSources` from inside
 * `BlockColors.createDefault`. Both take the same pair, so each passes its own registrar to [register].
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
        // White sand has a texture of its own (a desaturated vanilla sand), and only its sandstone is a tint.
        registrar(listOf(BlockTintSources.constant(opaque(PalmBeach.WHITE_SAND_TINT))), PalmBeach.WHITE_SANDSTONE_BLOCK)
        palm(registrar)
    }

    /**
     * The palm's wood over birch's, until the asset pass — see [PalmWood.PLANKS_TINT] for the colours.
     *
     * **Two tints on a log**, because its model's two faces are two materials: the palm's models carry their
     * own elements with 0 on a log's sides and 1 on its cut ends, so a log is bark round the outside and
     * caramel at the ends, and the wood, which is bark all round, takes bark for both. Those models are
     * birch's with a `tintindex` added, generated from the 26.3 jar, and go with the art.
     */
    private fun palm(registrar: (List<BlockTintSource>, Block) -> Unit) {
        val bark = BlockTintSources.constant(opaque(PalmWood.BARK_TINT))
        val sawn = BlockTintSources.constant(opaque(PalmWood.PLANKS_TINT))
        val green = BlockTintSources.constant(opaque(PalmWood.FRONDS_TINT))
        registrar(listOf(bark, sawn), PalmWood.LOG)
        registrar(listOf(bark, bark), PalmWood.WOOD)
        val sawnThroughout = listOf(
            PalmWood.STRIPPED_LOG, PalmWood.STRIPPED_WOOD, PalmWood.PLANKS, PalmWood.STAIRS, PalmWood.SLAB,
            PalmWood.FENCE, PalmWood.FENCE_GATE, PalmWood.DOOR, PalmWood.TRAPDOOR, PalmWood.PRESSURE_PLATE,
            PalmWood.BUTTON, PalmWood.SHELF,
            // A 26.3 sign's board is a block model, so it takes the tint like the rest; only its text is drawn
            // by the block entity. Left off this list, the walk found them plain birch.
            PalmWood.SIGN, PalmWood.WALL_SIGN, PalmWood.HANGING_SIGN, PalmWood.WALL_HANGING_SIGN,
        )
        for (block in sawnThroughout) registrar(listOf(sawn, sawn), block)
        for (block in listOf(PalmWood.FRONDS, PalmWood.SAPLING, PalmWood.POTTED_SAPLING)) registrar(listOf(green), block)
        registrar(listOf(BlockTintSources.constant(opaque(PalmWood.COCONUT_TINT))), PalmWood.COCONUT)
        // A paper tree says how it is: the leaves by how far they have turned, the heart and a sapling by
        // which side of their band the moisture is on (design §7.1.2).
        registrar(listOf(ByBlight), AgeContent.PAPER_TREE_LEAVES_BLOCK)
        registrar(listOf(ByMoisture), AgeContent.PAPER_TREE_ROOT_BLOCK)
        registrar(listOf(ByMoisture), AgeContent.PAPER_TREE_SAPLING_BLOCK)
    }

    /** A paper tree's leaf, by [PaperTreeLeavesBlock.BLIGHT]. */
    private object ByBlight : BlockTintSource {
        override fun color(state: BlockState): Int = opaque(
            when (state.getValue(PaperTreeLeavesBlock.BLIGHT)) {
                PaperTreeLeavesBlock.Blight.HEALTHY -> AgeContent.YEMA_LEAF_TINT
                PaperTreeLeavesBlock.Blight.BROWNING -> SERE_LEAF
                PaperTreeLeavesBlock.Blight.YELLOWING -> DROWNING_LEAF
                PaperTreeLeavesBlock.Blight.BLACKENED -> DROWNED_LEAF
            },
        )

        override fun relevantProperties(): Set<Property<*>> = setOf(PaperTreeLeavesBlock.BLIGHT)
    }

    /** A paper tree's heart or sapling, by the band its [PaperTreeHealth.MOISTURE] is in. */
    private object ByMoisture : BlockTintSource {
        override fun color(state: BlockState): Int = opaque(
            when (PaperTreeHealth.bandOf(state.getValue(PaperTreeHealth.MOISTURE))) {
                PaperTreeHealth.Band.SERE -> PARCHED
                PaperTreeHealth.Band.SUITS -> UNTINTED
                PaperTreeHealth.Band.DROWNED -> SODDEN
            },
        )

        override fun relevantProperties(): Set<Property<*>> = setOf(PaperTreeHealth.MOISTURE)
    }

    /** Brown for sere, yellow going to black for drowned — the order §7.1.2 gives them. */
    private const val SERE_LEAF = 0x8B6A3E
    private const val DROWNING_LEAF = 0xC9B23A
    private const val DROWNED_LEAF = 0x2E2B22

    /** Pale and dusty dry, dark and cold soaked, and the root's own colour in between. */
    private const val PARCHED = 0xD8C49A
    private const val UNTINTED = 0xFFFFFF
    private const val SODDEN = 0x5B6B78

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
