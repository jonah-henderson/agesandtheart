package co.voik.agesandtheart.book

import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.BannerItem
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.entity.BannerPatternLayers

/**
 * A book's cover as a banner: what a banner puts on a book, and the banner a covered book gives back. A book is
 * covered where it carries a base colour, whether or not it carries layers over it, as a shield is.
 */
object BookBanner {

    fun isCovered(book: ItemStack): Boolean = book.has(DataComponents.BASE_COLOR)

    /** The banner [book]'s cover came from, or nothing where it has none. */
    fun wornBy(book: ItemStack): ItemStack {
        val colour = book.get(DataComponents.BASE_COLOR) ?: return ItemStack.EMPTY
        val banner = ItemStack(bannerOf(colour))
        val layers = book.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY)
        if (layers.layers().isNotEmpty()) banner.set(DataComponents.BANNER_PATTERNS, layers)
        return banner
    }

    /** [book] covered with [banner]'s colour and pattern. */
    fun putOn(book: ItemStack, banner: ItemStack) {
        val colour = (banner.item as? BannerItem)?.color ?: return
        book.set(DataComponents.BASE_COLOR, colour)
        val layers = banner.get(DataComponents.BANNER_PATTERNS)
        if (layers == null) book.remove(DataComponents.BANNER_PATTERNS) else book.set(DataComponents.BANNER_PATTERNS, layers)
    }

    /** [book] with its cover taken off. Its layout stays, for the next banner it wears. */
    fun takeOff(book: ItemStack) {
        book.remove(DataComponents.BASE_COLOR)
        book.remove(DataComponents.BANNER_PATTERNS)
    }

    /** 26.2 gathers the sixteen coloured variants into a `ColorCollection`, which this picks from. */
    private fun bannerOf(colour: DyeColor) = Items.BANNER.pick(colour)
}
