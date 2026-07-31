package co.voik.agesandtheart.book

import co.voik.agesandtheart.content.AgeContent
import com.mojang.serialization.MapCodec
import net.minecraft.core.NonNullList
import net.minecraft.core.component.DataComponents
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.world.item.BannerItem
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.CustomRecipe
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BannerPatternLayers

/**
 * A banner and an already-patterned book: the book takes the new pattern and gives the old one back.
 *
 * Vanilla's shield decoration covers the unpatterned case and refuses this one, so the two sit side by
 * side — that recipe marks a blank book, this one re-marks a marked book.
 */
class RepatternBookRecipe : CustomRecipe() {

    override fun matches(input: CraftingInput, level: Level): Boolean {
        if (input.ingredientCount() != 2) return false
        var sawBanner = false
        var sawPatternedBook = false
        for (slot in 0 until input.size()) {
            val stack = input.getItem(slot)
            if (stack.isEmpty) continue
            when {
                stack.item is BannerItem -> {
                    if (sawBanner) return false
                    sawBanner = true
                }
                stack.item === AgeContent.DESCRIPTIVE_BOOK -> {
                    if (sawPatternedBook || patternOn(stack).layers().isEmpty()) return false
                    sawPatternedBook = true
                }
                else -> return false
            }
        }
        return sawBanner && sawPatternedBook
    }

    override fun assemble(input: CraftingInput): ItemStack {
        val banner = input.items().first { it.item is BannerItem }
        val book = input.items().first { it.item === AgeContent.DESCRIPTIVE_BOOK }
        val result = book.copyWithCount(1)
        result.set(DataComponents.BANNER_PATTERNS, banner.get(DataComponents.BANNER_PATTERNS))
        result.set(DataComponents.BASE_COLOR, (banner.item as BannerItem).color)
        return result
    }

    /** The pattern the book was wearing, handed back as the banner it came from. */
    override fun getRemainingItems(input: CraftingInput): NonNullList<ItemStack> {
        val remaining = NonNullList.withSize(input.size(), ItemStack.EMPTY)
        for (slot in 0 until input.size()) {
            val stack = input.getItem(slot)
            if (stack.item !== AgeContent.DESCRIPTIVE_BOOK) continue
            val worn = patternOn(stack)
            if (worn.layers().isEmpty()) continue
            val returned = ItemStack(bannerOf(stack.getOrDefault(DataComponents.BASE_COLOR, DyeColor.WHITE)))
            returned.set(DataComponents.BANNER_PATTERNS, worn)
            remaining[slot] = returned
        }
        return remaining
    }

    override fun getSerializer(): RecipeSerializer<out CustomRecipe> = SERIALIZER

    companion object {
        /** No fields, so the recipe is entirely its own type. */
        private val MAP_CODEC: MapCodec<RepatternBookRecipe> = MapCodec.unit(::RepatternBookRecipe)

        private val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, RepatternBookRecipe> =
            StreamCodec.unit(RepatternBookRecipe())

        val SERIALIZER: RecipeSerializer<RepatternBookRecipe> = RecipeSerializer(MAP_CODEC, STREAM_CODEC)

        private fun patternOn(stack: ItemStack): BannerPatternLayers =
            stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY)

        private fun bannerOf(colour: DyeColor) = when (colour) {
            DyeColor.WHITE -> Items.WHITE_BANNER
            DyeColor.ORANGE -> Items.ORANGE_BANNER
            DyeColor.MAGENTA -> Items.MAGENTA_BANNER
            DyeColor.LIGHT_BLUE -> Items.LIGHT_BLUE_BANNER
            DyeColor.YELLOW -> Items.YELLOW_BANNER
            DyeColor.LIME -> Items.LIME_BANNER
            DyeColor.PINK -> Items.PINK_BANNER
            DyeColor.GRAY -> Items.GRAY_BANNER
            DyeColor.LIGHT_GRAY -> Items.LIGHT_GRAY_BANNER
            DyeColor.CYAN -> Items.CYAN_BANNER
            DyeColor.PURPLE -> Items.PURPLE_BANNER
            DyeColor.BLUE -> Items.BLUE_BANNER
            DyeColor.BROWN -> Items.BROWN_BANNER
            DyeColor.GREEN -> Items.GREEN_BANNER
            DyeColor.RED -> Items.RED_BANNER
            DyeColor.BLACK -> Items.BLACK_BANNER
        }
    }
}
