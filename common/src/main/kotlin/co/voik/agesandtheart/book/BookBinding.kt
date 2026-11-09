package co.voik.agesandtheart.book

import co.voik.agesandtheart.content.AgeContent
import net.minecraft.world.item.ItemStack

/**
 * How a book of ours is bound: a descriptive book's boards are a pixel thick, and a linking book's as thin as an
 * enchanting table book's, so that it shuts to half the thickness and looks the smaller book.
 */
enum class BookBinding(val boardThickness: Float) {
    THICK(1.0f),
    THIN(0.005f),
    ;

    companion object {
        fun of(book: ItemStack): BookBinding = if (book.`is`(AgeContent.LINKING_BOOK)) THIN else THICK
    }
}
