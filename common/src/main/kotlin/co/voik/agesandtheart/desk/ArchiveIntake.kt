package co.voik.agesandtheart.desk

import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.NotebookItem
import net.minecraft.world.item.ItemStack

/**
 * Filing something into an archive.
 *
 * One place, because there are three doors — using an item on the block, shift-clicking it in the
 * archive's screen, and a hopper — and they must agree about what is accepted.
 */
object ArchiveIntake {

    /** Whether the archive would take this, so a door can refuse it before anything moves. */
    fun accepts(stack: ItemStack): Boolean = NotebookItem.isPage(stack) || holdsPages(stack)

    /**
     * Offers [stack] to [archive]: a stack of pages is filed whole, and a notebook is tipped out and handed
     * back empty — the pages inside are pages, and the notebook is the folder rather than part of the
     * contents. One notebook at a time, since each emptied one is a separate item to hand back.
     */
    fun offer(archive: ArchiveBlockEntity, stack: ItemStack): DeskIntake.Result {
        if (stack.isEmpty) return DeskIntake.Result.untouched(stack)
        if (NotebookItem.isPage(stack)) {
            val word = stack.get(AgeComponents.PAGE_WORD) ?: return DeskIntake.Result.untouched(stack)
            archive.file(word, stack.count)
            return DeskIntake.Result.consumed()
        }
        if (!holdsPages(stack)) return DeskIntake.Result.untouched(stack)
        for (page in NotebookItem.pagesIn(stack)) {
            val word = page.get(AgeComponents.PAGE_WORD) ?: continue
            archive.file(word, page.count)
        }
        val emptied = stack.copyWithCount(1)
        NotebookItem.setPages(emptied, emptyList())
        return DeskIntake.Result(stack.copyWithCount(stack.count - 1), emptied, took = true)
    }

    private fun holdsPages(stack: ItemStack): Boolean =
        stack.item === AgeContent.NOTEBOOK && NotebookItem.pagesIn(stack).any(NotebookItem::isPage)
}
