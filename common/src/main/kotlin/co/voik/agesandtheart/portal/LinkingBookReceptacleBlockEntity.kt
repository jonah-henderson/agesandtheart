package co.voik.agesandtheart.portal

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/** The one book a receptacle holds, which is where every portal on its frame leads. */
class LinkingBookReceptacleBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(AgeContent.LINKING_BOOK_RECEPTACLE_ENTITY, pos, state) {

    var book: ItemStack = ItemStack.EMPTY
        private set

    fun hold(stack: ItemStack) {
        book = stack
        setChanged()
    }

    /** Hands the book over and leaves the receptacle empty. */
    fun release(): ItemStack {
        val released = book
        book = ItemStack.EMPTY
        setChanged()
        return released
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        book = input.read(BOOK_KEY, ItemStack.CODEC).orElse(ItemStack.EMPTY)
    }

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        if (!book.isEmpty) output.store(BOOK_KEY, ItemStack.CODEC, book)
    }

    /** Broken, the receptacle drops its book and the portal it held open goes out, as a lectern drops its own. */
    override fun preRemoveSideEffects(pos: BlockPos, state: BlockState) {
        val level = level as? ServerLevel ?: return
        if (book.isEmpty) return
        val dropped = ItemEntity(level, pos.x + MIDDLE, pos.y + ABOVE_THE_TOP, pos.z + MIDDLE, release())
        dropped.setDefaultPickUpDelay()
        level.addFreshEntity(dropped)
        LinkingPortals.closeAround(level, pos)
    }

    private companion object {
        const val BOOK_KEY = "Book"
        const val MIDDLE = 0.5
        const val ABOVE_THE_TOP = 1.0
    }
}
