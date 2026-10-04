package co.voik.agesandtheart.station

import co.voik.agesandtheart.AgeConfig
import co.voik.agesandtheart.age.reward.DryingSky
import co.voik.agesandtheart.location
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.netty.buffer.ByteBuf
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.util.StringRepresentable
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.PlacementInfo
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeBookCategory
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.item.crafting.SingleRecipeInput
import net.minecraft.world.level.Level
import java.util.Optional

/**
 * What the drying rack turns one thing into, and under what sky if any (design §7.1.2) — the masterwork
 * grades' last step is an ink cake cured under a black sun and a wet sheet dried in an Age with a lava sea.
 * A recipe naming no sky dries anywhere.
 */
class DryingRecipe(
    val ingredient: Ingredient,
    val result: ItemStackTemplate,
    val under: Optional<DryingSky>,
    val allowedBy: Optional<DryingSwitch>,
) : Recipe<SingleRecipeInput> {

    /** Whether the server lets this recipe run at all; a recipe with no switch always may. */
    val isAllowed: Boolean get() = allowedBy.map(DryingSwitch::isOn).orElse(true)

    fun driesUnder(skies: Set<DryingSky>): Boolean = under.map { it in skies }.orElse(true)

    override fun matches(input: SingleRecipeInput, level: Level): Boolean = isAllowed && ingredient.test(input.item())

    override fun assemble(input: SingleRecipeInput): ItemStack = result.create()

    override fun showNotification(): Boolean = false

    override fun group(): String = ""

    override fun getSerializer(): RecipeSerializer<DryingRecipe> = Drying.SERIALIZER

    override fun getType(): RecipeType<DryingRecipe> = Drying.TYPE

    override fun placementInfo(): PlacementInfo = PlacementInfo.create(ingredient)

    override fun recipeBookCategory(): RecipeBookCategory = Drying.BOOK_CATEGORY
}

/** A server's switch on a drying recipe, named by its `allowed_by` — see [AgeConfig.driesLeather]. */
enum class DryingSwitch(private val key: String) : StringRepresentable {
    LEATHER("leather"),
    ;

    val isOn: Boolean get() = when (this) {
        LEATHER -> AgeConfig.driesLeather.get()
    }

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<DryingSwitch> = StringRepresentable.fromEnum(DryingSwitch::values)

        val STREAM_CODEC: StreamCodec<ByteBuf, DryingSwitch> = ByteBufCodecs.VAR_INT.map({ entries[it] }, DryingSwitch::ordinal)
    }
}

/** The recipe type, its serializer and its book category, for the loaders to register. */
object Drying {
    val ID: Identifier = "drying".location()

    val TYPE: RecipeType<DryingRecipe> = object : RecipeType<DryingRecipe> {
        override fun toString(): String = ID.toString()
    }

    private val MAP_CODEC: MapCodec<DryingRecipe> = RecordCodecBuilder.mapCodec { instance ->
        instance.group(
            Ingredient.CODEC.fieldOf("ingredient").forGetter { it.ingredient },
            ItemStackTemplate.CODEC.fieldOf("result").forGetter { it.result },
            DryingSky.CODEC.optionalFieldOf("under").forGetter { it.under },
            DryingSwitch.CODEC.optionalFieldOf("allowed_by").forGetter { it.allowedBy },
        ).apply(instance, ::DryingRecipe)
    }

    private val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, DryingRecipe> = StreamCodec.composite(
        Ingredient.CONTENTS_STREAM_CODEC, DryingRecipe::ingredient,
        ItemStackTemplate.STREAM_CODEC, DryingRecipe::result,
        ByteBufCodecs.optional(DryingSky.STREAM_CODEC), DryingRecipe::under,
        ByteBufCodecs.optional(DryingSwitch.STREAM_CODEC), DryingRecipe::allowedBy,
        ::DryingRecipe,
    )

    val SERIALIZER: RecipeSerializer<DryingRecipe> = RecipeSerializer(MAP_CODEC, STREAM_CODEC)

    val BOOK_CATEGORY: RecipeBookCategory = RecipeBookCategory()
}
