package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.content.AgeContent
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.toasts.Toast
import net.minecraft.client.gui.components.toasts.ToastManager
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack

/**
 * "Word Learned", the way vanilla says "Recipe Unlocked".
 *
 * **One toast for however many words arrive**, cycling through them, rather than a queue of identical
 * boxes — emptying a notebook can teach a dozen at once, and twelve toasts in a row is a wall rather than
 * a notification. Modelled on `RecipeToast`, which solves exactly this for the same reason.
 */
class WordToast : Toast {
    private val learned = mutableListOf<Identifier>()
    private var lastChanged = 0L
    private var changed = false
    private var wanted = Toast.Visibility.HIDE
    private var showing = 0

    override fun getWantedVisibility(): Toast.Visibility = wanted

    /**
     * What [show] looks one up by. Without it a toast answers `Toast.NO_TOKEN`, no lookup ever matches,
     * and every word learned raises a box of its own — which is the wall this class exists to avoid.
     */
    override fun getToken(): Any = TOKEN

    override fun update(manager: ToastManager, fullyVisibleForMs: Long) {
        if (changed) {
            lastChanged = fullyVisibleForMs
            changed = false
        }
        val window = DISPLAY_TIME * manager.notificationDisplayTimeMultiplier
        wanted = if (learned.isEmpty() || fullyVisibleForMs - lastChanged >= window) {
            Toast.Visibility.HIDE
        } else {
            Toast.Visibility.SHOW
        }
        // Split the window evenly between however many words are waiting.
        if (learned.isNotEmpty()) {
            val each = Math.max(1.0, window / learned.size)
            showing = ((fullyVisibleForMs / each) % learned.size).toInt()
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, font: Font, fullyVisibleForMs: Long) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, width(), height())
        val title = if (learned.size > 1) MANY_TITLE else ONE_TITLE
        graphics.text(font, title, TEXT_X, TITLE_Y, TITLE_COLOUR, false)
        if (learned.isEmpty()) return
        val word = learned[showing.coerceIn(learned.indices)]
        graphics.text(font, WordNames.readable(word), TEXT_X, WORD_Y, WORD_COLOUR, false)
        graphics.fakeItem(pageOf(word), ICON_X, ICON_Y)
    }

    private fun add(word: Identifier) {
        if (word in learned) return
        learned += word
        changed = true
    }

    /** The page it was written on, so the toast shows the object rather than an abstraction. */
    private fun pageOf(word: Identifier): ItemStack {
        val page = ItemStack(AgeContent.PAGE)
        page.set(AgeContent.PAGE_WORD, word)
        return page
    }

    companion object {
        private val BACKGROUND: Identifier = Identifier.withDefaultNamespace("toast/recipe")
        private val ONE_TITLE: Component = Component.translatable("toast.agesandtheart.word_learned")
        private val MANY_TITLE: Component = Component.translatable("toast.agesandtheart.words_learned")

        private const val DISPLAY_TIME = 5000.0
        private const val TEXT_X = 30
        private const val TITLE_Y = 7
        private const val WORD_Y = 18
        private const val ICON_X = 8
        private const val ICON_Y = 8
        private const val TITLE_COLOUR = -11534256
        private const val WORD_COLOUR = -16777216

        /** Distinguishes our toast from any other of the same class; there is only ever one. */
        private val TOKEN = Any()

        /** Folds into the toast already showing, if there is one. */
        fun show(manager: ToastManager, word: Identifier) {
            val existing = manager.getToast(WordToast::class.java, TOKEN)
            val toast = existing ?: WordToast().also { manager.addToast(it) }
            toast.add(word)
        }
    }
}
