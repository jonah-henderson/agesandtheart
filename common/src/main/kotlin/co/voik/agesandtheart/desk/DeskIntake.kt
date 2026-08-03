package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.NotebookItem
import co.voik.agesandtheart.location
import co.voik.agesandtheart.platform.Services
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.world.item.BucketItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Putting something into the desk.
 *
 * One place, because there are two doors into it — the UI's input slot and using an item on the block —
 * and they must agree about what is accepted.
 */
object DeskIntake {
    /** What ordinary paper is. Fine and masterwork are our items alone — see the tag file. */
    val COMMON_PAPER: TagKey<Item> = TagKey.create(Registries.ITEM, "common_paper".location())

    /**
     * The outcome of an offer.
     *
     * **Two stacks, not one.** Pouring three buckets from a stack of five leaves two still full *and*
     * three now empty, and those cannot share a slot — so [remainder] is what the desk declined and
     * [returned] is what it handed back. Either may be empty; both are when it simply took everything.
     */
    data class Result(val remainder: ItemStack, val returned: ItemStack, val took: Boolean) {
        companion object {
            fun untouched(stack: ItemStack) = Result(stack, ItemStack.EMPTY, took = false)

            fun consumed(returned: ItemStack = ItemStack.EMPTY) = Result(ItemStack.EMPTY, returned, took = true)
        }
    }

    /** Offers [stack] to the desk. Whole items only — never a fractional bottle. */
    fun offer(desk: WritersDeskBlockEntity, stack: ItemStack): Result {
        if (stack.isEmpty) return Result.untouched(stack)
        pageWordOf(stack)?.let { word ->
            desk.addPages(word, stack.count)
            return Result.consumed()
        }
        if (stack.item === AgeContent.NOTEBOOK) return acceptNotebook(desk, stack)
        paperTierOf(stack)?.let { return acceptPaper(desk, stack, it) }
        if (stack.`is`(BookBinding.TAG)) return acceptBinding(desk, stack)
        inkOf(stack)?.let { (tier, perContainer, emptied) ->
            return acceptInk(desk, stack, tier, perContainer, emptied)
        }
        return Result.untouched(stack)
    }

    /** Whether the desk would take this, so a slot can refuse it before the player commits. */
    fun accepts(stack: ItemStack): Boolean =
        pageWordOf(stack) != null ||
            stack.item === AgeContent.NOTEBOOK ||
            paperTierOf(stack) != null ||
            stack.`is`(BookBinding.TAG) ||
            inkOf(stack) != null

    private fun acceptBinding(desk: WritersDeskBlockEntity, stack: ItemStack): Result {
        val rejected = desk.addBinding(stack.count)
        if (rejected == stack.count) return Result.untouched(stack)
        return Result(stack.copyWithCount(rejected), ItemStack.EMPTY, took = true)
    }

    private fun pageWordOf(stack: ItemStack): Identifier? =
        if (stack.item === AgeContent.PAGE) stack.get(AgeContent.PAGE_WORD) else null

    /**
     * A notebook is tipped into the archive and handed back empty — the pages inside are pages, and the
     * notebook is the folder rather than part of the contents. One at a time, since each emptied notebook
     * is a separate returned item.
     */
    private fun acceptNotebook(desk: WritersDeskBlockEntity, stack: ItemStack): Result {
        val held = NotebookItem.pagesIn(stack)
        var took = false
        for (page in held) {
            val word = pageWordOf(page) ?: continue
            desk.addPages(word, page.count)
            took = true
        }
        if (!took) return Result.untouched(stack)
        val emptied = stack.copyWithCount(1)
        NotebookItem.setPages(emptied, emptyList())
        return Result(stack.copyWithCount(stack.count - 1), emptied, took = true)
    }

    private fun paperTierOf(stack: ItemStack): InkTier? = when {
        stack.item === AgeContent.MASTERWORK_PAPER -> InkTier.MASTERWORK
        stack.item === AgeContent.FINE_PAPER -> InkTier.FINE
        stack.`is`(COMMON_PAPER) -> InkTier.COMMON
        else -> null
    }

    private fun acceptPaper(desk: WritersDeskBlockEntity, stack: ItemStack, tier: InkTier): Result {
        val rejected = desk.addPaper(tier, stack.count)
        if (rejected == stack.count) return Result.untouched(stack)
        return Result(stack.copyWithCount(rejected), ItemStack.EMPTY, took = true)
    }

    /** The ink a container holds: its tier, how much, and what one of them leaves behind. */
    private fun inkOf(stack: ItemStack): Triple<InkTier, Long, ItemStack>? {
        val fluids = Services.INK_FLUIDS
        AgeContent.INK_BOTTLES.entries.firstOrNull { stack.item === it.value }?.let { (tier, _) ->
            return Triple(tier, fluids.unitsPerBottle, ItemStack(Items.GLASS_BOTTLE))
        }
        if (stack.item !is BucketItem) return null
        return InkTier.entries
            .firstOrNull { stack.item === fluids.bucket(it) }
            ?.let { Triple(it, fluids.unitsPerBucket, ItemStack(Items.BUCKET)) }
    }

    /** Pours in as many containers as the tank has room for, leaving the rest full. */
    private fun acceptInk(
        desk: WritersDeskBlockEntity,
        stack: ItemStack,
        tier: InkTier,
        perContainer: Long,
        emptied: ItemStack,
    ): Result {
        val poured = (desk.inkSpace(tier) / perContainer)
            .coerceAtMost(stack.count.toLong())
            .toInt()
        if (poured <= 0) return Result.untouched(stack)
        desk.addInk(tier, poured * perContainer)
        return Result(stack.copyWithCount(stack.count - poured), emptied.copyWithCount(poured), took = true)
    }
}
