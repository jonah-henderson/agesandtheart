package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.age.word.PageLearning
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.age.word.grammar.Readout
import co.voik.agesandtheart.book.panel.PanelWarming
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
        readTheArchive(player, desk)
        // The pages this writer left on this desk, back on the surface they were left on.
        menu.composing.clear()
        menu.composing += desk.compositionFor(player.uuid)
        sync(player, menu, desk)
    }

    /**
     * **Opening the archive is reading it** (design §4.5), so every word filed here is learned.
     *
     * The desk opens on this tab and it is the tab the pages are on, so a writer sitting down at a desk
     * has read what is in it — including, at a shared desk, whatever somebody else filed. That is the
     * same rule as picking a page up off the ground: you learn what passes through your hands, and pages
     * you are sorting through pass through them.
     *
     * It is also what stops the archive hiding its own contents. Rows are the words you *know* against
     * this desk's counts, so a page for a word you had never met was in there, counted by the reading and
     * invisible to the person it belonged to.
     */
    private fun readTheArchive(player: ServerPlayer, desk: WritersDeskBlockEntity) {
        PageLearning.teach(player, desk.archive.words)
    }

    fun handle(player: ServerPlayer, payload: DeskCommandPayload) {
        val menu = player.containerMenu as? WritersDeskMenu ?: return
        val desk = menu.deskOf(player) ?: return
        when (payload.action) {
            DeskAction.WRITE_TO_ARCHIVE -> write(player, menu, desk, payload, toBook = false)
            DeskAction.WRITE_TO_BOOK -> write(player, menu, desk, payload, toBook = true)
            DeskAction.COMPOSE_FROM_ARCHIVE -> composeFromArchive(player, menu, desk, payload)
            DeskAction.COMPOSE_FROM_HAND -> composeFromHand(player, menu, desk, payload)
            DeskAction.RETURN_TO_ARCHIVE -> returnToArchive(menu, desk, payload)
            DeskAction.WITHDRAW -> withdraw(player, desk, payload)
            // No sync afterwards: the tab is the client's own state and it already knows. Turning *to*
            // the archive is reading it, and what that teaches goes out on its own packet.
            DeskAction.SET_TAB -> {
                menu.openTab = payload.index
                if (payload.index == WritersDeskMenu.ARCHIVE_TAB) readTheArchive(player, desk)
                return
            }
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
                quarrels = emptyList(),
                reading = "",
            ),
        )
    }

    /**
     * Pushes the desk's state to the screen — and writes the laid-out pages down on the way past.
     *
     * Every action ends here, so this is the one place a composition has to be saved from: the menu is
     * where the pages are being moved and the block entity is what survives a chunk unloading. Persisting
     * per action rather than only on close means a crash costs the last click rather than the sentence.
     */
    fun sync(player: ServerPlayer, menu: WritersDeskMenu, desk: WritersDeskBlockEntity) {
        desk.setComposition(player.uuid, menu.composing)
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
        // The writer may pour better ink than the word demands, never worse — `satisfies` is the same
        // rule the desk checks when it spends, so asking for common ink on a diamond changes nothing.
        val spending = if (payload.inkTier.satisfies(cost.inkTier)) payload.inkTier else cost.inkTier
        if (!desk.spend(spending, cost.inkUnits, cost.paperTier, cost.sheets)) {
            return complain(player, "cannot_afford")
        }
        // Straight into the composer when writing for a book, so the page never has to be found again.
        if (toBook) menu.composing += wordId else desk.addPages(wordId, 1)
    }

    /**
     * The cap is checked **before the page is taken**, or a full book would swallow one out of the archive
     * and put it nowhere — and it is checked at all because this was the one route into the composer that
     * did not: pages laid this way ran past what the desk could bind, where the work surface draws only as
     * many as it can, so a writer had pages they could not see and a reading that counted them.
     */
    private fun composeFromArchive(
        player: ServerPlayer,
        menu: WritersDeskMenu,
        desk: WritersDeskBlockEntity,
        payload: DeskCommandPayload,
    ) {
        val word = payload.word ?: return
        if (!roomInBook(player, menu, desk)) return
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
        val words = menu.composing.toList()
        // **The Art's one refusal** (§4.3.1): a book opens with the `age` page or it is not a book, and
        // this is not repairable — without it an empty book would be a free reroll on a random Age.
        // Read up here because it is also what the book carries, so what it says and the Age it makes can
        // never be two different sentences.
        val sentence = Grammar.read(WritersDeskMenu.vocabularyFor(player), words.map { it.path })
            ?: return complain(player, "no_age")
        val title = payload.title.trim()
        if (title.isEmpty()) return complain(player, "no_name")
        if (!menu.outputIsFree()) return complain(player, "output_full")
        // Last, so nothing is consumed until every other check has passed.
        if (!desk.spendBinding()) return complain(player, "no_binding")

        // No ink here on purpose: it was spent writing each page, and charging again at the binding
        // would tax the same words twice.
        val book = ItemStack(AgeContent.DESCRIPTIVE_BOOK)
        book.set(AgeContent.BOOK_WORDS, words)
        book.set(AgeContent.BOOK_TITLE, title)
        // The seed the desk has been predicting against, written down before it is rerolled — so the Age
        // this book makes is the one whose conflicts the writer was shown.
        book.set(AgeContent.BOOK_SEED, player.writingSeed)
        // What it says, and what that means, written down beside the pages it is spelled out of. Reading a
        // sentence takes the whole corpus, which is a server's; a book is read wherever it is carried.
        // Read by **the same expression `DescriptiveBookRecipe` reads it by**, so what a book says and the
        // Age it makes can never be two different sentences.
        book.set(AgeContent.BOOK_READING, Readout.columnsOf(sentence))
        menu.composing.clear()
        // Into the output slot rather than the inventory: a book you take is a book you saw being made.
        menu.putOutput(book)
        // The earliest an Age can be made ready, and the whole point of doing it here: the writer is still
        // looking at the desk, so the terrain is generated while nobody is waiting on it.
        PanelWarming.whenBound(player.level().server, book)
        // A fresh world for the next book: one desk read over and over must not hand out the same Age.
        player.rerollWritingSeed()
        Constants.LOG.debug("{} bound the Age '{}'", player.gameProfile.name, title)
    }

    /** To the screen, not the action bar: an open screen covers the action bar. */
    private fun complain(player: ServerPlayer, reason: String) = say(player, reason)

    /**
     * A line at the foot of whichever desk screen is open.
     *
     * Usually a refusal, but not always: a wing that files something into the archive has moved it
     * somewhere the wing cannot show, and an item that vanishes with nothing said reads as an item lost.
     */
    fun say(player: ServerPlayer, line: String) {
        Services.NETWORK.sendToPlayer(player, DeskNoticePayload(line))
    }
}
