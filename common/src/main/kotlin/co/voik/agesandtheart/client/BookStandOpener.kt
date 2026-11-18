package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.BookBeingRead
import co.voik.agesandtheart.book.BookFinderBlockEntity
import co.voik.agesandtheart.book.BookStandAskPayload
import co.voik.agesandtheart.book.StandAsked
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.BookViewScreen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack

/** Opens the screen of a book on a stand of ours, kept apart so the blocks never name a client class directly. */
object BookStandOpener {

    /** A book of ours lying open on a stand, read as one on a lectern is. */
    fun readOurs(book: ItemStack, onAStand: BookBeingRead.OnAStand) {
        Minecraft.getInstance().setScreenAndShow(BookScreen(book, onAStand))
    }

    /** A written book on a stand, in vanilla's pages with the stand's buttons under them. */
    fun readWritten(book: ItemStack, stand: BlockPos, place: Int) {
        Minecraft.getInstance().setScreenAndShow(StandBookViewScreen(book, stand, place))
    }
}

/**
 * Vanilla's written book, read from a stand of ours: **Done**, **Take Book** and **Close Book** under it as under a
 * lectern's — **Send Back** on a book finder, where shutting it files it home.
 */
class StandBookViewScreen(book: ItemStack, private val stand: BlockPos, private val place: Int) :
    BookViewScreen(BookAccess.fromItem(book) ?: BookAccess(emptyList())) {

    override fun createMenuControls() {
        val buttonWidth = (ROW_WIDTH - GAP * 2) / BUTTONS
        val left = (width - ROW_WIDTH) / 2
        val top = menuControlsTop()
        val isAFinder = Minecraft.getInstance().level?.getBlockEntity(stand) is BookFinderBlockEntity
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE) { onClose() }.bounds(left, top, buttonWidth, BUTTON_HEIGHT).build())
        addRenderableWidget(
            Button.builder(Component.translatable(TAKE_BOOK)) { asked(StandAsked.TAKE) }
                .bounds(left + buttonWidth + GAP, top, buttonWidth, BUTTON_HEIGHT).build(),
        )
        addRenderableWidget(
            Button.builder(Component.translatable(if (isAFinder) SEND_BACK else CLOSE_BOOK)) { asked(StandAsked.CLOSE) }
                .bounds(left + (buttonWidth + GAP) * 2, top, buttonWidth, BUTTON_HEIGHT).build(),
        )
    }

    private fun asked(asked: StandAsked) {
        sendToServer(BookStandAskPayload(stand, place, asked))
        onClose()
    }

    private companion object {
        const val TAKE_BOOK = "lectern.take_book"
        const val CLOSE_BOOK = "book.agesandtheart.close_book"
        const val SEND_BACK = "book.agesandtheart.send_back"
        const val BUTTONS = 3
        const val GAP = 4
        const val ROW_WIDTH = BUTTONS * 80 + (BUTTONS - 1) * GAP
        const val BUTTON_HEIGHT = 20
    }
}
