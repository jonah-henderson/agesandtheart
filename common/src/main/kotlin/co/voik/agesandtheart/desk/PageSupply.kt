package co.voik.agesandtheart.desk

import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.NotebookItem
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.level.BlockGetter

/**
 * Which of a book's words have a written page to hand, and where each is drawn from.
 *
 * **Archives before the inventory** (Jonah, 2026-09-18): what a writer carries is more likely something
 * they mean to keep. Whatever neither holds is [toWrite], in the order the words appear.
 */
data class PageAllocation(
    val fromArchives: Map<Identifier, Int>,
    val fromInventory: Map<Identifier, Int>,
    val toWrite: List<Identifier>,
) {
    val drawn: Int get() = fromArchives.values.sum() + fromInventory.values.sum()

    companion object {
        fun of(
            words: List<Identifier>,
            inArchives: (Identifier) -> Int,
            inInventory: (Identifier) -> Int,
        ): PageAllocation {
            val fromArchives = mutableMapOf<Identifier, Int>()
            val fromInventory = mutableMapOf<Identifier, Int>()
            val toWrite = mutableListOf<Identifier>()
            for (word in words) {
                val archiveHasOneLeft = fromArchives.getOrDefault(word, 0) < inArchives(word)
                val inventoryHasOneLeft = fromInventory.getOrDefault(word, 0) < inInventory(word)
                when {
                    archiveHasOneLeft -> fromArchives.merge(word, 1, Int::plus)
                    inventoryHasOneLeft -> fromInventory.merge(word, 1, Int::plus)
                    else -> toWrite += word
                }
            }
            return PageAllocation(fromArchives, fromInventory, toWrite)
        }
    }
}

/**
 * The written pages a desk can draw on: every archive in the room, and the writer's own inventory.
 *
 * "In the room" is the implement survey's cube (`art/writers_desk.json`'s radius), the same check every
 * other desk upgrade block is found by — and several archives in it pool into one supply.
 */
class PageSupply(private val archives: List<ArchiveBlockEntity>, private val inventory: Inventory) {

    fun inArchives(word: Identifier): Int = archives.sumOf { it.pages.count(word) }

    fun inInventory(word: Identifier): Int =
        (0 until inventory.containerSize)
            .map(inventory::getItem)
            .filter { NotebookItem.isPage(it) && it.get(AgeComponents.PAGE_WORD) == word }
            .sumOf { it.count }

    fun allocate(words: List<Identifier>): PageAllocation = PageAllocation.of(words, ::inArchives, ::inInventory)

    /**
     * Takes what [allocation] drew. Checked before anything is taken, so a supply that changed under the
     * writer — a page moved, an archive broken — spends nothing.
     *
     * @return whether every page was there to take.
     */
    fun take(allocation: PageAllocation): Boolean {
        val stillThere = allocation.fromArchives.all { (word, count) -> inArchives(word) >= count } &&
            allocation.fromInventory.all { (word, count) -> inInventory(word) >= count }
        if (!stillThere) return false
        allocation.fromArchives.forEach { (word, count) -> takeFromArchives(word, count) }
        allocation.fromInventory.forEach { (word, count) -> takeFromInventory(word, count) }
        return true
    }

    private fun takeFromArchives(word: Identifier, count: Int) {
        var left = count
        for (archive in archives) {
            val taken = left.coerceAtMost(archive.pages.count(word))
            if (taken > 0 && archive.take(word, taken)) left -= taken
            if (left == 0) return
        }
    }

    private fun takeFromInventory(word: Identifier, count: Int) {
        var left = count
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (!NotebookItem.isPage(stack) || stack.get(AgeComponents.PAGE_WORD) != word) continue
            val taken = left.coerceAtMost(stack.count)
            stack.shrink(taken)
            left -= taken
            if (left == 0) break
        }
        inventory.setChanged()
    }

    companion object {
        /** Every archive within [radius] of [pos], in every direction. */
        fun archivesAround(level: BlockGetter, pos: BlockPos, radius: Int): List<ArchiveBlockEntity> {
            val found = mutableListOf<ArchiveBlockEntity>()
            val cursor = BlockPos.MutableBlockPos()
            for (x in -radius..radius) for (y in -radius..radius) for (z in -radius..radius) {
                cursor.setWithOffset(pos, x, y, z)
                (level.getBlockEntity(cursor) as? ArchiveBlockEntity)?.let(found::add)
            }
            return found
        }
    }
}
