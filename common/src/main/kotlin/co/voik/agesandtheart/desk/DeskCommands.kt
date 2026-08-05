package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.age.word.grammar.Readout
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.NotebookItem
import co.voik.agesandtheart.location
import co.voik.agesandtheart.platform.Services
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.MenuProvider
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerLevelAccess
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack

/** What may bind a book. A tag, so a pack can allow its own. */
object BookBinding {
    val TAG: TagKey<Item> = TagKey.create(Registries.ITEM, "book_binding".location())
}

/** Opens the desk without needing a loader-specific extended menu — all state travels on our payload. */
fun DeskMenuProvider(pos: BlockPos): MenuProvider = SimpleMenuProvider(
    { containerId, inventory, player ->
        WritersDeskMenu(
            containerId,
            inventory,
            ContainerLevelAccess.create(player.level(), pos),
        )
    },
    Component.translatable("container.agesandtheart.writers_desk"),
)

/**
 * Carrying out what the screen asked for.
 *
 * Every path re-checks what the screen already checked. The screen greys out what you cannot afford, but
 * a payload is a claim rather than evidence, and the desk is the only thing that knows the truth.
 */
object DeskCommands {

    /** Called once the screen is up, so it has something to draw before the player touches anything. */
    fun opened(player: ServerPlayer) {
        val menu = player.containerMenu as? WritersDeskMenu ?: return
        val desk = menu.deskOf(player) ?: return
        sync(player, menu, desk)
    }

    fun handle(player: ServerPlayer, payload: DeskCommandPayload) {
        val menu = player.containerMenu as? WritersDeskMenu ?: return
        val desk = menu.deskOf(player) ?: return
        when (payload.action) {
            DeskAction.WRITE_TO_ARCHIVE -> write(player, menu, desk, payload, toBook = false)
            DeskAction.WRITE_TO_BOOK -> write(player, menu, desk, payload, toBook = true)
            DeskAction.COMPOSE_FROM_ARCHIVE -> composeFromArchive(menu, desk, payload)
            DeskAction.COMPOSE_FROM_HAND -> composeFromHand(player, menu, desk, payload)
            DeskAction.RETURN_TO_ARCHIVE -> returnToArchive(menu, desk, payload)
            DeskAction.WITHDRAW -> withdraw(player, desk, payload)
            // No sync afterwards: the tab is the client's own state and it already knows.
            DeskAction.SET_TAB -> { menu.openTab = payload.index; return }
            DeskAction.PRICE -> return quote(player, payload)
            DeskAction.MOVE_IN_BOOK -> moveInBook(menu, payload)
            DeskAction.FINALISE -> finalise(player, menu, desk, payload)
        }
        sync(player, menu, desk)
    }

    /** Answers "what would this cost me?" for all three papers at once, so the buttons can each say. */
    private fun quote(player: ServerPlayer, payload: DeskCommandPayload) {
        val wordId = payload.word ?: return
        val vocabulary = Vocabulary.of(player.level().server)
        val word = vocabulary.word(wordId.toString()) ?: vocabulary.word(wordId.path) ?: return
        val registries = player.level().registryAccess()
        val units = Services.INK_FLUIDS.unitsPerBucket
        val prices = co.voik.agesandtheart.age.word.InkTier.entries.associateWith { paper ->
            val cost = WriteCost.of(word, vocabulary, registries, paper, units)
            cost.inkTier to cost.inkUnits
        }
        Services.NETWORK.sendToPlayer(player, DeskPricePayload(wordId, prices))
    }

    /** Pushes the current state to whoever has the desk open. */
    /**
     * The stores alone, for a wing — which shows no archive and no composer, so it sends neither.
     *
     * Safe to leave those empty because the desk re-syncs whenever it is opened ([opened]), so a wing
     * cannot leave the centre's screen looking at an emptied archive.
     */
    fun sync(player: ServerPlayer, desk: WritersDeskBlockEntity) {
        val workshop = WritersDesk.load(player.level().server.resourceManager, mutableListOf())
        val capabilities = desk.capabilities(workshop)
        Services.NETWORK.sendToPlayer(
            player,
            DeskSyncPayload(
                archive = emptyMap(),
                ink = InkTier.entries.associateWith { desk.stores.ink(it) },
                paper = InkTier.entries.associateWith { desk.stores.paper(it) },
                binding = desk.stores.binding(),
                inkCapacity = desk.inkCapacity,
                capabilities = capabilities.capabilities,
                pageLimit = capabilities.pageLimit,
                composing = emptyList(),
            ),
        )
    }

    fun sync(player: ServerPlayer, menu: WritersDeskMenu, desk: WritersDeskBlockEntity) {
        val workshop = WritersDesk.load(player.level().server.resourceManager, mutableListOf())
        Services.NETWORK.sendToPlayer(player, menu.snapshot(player, desk, desk.capabilities(workshop)))
    }

    private fun write(
        player: ServerPlayer,
        menu: WritersDeskMenu,
        desk: WritersDeskBlockEntity,
        payload: DeskCommandPayload,
        toBook: Boolean,
    ) {
        val wordId = payload.word ?: return
        if (!menu.knows(player, wordId)) return complain(player, "unknown_word")
        val vocabulary = Vocabulary.of(player.level().server)
        val word = vocabulary.word(wordId.toString()) ?: vocabulary.word(wordId.path) ?: return
        if (toBook && !roomInBook(player, menu, desk)) return

        val cost = WriteCost.of(
            word,
            vocabulary,
            player.level().registryAccess(),
            payload.paperTier,
            Services.INK_FLUIDS.unitsPerBucket,
        )
        if (!desk.spend(cost.inkTier, cost.inkUnits, cost.paperTier, cost.sheets)) {
            return complain(player, "cannot_afford")
        }
        // Straight into the composer when writing for a book, so the page never has to be found again.
        if (toBook) menu.composing += wordId else desk.addPages(wordId, 1)
    }

