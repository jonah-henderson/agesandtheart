package co.voik.agesandtheart.book

import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.page.PageLearning
import co.voik.agesandtheart.client.BookScreenOpener
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LecternBlock
import net.minecraft.world.level.block.entity.LecternBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * A book of ours on a vanilla lectern (design §7.8.2): shut until somebody opens it, and then a door.
 *
 * Reached from `LecternBlockMixin` for every click on a lectern and every scheduled tick of one, and from
 * `LecternBlockEntityMixin` for what a client is told. A lectern holding anything else is left to vanilla.
 */
object LecternBooks {

    /** How near a player keeps an open book open — and, on a client, how near its panel resolves. */
    const val REACH_BLOCKS = 8.0

    /**
     * How near the server lets a lectern's panel stay open: past [REACH_BLOCKS], so the client — which lets
     * go at the edge — is always the one to, and the two never argue over a player standing on it.
     */
    private const val PANEL_HELD_WITHIN_BLOCKS = REACH_BLOCKS + 4.0

    /** How long an open book goes between looking round for a reader, so "a few minutes" is at most this. */
    private const val TICKS_BETWEEN_LOOKS_ROUND = 3 * 60 * 20

    /** Vanilla's own key, so its `loadAdditional` reads what this writes with no seam of ours on the client. */
    private const val BOOK_KEY = "Book"

    /**
     * Written in place of a book that is not ours. A tag with nothing in it need not be applied at all, and it
     * is applying one that clears a book of ours the client still holds.
     */
    private const val NOTHING_OF_OURS_KEY = "agesandtheart:nothing_of_ours"

    private const val FULL_VOLUME = 1.0f
    private const val NATURAL_PITCH = 1.0f

    /** What a comparator reads off a book of ours lying open, and lying shut. */
    private const val OPEN_SIGNAL = 15
    private const val SHUT_SIGNAL = 1

    /** What a click on an open book is asking for. */
    private enum class Click { LINK, READ, SHUT }

    fun isOurs(stack: ItemStack): Boolean = panelPageOf(stack) != null

    /** Which page carries [stack]'s panel — opposite ones, as in the source — or null where it is not ours. */
    fun panelPageOf(stack: ItemStack): BookPage? = when {
        stack.item === AgeContent.DESCRIPTIVE_BOOK -> BookPage.LEFT
        stack.item === AgeContent.LINKING_BOOK -> BookPage.RIGHT
        else -> null
    }

