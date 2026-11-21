package co.voik.agesandtheart.imaging

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.CraftingInput
import net.minecraft.world.item.crafting.CustomRecipe
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block

/**
 * A recorded image framed: one and two of the same [wood], anywhere in the grid, give it back in a frame of that wood
 * ([ImagingComponents.FRAME]), its picture and name kept. A framed one is framed again, its old frame lost.
 */
class FramingRecipe(private val picture: Ingredient, private val wood: Ingredient) : CustomRecipe() {

    override fun matches(input: CraftingInput, level: Level): Boolean = framing(input) != null

    override fun assemble(input: CraftingInput): ItemStack {
        val (image, frame) = framing(input) ?: return ItemStack.EMPTY
        return image.copyWithCount(1).also { it.set(ImagingComponents.FRAME, PictureFrame(frame)) }
    }

    /** The image to frame and the block of the wood it is framed in, where [input] holds just those. */
    private fun framing(input: CraftingInput): Pair<ItemStack, Block>? {
        val held = input.items().filterNot(ItemStack::isEmpty)
        val images = held.filter(picture::test)
        val woods = held.filter(wood::test)
        val image = images.singleOrNull() ?: return null
        val woodsAreAlike = woods.map(ItemStack::getItem).distinct().size == 1
        val holdsNothingElse = images.size + woods.size == held.size
        if (woods.size != WOODS || !woodsAreAlike || !holdsNothingElse) return null
        val block = (woods.first().item as? BlockItem)?.block ?: return null
        val changesTheFrame = image.framedIn != block
        return if (changesTheFrame) image to block else null
    }

    override fun getSerializer(): RecipeSerializer<FramingRecipe> = SERIALIZER

    companion object {
        val ID: Identifier = Imaging.id("framing")

        private const val WOODS = 2

        private val MAP_CODEC: MapCodec<FramingRecipe> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(
                Ingredient.CODEC.fieldOf("picture").forGetter { it.picture },
                Ingredient.CODEC.fieldOf("wood").forGetter { it.wood },
            ).apply(instance, ::FramingRecipe)
        }

        private val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, FramingRecipe> = StreamCodec.composite(
            Ingredient.CONTENTS_STREAM_CODEC, { it.picture },
            Ingredient.CONTENTS_STREAM_CODEC, { it.wood },
            ::FramingRecipe,
        )

        val SERIALIZER: RecipeSerializer<FramingRecipe> = RecipeSerializer(MAP_CODEC, STREAM_CODEC)
    }
}
