package co.voik.agesandtheart.content

import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.SlotAccess
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ClickAction
import net.minecraft.world.inventory.Slot
import net.minecraft.world.inventory.tooltip.BundleTooltip
import net.minecraft.world.inventory.tooltip.TooltipComponent
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.item.component.BundleContents
import net.minecraft.world.item.component.TooltipDisplay
import java.util.Optional
import java.util.function.Consumer

/**
 * Holds pages, and behaves like a bundle doing it.
 *
 * The clicks are the point: right-click a page onto a notebook to file it, right-click a notebook onto a
 * slot to take one out. Without those you can only tip the whole thing out, which is useless when what
 * you want is the one page you are looking for.
 *
 * **Unlimited, which is why it is not literally a bundle.** A bundle's capacity lives in a private weight
 * check, so reusing `BundleContents` would cap this at sixty-four pages — plenty for a pocket, nowhere
 * near enough for a catalogue of a corpus that runs to four figures. The contents are ours; the *tooltip* is the
 * bundle's, handed a window of the pages that follows the one scrolled to, so the whole notebook can be scrolled.
 *
 * Pages stay pages throughout. A found page is what a book gets written from, so filing one must never
 * dissolve it into a list of words.
 */
class NotebookItem(properties: Properties) : Item(properties) {

    /**
     * Clicking with the notebook carried, onto a slot — **a bundle's controls**: a left click on a page files it,
     * and a right click on an empty slot puts the most recently filed page back out. A right click on another
     * notebook moves every page into it.
     */
    override fun overrideStackedOnOther(
        self: ItemStack,
        slot: Slot,
        clickAction: ClickAction,
        player: Player,
    ): Boolean {
        val held = pagesIn(self)
        val target = slot.item
        val filesIt = clickAction == ClickAction.PRIMARY && !target.isEmpty
        val takesOneOut = clickAction == ClickAction.SECONDARY && target.isEmpty
        val movesEveryPage = clickAction == ClickAction.SECONDARY && target.item === AgeContent.NOTEBOOK
        return when {
            movesEveryPage -> {
                if (held.isEmpty() || !slot.allowModification(player)) return false
                setPages(target, pagesIn(target) + held)
                slot.setChanged()
                setPages(self, emptyList())
                select(self, NotebookSelectPayload.NONE)
                play(player, true)
                true
            }
            filesIt -> {
                if (!isPage(target)) return false
                setPages(self, held + slot.safeTake(target.count, target.count, player))
                play(player, true)
                true
            }
            takesOneOut -> {
                val top = held.lastOrNull() ?: return false
                if (!slot.mayPlace(top)) return false
                slot.setByPlayer(top)
                setPages(self, held.dropLast(1))
                play(player, false)
                true
            }
            else -> false
        }
    }

    /**
     * Clicking onto the notebook where it sits in a slot — **a bundle's controls**: a left click with a page files
     * it, and a right click with an empty hand takes out the page scrolled to, or the top one. A left click with
     * nothing picks the notebook up, as it does a bundle.
     */
    override fun overrideOtherStackedOnMe(
        self: ItemStack,
        other: ItemStack,
        slot: Slot,
        clickAction: ClickAction,
        player: Player,
        carriedItem: SlotAccess,
    ): Boolean {
        if (!slot.allowModification(player)) return false
        val held = pagesIn(self)
        val filesIt = clickAction == ClickAction.PRIMARY && !other.isEmpty
        val takesOneOut = clickAction == ClickAction.SECONDARY && other.isEmpty
        return when {
            filesIt -> {
                if (!isPage(other)) return false
                setPages(self, held + other.copy())
                other.setCount(0)
                play(player, true)
                true
            }
            takesOneOut -> {
                if (held.isEmpty()) return false
                val newestFirst = selectedIn(self) ?: 0
                val taken = held.size - 1 - newestFirst
                carriedItem.set(held[taken])
                setPages(self, held.filterIndexed { index, _ -> index != taken })
                select(self, newestFirst.coerceAtMost(held.size - 2))
                play(player, false)
                true
            }
            else -> false
        }
    }

