package co.voik.agesandtheart.book

import co.voik.agesandtheart.content.AgeComponents
import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.tags.BannerPatternTags
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BannerPattern
import net.minecraft.world.level.block.entity.BannerPatternLayers
import net.minecraft.world.level.block.entity.BannerPatterns
import kotlin.random.Random

/**
 * The cover a found book comes in, drawn at random: a base colour and banner pattern layers laid out as a
 * [CoverLayout] says, the same as a banner puts on a book ([BookBanner]). A basic book's is plain or one layer, an
 * advanced or unstable book's two or three, so a book worth more reads as one at a glance. The patterns are the
 * loom's own, never one that needs a pattern item to make.
 */
object FoundCover {

    /** [book] covered at random, unless it is covered already. */
    fun putOn(book: ItemStack, registries: HolderLookup.Provider, seed: Long, kind: FoundBookKind) {
        if (BookBanner.isCovered(book)) return
        val random = Random(seed xor COVER_SALT)
        val base = DyeColor.entries.random(random)
        val patterns = loomsOwn(registries)
        val layers = BannerPatternLayers.Builder()
        var under = base
        if (patterns.isNotEmpty()) {
            repeat(layersOf(kind).random(random)) {
                val colour = DyeColor.entries.filter { it != under }.random(random)
                layers.add(patterns.random(random), colour)
                under = colour
            }
        }
        val drawn = layers.build()
        book.set(DataComponents.BASE_COLOR, base)
        if (drawn.layers().isNotEmpty()) book.set(DataComponents.BANNER_PATTERNS, drawn)
        book.set(AgeComponents.COVER_LAYOUT, CoverLayout.entries.random(random))
    }

    private fun layersOf(kind: FoundBookKind): IntRange = when (kind) {
        FoundBookKind.BASIC -> PLAIN_OR_ONE
        FoundBookKind.ADVANCED, FoundBookKind.UNSTABLE -> TWO_OR_THREE
    }

    /** Every pattern a loom offers without a pattern item, but the plain field, which would hide what is under it. */
    private fun loomsOwn(registries: HolderLookup.Provider): List<Holder<BannerPattern>> {
        val patterns = registries.lookupOrThrow(Registries.BANNER_PATTERN)
        val offered = patterns.get(BannerPatternTags.NO_ITEM_REQUIRED).map { it.toList() }.orElse(emptyList())
        return offered.filterNot { it.`is`(BannerPatterns.BASE) }
    }

    private val PLAIN_OR_ONE = 0..1
    private val TWO_OR_THREE = 2..3

    private const val COVER_SALT = 0x434F_5645_52L
}
