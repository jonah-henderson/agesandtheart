package co.voik.agesandtheart.content

import co.voik.agesandtheart.location
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.sounds.SoundEvent
import net.minecraft.tags.TagKey
import net.minecraft.util.ColorRGBA
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.ColoredFallingBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import net.minecraft.world.level.material.MapColor

/**
 * What the palm beach is made of — `notes/palm-beach-design.md`. A biome of ours that exists only in Ages.
 *
 * Its own object rather than more of [AgeContent], which already holds two thousand lines; each loader's
 * registration takes these lists along with that object's.
 */
object PalmBeach {

    val WHITE_SAND_ID: Identifier = "white_sand".location()
    val WHITE_SANDSTONE_ID: Identifier = "white_sandstone".location()

    /**
     * **A plain falling block, not vanilla's `SandBlock`**, which plays the desert's dry ambient sounds — the
     * wrong place entirely. The palm beach's own sound is the surf, and that comes from the shore.
     *
     * Vanilla sand's numbers otherwise, and its dust a shade paler, to match.
     */
    val WHITE_SAND_BLOCK: ColoredFallingBlock = ColoredFallingBlock(
        ColorRGBA(WHITE_SAND_DUST),
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, WHITE_SAND_ID))
            .mapColor(MapColor.SNOW)
            .instrument(NoteBlockInstrument.SNARE)
            .strength(SAND_STRENGTH)
            .sound(SoundType.SAND),
    )

    /** What lies under the white sand, so an undercut or a dug pit does not show yellow. Vanilla sandstone's numbers. */
    val WHITE_SANDSTONE_BLOCK: Block = Block(
        BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, WHITE_SANDSTONE_ID))
            .mapColor(MapColor.SNOW)
            .instrument(NoteBlockInstrument.BASEDRUM)
            .requiresCorrectToolForDrops()
            .strength(SANDSTONE_STRENGTH),
    )

    val WHITE_SAND: Item = blockItem(WHITE_SAND_BLOCK, WHITE_SAND_ID)
    val WHITE_SANDSTONE: Item = blockItem(WHITE_SANDSTONE_BLOCK, WHITE_SANDSTONE_ID)

    /**
     * The faint warm white the white sandstone is drawn in, over vanilla's calcite until the asset pass. The
     * sand itself is a texture now, vanilla's sand desaturated and lightened to about (240, 237, 228): it was
     * concrete powder under a tint, then powder snow, and the walk wanted it to read as sand (Jonah,
     * 2026-10-01).
     */
    const val WHITE_SAND_TINT = 0xFFFAF0

    /**
     * What a wave breaks on — `#agesandtheart:surf_breaks_on`, white sand to start with. The shore is this
     * level with still water, in a palm beach; see `Surf`.
     */
    val SURF_BREAKS_ON: TagKey<Block> = TagKey.create(Registries.BLOCK, "surf_breaks_on".location())

    private val SURF_ID: Identifier = "surf".location()

    /**
     * The sea on a palm beach: one three-minute recording of waves (`sounds/surf_loop.ogg`, from Freesound, see
     * ATTRIBUTIONS.md; encoded mono so it can be positional), looped by `SurfLoop` while there is shore in reach.
     * Streamed, being long.
     */
    val SURF: SoundEvent = SoundEvent.createVariableRangeEvent(SURF_ID)

    val soundEvents: List<Pair<Identifier, SoundEvent>> = listOf(SURF_ID to SURF)

    val blocks: List<Pair<Identifier, Block>> = listOf(
        WHITE_SAND_ID to WHITE_SAND_BLOCK,
        WHITE_SANDSTONE_ID to WHITE_SANDSTONE_BLOCK,
    )

    val items: List<Pair<Identifier, Item>> = listOf(
        WHITE_SAND_ID to WHITE_SAND,
        WHITE_SANDSTONE_ID to WHITE_SANDSTONE,
    )

    private fun blockItem(block: Block, id: Identifier): Item =
        BlockItem(block, Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)).useBlockDescriptionPrefix())

    private const val WHITE_SAND_DUST = 0xE6E1D2
    private const val SAND_STRENGTH = 0.5f
    private const val SANDSTONE_STRENGTH = 0.8f
}