    /**
     * Vanilla's bundle grid over the pages, newest first, from its window: the grid shows what fits and counts the
     * rest, and highlights and names the page scrolled to, as it does a bundle's.
     */
    override fun getTooltipImage(itemStack: ItemStack): Optional<TooltipComponent> {
        val newestFirst = pagesIn(itemStack).reversed()
        if (newestFirst.isEmpty()) return Optional.empty()
        val selected = selectedIn(itemStack)
        val firstShown = selected?.let { windowAround(it, windowIn(itemStack), newestFirst.size) } ?: 0
        val shown = newestFirst.drop(firstShown).map(ItemStackTemplate::fromNonEmptyStack)
        val contents = BundleContents(shown)
        val withSelection = if (selected == null) contents else contents.asMutable().also { it.toggleSelectedItem(selected - firstShown) }.toImmutable()
        return Optional.of(BundleTooltip(withSelection))
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun appendHoverText(
        stack: ItemStack,
        context: TooltipContext,
        display: TooltipDisplay,
        builder: Consumer<Component>,
        flag: TooltipFlag,
    ) {
        val held = pagesIn(stack)
        if (held.isEmpty()) {
            builder.accept(Component.translatable("item.agesandtheart.notebook.empty"))
            return
        }
        builder.accept(Component.translatable("item.agesandtheart.notebook.count", held.sumOf { it.count }))
    }

    // No fill bar. A bar states a proportion and this has no full, so it could only ever have said
    // "not empty" — which the tooltip already says, in words, with the count.

    private fun play(player: Player, filing: Boolean) {
        val sound = if (filing) SoundEvents.BUNDLE_INSERT else SoundEvents.BUNDLE_REMOVE_ONE
        player.playSound(sound, SOUND_VOLUME, SOUND_PITCH)
    }

    companion object {
        /** The bundle grid's width, which a window scrolls by a row of. */
        private const val GRID_COLUMNS = 4

        /** How many cells the bundle grid has, one of which counts what is hidden when it overflows. */
        private const val GRID_CELLS = 12
        private const val SOUND_VOLUME = 0.8f
        private const val SOUND_PITCH = 0.8f

        fun isPage(stack: ItemStack): Boolean =
            stack.item === AgeContent.PAGE && stack.get(AgeComponents.PAGE_WORD) != null

        /** Newest last, so "take one out" returns what you most recently put in. */
        fun pagesIn(notebook: ItemStack): List<ItemStack> =
            notebook.getOrDefault(AgeComponents.NOTEBOOK_PAGES, emptyList())

        fun setPages(notebook: ItemStack, pages: List<ItemStack>) {
            if (pages.isEmpty()) notebook.remove(AgeComponents.NOTEBOOK_PAGES)
            else notebook.set(AgeComponents.NOTEBOOK_PAGES, pages.map { it.copy() })
        }

        /** The page scrolled to, newest first, where one is and it is still there. */
        fun selectedIn(notebook: ItemStack): Int? =
            notebook.get(AgeComponents.NOTEBOOK_SELECTED)?.takeIf { it in pagesIn(notebook).indices }

        private fun windowIn(notebook: ItemStack): Int = notebook.getOrDefault(AgeComponents.NOTEBOOK_WINDOW, 0)

        /** Scrolls [notebook] to the page [newestFirst] places, or to none for anything out of its pages. */
        fun select(notebook: ItemStack, newestFirst: Int) {
            if (notebook.item !== AgeContent.NOTEBOOK) return
            val pages = pagesIn(notebook).size
            if (newestFirst in 0..<pages) {
                notebook.set(AgeComponents.NOTEBOOK_SELECTED, newestFirst)
                notebook.set(AgeComponents.NOTEBOOK_WINDOW, windowAround(newestFirst, windowIn(notebook), pages))
            } else {
                notebook.remove(AgeComponents.NOTEBOOK_SELECTED)
                notebook.remove(AgeComponents.NOTEBOOK_WINDOW)
            }
        }

        /**
         * The first page a grid over [pages] shows with [selected] scrolled to, moved from [first] by as few rows as
         * bring it into view — so the highlight crosses the grid, and the grid moves only once it reaches an edge.
         */
        fun windowAround(selected: Int, first: Int, pages: Int): Int {
            val startOfItsRow = selected / GRID_COLUMNS * GRID_COLUMNS
            fun showsIt(start: Int) = selected < start + shownOf(pages - start)
            return generateSequence(minOf(first, startOfItsRow)) { it + GRID_COLUMNS }.first(::showsIt)
        }

        /** How many of [remaining] pages vanilla's bundle grid shows, as `BundleContents.getNumberOfItemsToShow` counts. */
        private fun shownOf(remaining: Int): Int {
            val cellsForPages = if (remaining > GRID_CELLS) GRID_CELLS - 1 else GRID_CELLS
            val gapInThePartRow = (GRID_COLUMNS - remaining % GRID_COLUMNS) % GRID_COLUMNS
            return minOf(remaining, cellsForPages - gapInThePartRow)
        }
    }
}
