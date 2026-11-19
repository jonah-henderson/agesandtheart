package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.aspect.Features
import co.voik.agesandtheart.book.BookFinderBlock
import co.voik.agesandtheart.book.BookFinderFrameBlock
import co.voik.agesandtheart.book.BookFinders
import co.voik.agesandtheart.book.BookLiftShelfBlock
import co.voik.agesandtheart.book.BookLifts
import co.voik.agesandtheart.book.ShelfChain
import co.voik.agesandtheart.book.Bookstands
import co.voik.agesandtheart.content.RimeColour
import co.voik.agesandtheart.client.light.TintedLights
import co.voik.ephemeris.sky.LevelLooks
import co.voik.ephemeris.client.GroundTints
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.client.renderer.block.BlockAndTintGetter
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.CompoundedStone
import co.voik.agesandtheart.content.PalmBeach
import co.voik.agesandtheart.content.PalmWood
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.client.color.block.BlockTintSource
import net.minecraft.client.color.block.BlockTintSources
import co.voik.agesandtheart.content.DeadPaperTreeSaplingBlock
import co.voik.agesandtheart.content.PaperTreeHealth
import co.voik.agesandtheart.content.PaperTreeLeavesBlock
import co.voik.agesandtheart.worldgen.feature.TreeKinds
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.FoliageColor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
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
        registrar(listOf(AlgaeTint), AgeContent.ALGAE_BLOCK)
        registrar(
            listOf(BlockTintSources.constant(opaque(AgeContent.GLOOMGRIT_TINT))),
            AgeContent.GLOOMGRIT_CLUSTER,
        )
        // White sand has a texture of its own (a desaturated vanilla sand), and only its sandstone is a tint.
        registrar(listOf(BlockTintSources.constant(opaque(PalmBeach.WHITE_SAND_TINT))), PalmBeach.WHITE_SANDSTONE_BLOCK)
        registrar(listOf(BlockTintSources.constant(opaque(CompoundedStone.TINT))), CompoundedStone.BLOCK)
        palm(registrar)
        registrar(listOf(ByIndicator), BookFinders.FRAME)
        registrar(listOf(ByFindersIndicator), BookFinders.BLOCK)
        // A book lift frame's buttons: tint 0 on the one pressed, 1 on the rest, 2 on all of them locked, as
        // `scripts/books/book_lift_models.py` writes them.
        registrar(
            listOf(LIFT_BUTTON_LIT, LIFT_BUTTON_DULL, LIFT_BUTTON_LOCKED).map { BlockTintSources.constant(opaque(it)) },
            BookLifts.FRAME,
        )
        registrar(listOf(ByChain), BookLifts.SHELF)
        for (leaves in TreeKinds.TINTED_LEAVES) registrar(listOf(WrittenLeaves(leaves, vanillaTintOf(leaves))), leaves)
    }

    /** What vanilla tints [leaves] with, which is what they keep where their Age wrote them no colour. */
    private fun vanillaTintOf(leaves: Block): BlockTintSource = when (leaves) {
        Blocks.SPRUCE_LEAVES -> BlockTintSources.constant(FoliageColor.FOLIAGE_EVERGREEN)
        Blocks.BIRCH_LEAVES -> BlockTintSources.constant(FoliageColor.FOLIAGE_BIRCH)
        else -> BlockTintSources.foliage()
    }

    /** A kind of tree's leaves in the colour their Age wrote them (`red oak trees`), or as [vanilla] tints them. */
    private class WrittenLeaves(leaves: Block, private val vanilla: BlockTintSource) : BlockTintSource {
        private val id: Identifier = BuiltInRegistries.BLOCK.getKey(leaves)

        private fun writtenIn(level: BlockAndTintGetter): Int? {
            val dimension = GroundTints.levelBehind(level)?.dimension() ?: return null
            return LevelLooks.of(dimension)?.blockTint(id)?.packed()?.let(::opaque)
        }

        override fun color(state: BlockState): Int = vanilla.color(state)

        override fun colorInWorld(state: BlockState, level: BlockAndTintGetter, pos: BlockPos): Int =
            writtenIn(level) ?: vanilla.colorInWorld(state, level, pos)

        override fun colorAsTerrainParticle(state: BlockState, level: BlockAndTintGetter, pos: BlockPos): Int =
            writtenIn(level) ?: vanilla.colorAsTerrainParticle(state, level, pos)

        override fun relevantProperties(): Set<Property<*>> = vanilla.relevantProperties()
    }

    /**
     * The algae's red, or the colour the Age it grows in painted it (`blue algae`), as that Age's look carries it —
     * the level the section is built for, so a panel looking into another Age has that Age's algae.
     */
    private object AlgaeTint : BlockTintSource {
        private val ALGAE: Identifier = Identifier.parse(Features.ALGAE)

        override fun color(state: BlockState): Int = opaque(AgeContent.ALGAE_TINT)

        override fun colorInWorld(state: BlockState, level: BlockAndTintGetter, pos: BlockPos): Int {
            val dimension = GroundTints.levelBehind(level)?.dimension() ?: return color(state)
            return LevelLooks.of(dimension)?.blockTint(ALGAE)?.packed()?.let(::opaque) ?: color(state)
        }
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
            PalmWood.FENCE, PalmWood.FENCE_GATE, PalmWood.TRAPDOOR, PalmWood.PRESSURE_PLATE,
            PalmWood.BUTTON, PalmWood.SHELF,
            // A 26.3 sign's board is a block model, so it takes the tint like the rest; only its text is drawn
            // by the block entity. Left off this list, the walk found them plain birch.
            PalmWood.SIGN, PalmWood.WALL_SIGN, PalmWood.HANGING_SIGN, PalmWood.WALL_HANGING_SIGN,
        )
        for (block in sawnThroughout) registrar(listOf(sawn, sawn), block)
        // Only a palm toolbox's or bookstand's model asks for a tint, so the other woods draw untinted.
        registrar(listOf(sawn), AgeContent.TOOLBOX_BLOCK)
        registrar(listOf(sawn), Bookstands.BLOCK)
        for (block in listOf(PalmWood.FRONDS, PalmWood.POTTED_SAPLING)) registrar(listOf(green), block)
        for (block in listOf(PalmWood.COCONUT, PalmWood.HANGING_COCONUT)) {
            registrar(listOf(BlockTintSources.constant(opaque(PalmWood.COCONUT_TINT))), block)
        }
        // A paper tree says how it is: the leaves by how far they have turned, the heart and a sapling by
        // which side of their band the moisture is on (design §7.1.2).
        registrar(listOf(ByBlight), AgeContent.PAPER_TREE_LEAVES_BLOCK)
        registrar(listOf(ByMoisture), AgeContent.PAPER_TREE_ROOT_BLOCK)
        registrar(listOf(ByMoisture), AgeContent.PAPER_TREE_SAPLING_BLOCK)
        registrar(listOf(ByHowItDied), AgeContent.DEAD_PAPER_TREE_SAPLING_BLOCK)
    }

    /** A book finder frame's indicator, in a rime crystal's colours, by [BookFinderFrameBlock.INDICATOR]. */
    private object ByIndicator : BlockTintSource {
        override fun color(state: BlockState): Int = opaque(
            when (state.getValue(BookFinderFrameBlock.INDICATOR)) {
                BookFinderFrameBlock.Indicator.READY -> RimeColour.GREEN.tint
                BookFinderFrameBlock.Indicator.INVALID -> RimeColour.RED.tint
                BookFinderFrameBlock.Indicator.ACTIVE -> RimeColour.BLUE.tint
            },
        )

        override fun relevantProperties(): Set<Property<*>> = setOf(BookFinderFrameBlock.INDICATOR)
    }

    /** A finder's own frame's indicator: cyan, for a finder living there, unless its shelf is lit for an errand. */
    private object ByFindersIndicator : BlockTintSource {
        override fun color(state: BlockState): Int = opaque(
            when (state.getValue(BookFinderBlock.INDICATOR)) {
                BookFinderFrameBlock.Indicator.ACTIVE -> RimeColour.BLUE.tint
                BookFinderFrameBlock.Indicator.READY, BookFinderFrameBlock.Indicator.INVALID -> RimeColour.CYAN.tint
            },
        )

        override fun relevantProperties(): Set<Property<*>> = setOf(BookFinderBlock.INDICATOR)
    }

    /** A book lift shelf's platform, in the colour of the chain it was. */
    private object ByChain : BlockTintSource {
        override fun color(state: BlockState): Int = opaque(
            when (state.getValue(BookLiftShelfBlock.CHAIN)) {
                ShelfChain.IRON -> UNTINTED
                ShelfChain.COPPER -> COPPER_PLATFORM
                ShelfChain.EXPOSED_COPPER -> EXPOSED_COPPER_PLATFORM
                ShelfChain.WEATHERED_COPPER -> WEATHERED_COPPER_PLATFORM
                ShelfChain.OXIDIZED_COPPER -> OXIDIZED_COPPER_PLATFORM
            },
        )

        override fun relevantProperties(): Set<Property<*>> = setOf(BookLiftShelfBlock.CHAIN)
    }

    /** A dead yema sapling, by [DeadPaperTreeSaplingBlock.DROWNED]; its item takes the same two colours. */
    private object ByHowItDied : BlockTintSource {
        override fun color(state: BlockState): Int =
            opaque(if (state.getValue(DeadPaperTreeSaplingBlock.DROWNED)) DROWNED_SAPLING else SERE_LEAF)

        override fun relevantProperties(): Set<Property<*>> = setOf(DeadPaperTreeSaplingBlock.DROWNED)
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

    /** Halfway from a drowning leaf to a drowned one: a sapling has one colour to say both with. */
    private const val DROWNED_SAPLING = 0x7C6E2E

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

    /** A book lift's pressed button, lit yellow, and the rest, and all of them while locked, dark grey (Jonah); the item's are the script's. */
    private const val LIFT_BUTTON_LIT = 0xFFDD6E
    private const val LIFT_BUTTON_DULL = 0x5A5A5A
    private const val LIFT_BUTTON_LOCKED = LIFT_BUTTON_DULL

    /** A copper chain's platform, as weathered as the chain; an iron chain's is as drawn. */
    private const val COPPER_PLATFORM = 0xE3946E
    private const val EXPOSED_COPPER_PLATFORM = 0xC49A84
    private const val WEATHERED_COPPER_PLATFORM = 0x8DB596
    private const val OXIDIZED_COPPER_PLATFORM = 0x6FC2A5
}