    /** What a click on a lectern does, or null to leave the lectern to vanilla. */
    @JvmStatic
    fun use(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult? {
        val lectern = level.getBlockEntity(pos) as? LecternBlockEntity ?: return null
        val panelPage = panelPageOf(lectern.book) ?: return null
        // A sneak is vanilla's "Take Book", which we have no screen for: it takes the book, open or shut, and
        // does nothing else — no link, no reading, no shutting. Where the player may not take it, the click
        // is an ordinary one.
        if (player.isSecondaryUseActive && player.mayBuild()) {
            if (level is ServerLevel) takeBack(state, level, pos, player, lectern)
            return InteractionResult.SUCCESS
        }
        if (!LecternOpening.isOpen(state)) {
            if (level is ServerLevel) open(state, level, pos, lectern)
            return InteractionResult.SUCCESS
        }
        val facing = state.getValue(LecternBlock.FACING)
        val spot = LecternBookPlane.spotLookedAt(facing, pos, player.eyePosition, hit.location)
        when (clickOn(lectern.book, panelPage, spot)) {
            Click.LINK -> linkFrom(level, lectern, player)
            Click.READ -> read(level, lectern, player)
            Click.SHUT -> shut(state, level, pos)
        }
        return InteractionResult.SUCCESS
    }

    /** The scheduled look round: an open book with nobody near it shuts, and one with a reader waits again. */
    @JvmStatic
    fun shutIfUnwatched(level: ServerLevel, pos: BlockPos) {
        val state = level.getBlockState(pos)
        if (!LecternOpening.isOpen(state)) return
        if (anybodyNear(level, pos)) {
            level.scheduleTick(pos, state.block, TICKS_BETWEEN_LOOKS_ROUND)
            return
        }
        shut(state, level, pos)
    }

    /**
     * The book lying open on the lectern at [pos], where [viewer] may see its panel: in their own world,
     * within reach, and a book of ours. Null for anything else, which is every request a client had no
     * business making.
     */
    fun openBookSeenBy(viewer: Player, pos: BlockPos): ItemStack? {
        val level = viewer.level()
        if (!level.isLoaded(pos)) return null
        if (!viewer.position().closerThan(Vec3.atCenterOf(pos), PANEL_HELD_WITHIN_BLOCKS)) return null
        if (!LecternOpening.isOpen(level.getBlockState(pos))) return null
        val lectern = level.getBlockEntity(pos) as? LecternBlockEntity ?: return null
        return lectern.book.takeIf(::isOurs)
    }

    /** What a client is told a lectern holds: the book, where it is one of ours, and otherwise that it is not. */
    @JvmStatic
    fun updateTagFor(lectern: LecternBlockEntity, registries: HolderLookup.Provider): CompoundTag {
        val tag = CompoundTag()
        if (!isOurs(lectern.book)) {
            tag.putBoolean(NOTHING_OF_OURS_KEY, true)
            return tag
        }
        ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), shownToClients(lectern.book))
            .resultOrPartial { problem ->
                Constants.LOG.warn("Lectern: the book at {} could not be sent: {}", lectern.blockPos, problem)
            }
            .ifPresent { tag.put(BOOK_KEY, it) }
        return tag
    }

    /**
     * What a comparator reads off a lectern holding a book of ours, or null to leave it to vanilla: full while the
     * book lies open, and while it is shut the least a book can give — vanilla's own floor for a book being there
     * at all. Vanilla's reading is how far through its pages a book has been read, and ours have none.
     */
    @JvmStatic
    fun signalOf(lectern: LecternBlockEntity): Int? {
        if (!isOurs(lectern.book)) return null
        return if (LecternOpening.isOpen(lectern.blockState)) OPEN_SIGNAL else SHUT_SIGNAL
    }

    private fun clickOn(book: ItemStack, panelPage: BookPage, spot: LecternBookSpot?): Click {
        val onThePanel = spot != null && spot.page == panelPage && spot.withinThePanel
        val onTheWriting = spot != null && spot.page != panelPage && book.item === AgeContent.DESCRIPTIVE_BOOK
        return when {
            onThePanel -> Click.LINK
            onTheWriting -> Click.READ
            else -> Click.SHUT
        }
    }

    /** Goes, and leaves the book on the lectern for the next reader — which is the whole of a lectern's point. */
    private fun linkFrom(level: Level, lectern: LecternBlockEntity, player: Player) {
        if (level !is ServerLevel || player !is ServerPlayer) return
        // A descriptive book is stamped with its Age the first time it links, and the lectern keeps the stamp.
        if (Linking.go(player, level, lectern.book)) lectern.setChanged()
    }

    private fun read(level: Level, lectern: LecternBlockEntity, player: Player) {
        // Guarded so the screen class is never loaded on a dedicated server.
        if (level.isClientSide) BookScreenOpener.openFromLectern(lectern.book, lectern.blockPos)
        if (player is ServerPlayer) PageLearning.study(player, lectern.book)
    }

    private fun open(state: BlockState, level: ServerLevel, pos: BlockPos, lectern: LecternBlockEntity) {
        // A blank descriptive book is written on its inventory tick, which a lectern never runs, and one can reach
        // a lectern without ever being carried — so it is written here, on the click every reading passes through.
        if (DescriptiveBookItem.writeIfBlank(lectern.book, level)) lectern.setChanged()
        level.setBlock(pos, state.setValue(LecternOpening.BOOK_OPEN, true), Block.UPDATE_CLIENTS)
        // A comparator reads whether the book lies open ([signalOf]), and is told only when it is asked to be.
        level.updateNeighbourForOutputSignal(pos, state.block)
        level.scheduleTick(pos, state.block, TICKS_BETWEEN_LOOKS_ROUND)
        level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, FULL_VOLUME, NATURAL_PITCH)
    }

    private fun shut(state: BlockState, level: Level, pos: BlockPos) {
        if (level !is ServerLevel) return
        level.setBlock(pos, LecternOpening.closed(state), Block.UPDATE_CLIENTS)
        level.updateNeighbourForOutputSignal(pos, state.block)
        level.playSound(null, pos, SoundEvents.BOOK_PAGE_TURN, SoundSource.BLOCKS, FULL_VOLUME, NATURAL_PITCH)
    }

    private fun takeBack(state: BlockState, level: ServerLevel, pos: BlockPos, player: Player, lectern: LecternBlockEntity) {
        val book = lectern.book.copy()
        lectern.clearContent()
        LecternBlock.resetBookState(player, level, pos, LecternOpening.closed(state), false)
        if (!player.inventory.add(book)) player.drop(book, false)
        level.playSound(null, pos, SoundEvents.BOOK_PUT, SoundSource.BLOCKS, FULL_VOLUME, NATURAL_PITCH)
    }

    private fun anybodyNear(level: ServerLevel, pos: BlockPos): Boolean {
        val centre = Vec3.atCenterOf(pos)
        return level.players().any { !it.isSpectator && it.position().closerThan(centre, REACH_BLOCKS) }
    }

    /** The book as a client sees it: the Age behind a linking book stays on the server, as it does in a hand. */
    private fun shownToClients(book: ItemStack): ItemStack {
        val target = book.get(AgeContent.LINK_TARGET) ?: return book
        return book.copy().also { it.set(AgeContent.LINK_TARGET, target.copy(recipe = null)) }
    }
}
