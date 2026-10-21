package co.voik.agesandtheart.page

import co.voik.agesandtheart.advancement.AgeTriggers
import co.voik.agesandtheart.advancement.LearnedBy
import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.NotebookItem
import co.voik.agesandtheart.content.UnintelligiblePageItem
import co.voik.agesandtheart.platform.Services
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerListener
import net.minecraft.world.inventory.ResultSlot
import net.minecraft.world.item.ItemStack
import co.voik.agesandtheart.age.word.LearnedWordsPayload
import co.voik.agesandtheart.age.word.LexiconPayload
import co.voik.agesandtheart.age.word.Vocabulary
import co.voik.agesandtheart.age.word.learnedWords

/**
 * Learning a word by holding the page it is written on, or by reading a book somebody wrote with it.
 *
 * **You learn what you read, and there is no gate beyond that** (design §4.5). Mastery is not a lock on
 * words — it is knowing *how* to use them, which comes from studying well-formed books. So both routes here
 * teach outright, and the second is the only way most writers will ever meet `and`, `only`, `except` and
 * the rungs: nobody is handed those pages.
 *
 * The seam for the first is `ContainerListener`, which is what vanilla fires its `inventory_changed`
 * advancement trigger from — so it catches every route a stack can arrive by, and needs no polling.
 */
object PageLearning {

    /**
     * Watches one player's inventory slots.
     *
     * The two filters are vanilla's own: a `ResultSlot` holds a crafting *preview*, which is not yet the
     * player's, and slots belonging to some other container are the chest they are looking into.
     *
     * **The stack a listener is handed is vanilla's copy of the slot**, the one it keeps to notice the next
     * change and sends to the client, so a page drawn for its finder is written on the slot's own item.
     *
     * Any slot the player can see, a chest's included, has its gone words cleared — a page made unintelligible, a
     * notebook's pages with it — since a menu opening hands every slot to its listeners.
     */
    @JvmStatic
    fun listenerFor(player: ServerPlayer): ContainerListener = object : ContainerListener {
        override fun slotChanged(container: AbstractContainerMenu, slotIndex: Int, itemStack: ItemStack) {
            val slot = container.getSlot(slotIndex)
            if (slot is ResultSlot) return
            val cleared = UnintelligiblePageItem.clearedOfGoneWords(slot.item, Vocabulary.of(player.level().server))
            if (cleared != null) return slot.set(cleared)
            if (slot.container !== player.inventory) return
            val held = slot.item
            PageWordFunction.writeForFinder(player, held)
            observe(player, held)
        }

        override fun dataChanged(container: AbstractContainerMenu, id: Int, value: Int) = Unit
    }

    /**
     * Learns whatever [stack] has written on it — a page's own word, or every page a notebook is holding.
     *
     * **A notebook is a stack of pages and teaches like one.** It was a way to carry pages without ever
     * reading them: a full one could be tipped straight into a desk, and the words went in unlearned to an
     * archive that lists what you know, where nothing could see or reach them again.
     */
    fun observe(player: ServerPlayer, stack: ItemStack) {
        val page = stack.get(AgeComponents.PAGE_WORD)
        if (page != null) {
            teach(player, listOf(page))
            return
        }
        if (stack.item === AgeContent.NOTEBOOK) {
            teach(player, NotebookItem.pagesIn(stack).mapNotNull { it.get(AgeComponents.PAGE_WORD) })
        }
    }

    /**
     * Learns each of [words] the player did not already know, says so once, and answers which were new.
     *
     * One packet however many are learned, because these arrive by the notebook and the deskful now — and
     * the toast cycles through a batch where a packet each would raise a wall of them.
     */
    fun teach(player: ServerPlayer, words: Collection<Identifier>, by: LearnedBy = LearnedBy.READING): List<Identifier> {
        val vocabulary = Vocabulary.of(player.level().server)
        val stillWords = words.filter(vocabulary::stillNames)
        val learned = stillWords.map { vocabulary.successorId(it) ?: it }.distinct().filter(player.learnedWords::learn)
        if (learned.isNotEmpty()) Services.NETWORK.sendToPlayer(player, LearnedWordsPayload.added(learned))
        for (word in learned) AgeTriggers.LEARNED_WORD.trigger(player, word, by)
        return learned
    }

    /**
     * Learns every word a book was written with — **opening it is reading it**, since the screen is the
     * closest thing to studying the game has.
     *
     * One packet rather than one per word: a book teaches a dozen at once, and the toast cycles.
     */
    fun study(player: ServerPlayer, book: ItemStack) {
        teach(player, book.get(AgeComponents.BOOK_WORDS).orEmpty())
    }

    /**
     * Tells a joining player what they know, having carried any retired word they learned over to its successor and
     * forgotten any that is gone.
     *
     * Sent even though the client could not have forgotten it: a client may have connected to another
     * server since.
     */
    fun tellEverything(player: ServerPlayer) {
        val vocabulary = Vocabulary.of(player.level().server)
        player.learnedWords.migrate(vocabulary::successorId)
        player.learnedWords.forgetGone(vocabulary::stillNames)
        Services.NETWORK.sendToPlayer(player, LearnedWordsPayload.whole(player.learnedWords.words))
    }

    /**
     * Tells [player] how words are written and where each reaches — **on joining and again on every
     * `/reload`**, since both are datapack content and a page's tooltip read off a stale copy would
     * contradict the desk.
     */
    fun tellTheLexicon(player: ServerPlayer) {
        Services.NETWORK.sendToPlayer(player, LexiconPayload.of(Vocabulary.of(player.level().server)))
    }
}
