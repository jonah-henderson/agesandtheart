package co.voik.agesandtheart.desk

import net.minecraft.util.Prediction
import co.voik.agesandtheart.Constants
import co.voik.agesandtheart.advancement.AgeTriggers
import co.voik.agesandtheart.advancement.WrittenAge
import co.voik.agesandtheart.age.word.Resolver
import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.age.word.grammar.Grammar
import co.voik.agesandtheart.age.word.grammar.Prose
import co.voik.agesandtheart.book.panel.PanelWarming
import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import co.voik.agesandtheart.platform.Services
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.MenuProvider
import net.minecraft.world.SimpleMenuProvider
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
        AgeTriggers.DESK_FURNISHED.trigger(player, desk.capabilities(WritersDesk.of(player.level().server)).rung)
        sync(player, menu, desk)
    }

    /** The writer typed: keep it on the desk, and say back what it reads as. */
    fun template(player: ServerPlayer, payload: DeskTemplatePayload) {
        val menu = player.containerMenu as? WritersDeskMenu ?: return
        val desk = menu.deskOf(player) ?: return
        desk.setTemplate(player.uuid, payload.text)
        sync(player, menu, desk)
    }

    /**
     * The stores alone, for a wing — which shows no template, so it sends none.
     *
     * Safe to leave the rest empty because the desk re-syncs whenever it is opened ([opened]).
     */
    fun sync(player: ServerPlayer, desk: WritersDeskBlockEntity) {
        val capabilities = desk.capabilities(WritersDesk.of(player.level().server))
        Services.NETWORK.sendToPlayer(
            player,
            DeskSyncPayload(
                ink = InkTier.entries.associateWith { desk.stores.ink(it) },
                paper = InkTier.entries.associateWith { desk.stores.paper(it) },
                binding = desk.stores.binding(),
                inkCapacity = desk.inkCapacity,
                capabilities = capabilities.capabilities,
                pageLimit = capabilities.pageLimit,
                template = "",
                read = emptyList(),
                toWrite = emptyList(),
                drawn = 0,
                quarrels = emptyList(),
                reading = emptyList(),
            ),
        )
    }

    /** Pushes the desk's state to the screen. */
    fun sync(player: ServerPlayer, menu: WritersDeskMenu, desk: WritersDeskBlockEntity) {
        val capabilities = desk.capabilities(WritersDesk.of(player.level().server))
        Services.NETWORK.sendToPlayer(player, menu.snapshot(player, desk, capabilities))
    }

    /**
     * Pays for the template and binds it — **the whole cost or nothing** (Jonah, 2026-09-18).
     *
     * Every check comes before anything is taken, so a refusal spends nothing. What is taken, in order:
     * written pages from archives in the room, then from the writer's inventory, then ink and paper for
     * every page neither held, and one binding.
     *
     * The words are stored on the book **in order**, because word order is the sentence — and resolving it
     * is the book's own job when someone opens it.
     */
    fun bind(player: ServerPlayer, payload: DeskBindPayload) {
        val menu = player.containerMenu as? WritersDeskMenu ?: return
        val desk = menu.deskOf(player) ?: return
        refusalFor(player, desk, payload)?.let { return complain(player, it) }
        val words = TemplateReading.learnedWords(DeskTemplates.read(player, desk.templateFor(player.uuid)))
        val sentence = Grammar.read(WritersDeskMenu.vocabularyFor(player), words.map { it.path })
            ?: return complain(player, "no_age")

        val supply = WritersDeskMenu.supplyFor(player, desk)
        val allocation = supply.allocate(words)
        val prices = allocation.toWrite.map { WritersDeskMenu.pricePage(player, it) }
        val cost = BookCost.of(prices, payload.paper)
        if (!cost.affordableFrom(desk.stores)) return complain(player, "cannot_afford")
        // Pages first: they are the one part that can have moved since the check, and a supply that
        // changed under the writer must spend nothing.
        if (!supply.take(allocation)) return complain(player, "pages_moved")
        desk.pay(cost)

        val book = ItemStack(AgeContent.DESCRIPTIVE_BOOK)
        book.set(AgeComponents.BOOK_WORDS, words)
        book.set(AgeComponents.BOOK_TITLE, payload.title.trim())
        // The seed the desk has been predicting against, written down before it is rerolled — so the Age
        // this book makes is the one whose conflicts the writer was shown.
        book.set(AgeComponents.BOOK_SEED, player.writingSeed)
        // Read by the same expression `DescriptiveBookRecipe` reads it by, so what a book says and the Age
        // it makes can never be two different sentences.
        book.set(AgeComponents.BOOK_READING, Prose.of(sentence))
        // The one place a book is marked as somebody's own work (design §7.7).
        book.set(AgeComponents.BOOK_AUTHORED, true)
        // A crystal viewer's preview of exactly this sentence becomes the book's Age; any other is deleted.
        PreviewedAges.claim(player, words, player.writingSeed, payload.title.trim())
            ?.let { book.set(AgeComponents.AGE_ID, it) }
        desk.setTemplate(player.uuid, "")
        // Straight to the writer: there is no slot to lift it out of, since nothing is handled at the desk.
        player.inventory.placeItemBackInInventory(book, Prediction.SERVER_ONLY)
        // The earliest an Age can be made ready: the writer is still at the desk, so the terrain is
        // generated while nobody is waiting on it.
        PanelWarming.whenBound(player.level().server, book)
        // A fresh world for the next book: one desk read over and over must not hand out the same Age.
        val seed = player.writingSeed
        AgeTriggers.WROTE_AGE.trigger(player, WrittenAge(words) {
            Resolver.resolve(WritersDeskMenu.vocabularyFor(player), sentence, seed).composition
        })
        player.rerollWritingSeed()
        sync(player, menu, desk)
        Constants.LOG.debug("{} bound the Age '{}'", player.gameProfile.name, payload.title)
    }

    /** Why [payload] cannot be bound, or null if nothing stands in the way but the cost. */
    private fun refusalFor(player: ServerPlayer, desk: WritersDeskBlockEntity, payload: DeskBindPayload): String? {
        val read = DeskTemplates.read(player, desk.templateFor(player.uuid))
        val limit = desk.capabilities(WritersDesk.of(player.level().server)).pageLimit
        val overTheLimit = limit != null && read.size > limit
        return when {
            read.isEmpty() -> "no_pages"
            read.any { it.state == WordState.UNKNOWN } -> "unknown_words"
            read.any { it.state == WordState.UNLEARNED } -> "unlearned_words"
            overTheLimit -> "book_full"
            payload.title.isBlank() -> "no_name"
            else -> null
        }
    }

    /** To the screen, not the action bar: an open screen covers the action bar. */
    private fun complain(player: ServerPlayer, reason: String) = say(player, reason)

    /** A line at the foot of whichever desk screen is open — usually a refusal. */
    fun say(player: ServerPlayer, line: String) {
        Services.NETWORK.sendToPlayer(player, DeskNoticePayload(line))
    }
}