    private fun composeFromArchive(
        menu: WritersDeskMenu,
        desk: WritersDeskBlockEntity,
        payload: DeskCommandPayload,
    ) {
        val word = payload.word ?: return
        if (!desk.takePages(word, 1)) return
        menu.composing += word
    }

    /**
     * A page carried in hand, laid straight onto the work surface.
     *
     * One page off the stack, inserted where it was dropped rather than appended — the position *is* the
     * meaning, so dropping between two words has to put it between them. The rest of the stack stays in
     * hand, which is what makes laying out several in a row bearable.
     */
    private fun composeFromHand(
        player: ServerPlayer,
        menu: WritersDeskMenu,
        desk: WritersDeskBlockEntity,
        payload: DeskCommandPayload,
    ) {
        val carried = menu.carried
        if (!NotebookItem.isPage(carried)) return
        val word = carried.get(AgeContent.PAGE_WORD) ?: return
        if (!roomInBook(player, menu, desk)) return
        val at = payload.index.coerceIn(0, menu.composing.size)
        menu.composing.add(at, word)
        carried.shrink(1)
        menu.carried = carried
    }

    /**
     * Undo for a misclick. The page goes back to the archive rather than the ink coming back — the player
     * keeps something reusable, which is a better refund than the resource it was made from.
     */
    private fun returnToArchive(
        menu: WritersDeskMenu,
        desk: WritersDeskBlockEntity,
        payload: DeskCommandPayload,
    ) {
        val at = payload.index
        if (at !in menu.composing.indices) return
        desk.addPages(menu.composing.removeAt(at), 1)
    }

    /**
     * Reordering. Nothing is spent or refunded — the same pages are being read in a different order, and
     * that different order is a different Age.
     */
    private fun moveInBook(menu: WritersDeskMenu, payload: DeskCommandPayload) {
        val from = payload.index
        if (from !in menu.composing.indices) return
        // Clamped rather than rejected, so dropping past the end means "put it last".
        val to = payload.target.coerceIn(0, menu.composing.size - 1)
        if (from == to) return
        menu.composing.add(to, menu.composing.removeAt(from))
    }

    private fun withdraw(player: ServerPlayer, desk: WritersDeskBlockEntity, payload: DeskCommandPayload) {
        val word = payload.word ?: return
        if (!desk.takePages(word, 1)) return
        val page = ItemStack(AgeContent.PAGE)
        page.set(AgeContent.PAGE_WORD, word)
        if (!player.inventory.add(page)) player.drop(page, false)
    }

    private fun roomInBook(player: ServerPlayer, menu: WritersDeskMenu, desk: WritersDeskBlockEntity): Boolean {
        val workshop = WritersDesk.load(player.level().server.resourceManager, mutableListOf())
        val limit = desk.capabilities(workshop).pageLimit ?: return true
        if (menu.composing.size < limit) return true
        complain(player, "book_full")
        return false
    }

    /**
     * Binds what is laid out.
     *
     * The words are stored on the book **in order**, because page order is word order — the sentence is
     * the book, and resolving it is the book's own job when someone opens it.
     */
    private fun finalise(
        player: ServerPlayer,
        menu: WritersDeskMenu,
        desk: WritersDeskBlockEntity,
        payload: DeskCommandPayload,
    ) {
        if (menu.composing.isEmpty()) return complain(player, "no_pages")
        val title = payload.title.trim()
        if (title.isEmpty()) return complain(player, "no_name")
        if (!menu.outputIsFree()) return complain(player, "output_full")
        // Last, so nothing is consumed until every other check has passed.
        if (!desk.spendBinding()) return complain(player, "no_binding")

        // No ink here on purpose: it was spent writing each page, and charging again at the binding
        // would tax the same words twice.
        val book = ItemStack(AgeContent.DESCRIPTIVE_BOOK)
        val words = menu.composing.toList()
        book.set(AgeContent.BOOK_WORDS, words)
        book.set(AgeContent.BOOK_TITLE, title)
        // What it says, and what that means, written down beside the pages it is spelled out of. Reading a
        // sentence takes the whole corpus, which is a server's; a book is read wherever it is carried.
        // Read by **the same expression `DescriptiveBookRecipe` reads it by**, so what a book says and the
        // Age it makes can never be two different sentences.
        val sentence = Grammar.read(WritersDeskMenu.vocabularyFor(player), words.map { it.path })
        book.set(AgeContent.BOOK_READING, Readout.columnsOf(sentence))
        menu.composing.clear()
        // Into the output slot rather than the inventory: a book you take is a book you saw being made.
        menu.putOutput(book)
        Constants.LOG.debug("{} bound the Age '{}'", player.gameProfile.name, title)
    }

    /** To the screen, not the action bar: an open screen covers the action bar. */
    private fun complain(player: ServerPlayer, reason: String) {
        Services.NETWORK.sendToPlayer(player, DeskNoticePayload(reason))
    }
}
