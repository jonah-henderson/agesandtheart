package co.voik.agesandtheart.desk

import co.voik.agesandtheart.content.AgeComponents
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.NotebookItem
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponentGetter
import net.minecraft.core.component.DataComponentMap
import net.minecraft.resources.Identifier
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * An archive's pages, and the one slot a hopper sees.
 *
 * **The slot is a doorway, not storage.** A hopper or a pipe puts a stack into it, and the stack is filed
 * when the insertion is *committed* — on [setChanged], which vanilla's hopper and Fabric's transfer API
 * both call once a move is final. Filing it in [setItem] instead would file it during a simulated insert
 * too, which Fabric's API rolls back by setting the slot empty again, leaving the pages filed and the
 * stack still in the pipe.
 *
 * Nothing can be taken out through it: the slot reads as empty whenever it has been filed, and
 * [canTakeItem] refuses anyway. Pages come out through the screen, one word at a time.
 */
class ArchiveBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(AgeContent.ARCHIVE_ENTITY, pos, state), Container {

    var pages: PageArchive = PageArchive.EMPTY
        private set

    private var doorway: ItemStack = ItemStack.EMPTY

    fun file(word: Identifier, count: Int) {
        if (count <= 0) return
        pages = pages.with(word, count)
        setChanged()
    }

    /** @return whether the archive held that many. */
    fun take(word: Identifier, count: Int): Boolean {
        val remaining = pages.without(word, count) ?: return false
        pages = remaining
        setChanged()
        return true
    }

    override fun setChanged() {
        fileTheDoorway()
        super.setChanged()
    }

    private fun fileTheDoorway() {
        val arrived = doorway
        if (arrived.isEmpty) return
        doorway = ItemStack.EMPTY
        val word = arrived.get(AgeComponents.PAGE_WORD) ?: return
        pages = pages.with(word, arrived.count)
    }

    override fun getContainerSize(): Int = 1

    override fun isEmpty(): Boolean = doorway.isEmpty

    override fun getItem(slot: Int): ItemStack = doorway

    override fun removeItem(slot: Int, count: Int): ItemStack = ItemStack.EMPTY

    override fun removeItemNoUpdate(slot: Int): ItemStack = ItemStack.EMPTY

    override fun setItem(slot: Int, stack: ItemStack) {
        doorway = stack
    }

    /** Loose pages only: a notebook hands back an empty one, and a pipe has nowhere to put it. */
    override fun canPlaceItem(slot: Int, stack: ItemStack): Boolean = NotebookItem.isPage(stack)

    override fun canTakeItem(target: Container, slot: Int, stack: ItemStack): Boolean = false

    override fun stillValid(player: Player): Boolean = Container.stillValidBlockEntity(this, player)

    /** What `/setblock` and structure placement call so a replaced block drops nothing. */
    override fun clearContent() {
        doorway = ItemStack.EMPTY
        pages = PageArchive.EMPTY
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        pages = input.read(PAGES_KEY, PageArchive.CODEC).orElse(PageArchive.EMPTY)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        fileTheDoorway()
        if (!pages.isEmpty) output.store(PAGES_KEY, PageArchive.CODEC, pages)
    }

    override fun applyImplicitComponents(components: DataComponentGetter) {
        super.applyImplicitComponents(components)
        pages = components.getOrDefault(AgeComponents.ARCHIVE_PAGES, PageArchive.EMPTY)
    }

    override fun collectImplicitComponents(components: DataComponentMap.Builder) {
        super.collectImplicitComponents(components)
        if (!pages.isEmpty) components.set(AgeComponents.ARCHIVE_PAGES, pages)
    }

    /** The pages travel as the item's component, so the block's own copy of them is not written twice. */
    override fun removeComponentsFromTag(output: ValueOutput) {
        output.discard(PAGES_KEY)
    }

    private companion object {
        const val PAGES_KEY = "pages"
    }
}
