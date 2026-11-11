package co.voik.agesandtheart.book

import co.voik.agesandtheart.content.AgeComponents
import net.minecraft.core.Holder
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.tags.BannerPatternTags
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.DyedItemColor
import net.minecraft.world.level.block.entity.BannerPattern
import net.minecraft.world.level.block.entity.BannerPatternLayers
import net.minecraft.world.level.block.entity.BannerPatterns
import kotlin.random.Random

/**
 * How a found book comes bound, drawn at random: a quarter of them covered and the rest dyed.
 *
 * A cover is a base colour and banner pattern layers laid out as a [CoverLayout] says, the same as a banner puts on a
 * book ([BookBanner]). A basic book's is plain or one layer, an advanced or unstable book's two or three, so a book
 * worth more reads as one at a glance. The patterns are the loom's own, never one that needs a pattern item to make.
 *
 * A dyed book's binding is left undyed a tenth of the time, takes one dye most of the time, and a mix of two or three
 * the rest, mixed as a crafting grid mixes them.
 */
object FoundCover {

    /** [book] covered or dyed at random, unless it is either already. */
    fun putOn(book: ItemStack, registries: HolderLookup.Provider, seed: Long, kind: FoundBookKind) {
        val isDressedAlready = BookBanner.isCovered(book) || book.has(DataComponents.DYED_COLOR)
        if (isDressedAlready) return
        val random = Random(seed xor COVER_SALT)
        if (random.nextDouble() < COVERED) cover(book, registries, random, kind) else dye(book, random)
    }

    private fun dye(book: ItemStack, random: Random) {
        val draw = random.nextDouble()
        val dyes = when {
            draw < UNDYED -> return
            draw < UNDYED + ONE_DYE -> listOf(DyeColor.entries.random(random))
            else -> DyeColor.entries.shuffled(random).take(MIXED.random(random))
        }
        book.set(DataComponents.DYED_COLOR, DyedItemColor.applyDyes(null, dyes))
    }

    private fun cover(book: ItemStack, registries: HolderLookup.Provider, random: Random, kind: FoundBookKind) {
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

    /** The share of found books covered; the rest are dyed. */
    private const val COVERED = 0.25

    /** Of the dyed, the share left undyed and the share of one dye; the rest are a mix of [MIXED] dyes. */
    private const val UNDYED = 0.1
    private const val ONE_DYE = 0.8
    private val MIXED = 2..3

    private val PLAIN_OR_ONE = 0..1
    private val TWO_OR_THREE = 2..3

    private const val COVER_SALT = 0x434F_5645_52L
}
