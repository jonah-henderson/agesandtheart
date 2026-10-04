package co.voik.agesandtheart.desk

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.content.AgeComponents
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
 * One place, because there are two doors into it — a wing's input slot and using an item on the block —
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

    /**
     * Offers [stack] to the desk. Whole items only — never a fractional bottle.
     *
     * Pages are not among what it takes: they are filed in an archive, which the desk draws on at the bind.
     */
    fun offer(desk: WritersDeskBlockEntity, stack: ItemStack): Result {
        if (stack.isEmpty) return Result.untouched(stack)
        paperTierOf(stack)?.let { return acceptPaper(desk, stack, it) }
        if (stack.`is`(BookBinding.TAG)) return acceptBinding(desk, stack)
        inkOf(stack)?.let { (tier, perContainer, emptied) ->
            return acceptInk(desk, stack, tier, perContainer, emptied)
        }
        return Result.untouched(stack)
    }

    /** Whether the desk would take this, so a slot can refuse it before the player commits. */
    fun accepts(stack: ItemStack): Boolean =
        paperTierOf(stack) != null ||
            stack.`is`(BookBinding.TAG) ||
            inkOf(stack) != null

    private fun acceptBinding(desk: WritersDeskBlockEntity, stack: ItemStack): Result {
        val rejected = desk.addBinding(stack.count)
        if (rejected == stack.count) return Result.untouched(stack)
        return Result(stack.copyWithCount(rejected), ItemStack.EMPTY, took = true)
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

    /**
     * Fills one empty bucket from the commonest ink holding a whole bucket — the least precious first,
     * and pouring it back loses nothing. Untouched when no tank holds a bucket's worth.
     */
    fun draw(desk: WritersDeskBlockEntity, stack: ItemStack): Result {
        if (!stack.`is`(Items.BUCKET)) return Result.untouched(stack)
        val fluids = Services.INK_FLUIDS
        val tier = InkTier.entries.firstOrNull { desk.stores.ink(it) >= fluids.unitsPerBucket } ?: return Result.untouched(stack)
        if (!desk.takeInk(tier, fluids.unitsPerBucket)) return Result.untouched(stack)
        return Result(stack.copyWithCount(stack.count - 1), ItemStack(fluids.bucket(tier)), took = true)
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
