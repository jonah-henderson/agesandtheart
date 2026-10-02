package co.voik.agesandtheart.station

import co.voik.agesandtheart.AgeConfig
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.netty.buffer.ByteBuf
import net.minecraft.core.registries.Registries
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import net.minecraft.util.ExtraCodecs
import net.minecraft.util.StringRepresentable
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.PlacementInfo
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeBookCategory
import net.minecraft.world.item.crafting.RecipeInput
import net.minecraft.world.item.crafting.RecipeSerializer
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.item.crafting.display.RecipeDisplay
import net.minecraft.world.item.crafting.display.SlotDisplay
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import java.util.Optional

/**
 * What the fusion-compounder makes (design §7.1.2): several counted stacks in, one thing out, at once.
 *
 * Each ingredient is taken from a slot of its own, so the inputs a recipe names and the filled slots must
 * pair off one to one: a slot may hold more than its ingredient's count, but no filled slot may be left over.
 */
class CompoundingRecipe(
    val ingredients: List<CountedIngredient>,
    val result: ItemStackTemplate,
    val alsoNeeds: List<CompounderNeed>,
    val allowedBy: Optional<CompoundingSwitch>,
) : Recipe<CompounderInput> {

    /** Whether the server lets this recipe run at all; a recipe with no switch always may. */
    val isAllowed: Boolean get() = allowedBy.map(CompoundingSwitch::isOn).orElse(true)

    override fun matches(input: CompounderInput, level: Level): Boolean = isAllowed && slotsFor(input) != null

    override fun assemble(input: CompounderInput): ItemStack = result.create()

    /**
     * The slot each ingredient takes from, in the ingredients' order, or null where the stacks do not pair
     * off with them. At most four of each, so trying every pairing is cheap.
     */
    fun slotsFor(input: CompounderInput): List<Int>? {
        val filled = (0..<input.size()).filterNot { input.getItem(it).isEmpty }
        if (filled.size != ingredients.size) return null
        fun pair(next: Int, taken: List<Int>): List<Int>? {
            if (next == ingredients.size) return taken
            for (slot in filled) {
                val isFree = slot !in taken
                if (isFree && ingredients[next].isMetBy(input.getItem(slot))) pair(next + 1, taken + slot)?.let { return it }
            }
            return null
        }
        return pair(0, emptyList())
    }

    override fun showNotification(): Boolean = true

    override fun group(): String = ""

    override fun getSerializer(): RecipeSerializer<CompoundingRecipe> = Compounding.SERIALIZER

    override fun getType(): RecipeType<CompoundingRecipe> = Compounding.TYPE

    /**
     * Each ingredient once per item it takes, so sixteen obsidian is sixteen entries. That is what makes
     * vanilla's `StackedItemContents` count: the recipe book's craftable check and [CompoundingPlacement]
     * both read this list, and neither knows of counts.
     */
    override fun placementInfo(): PlacementInfo = placement

    private val placement: PlacementInfo by lazy {
        PlacementInfo.create(ingredients.flatMap { counted -> List(counted.count) { counted.ingredient } })
    }

    override fun recipeBookCategory(): RecipeBookCategory = Compounding.BOOK_CATEGORY

    override fun display(): List<RecipeDisplay> = listOf(
        CompoundingRecipeDisplay(
            inputs = ingredients.map { it.ingredient.display() },
            counts = ingredients.map { it.count },
            resultShown = SlotDisplay.ItemStackSlotDisplay(result),
            stationShown = SlotDisplay.ItemSlotDisplay(Compounder.ITEM),
            needs = (listOf(CompounderNeed.POWER) + alsoNeeds).distinct(),
            allowedBy = allowedBy,
        ),
    )
}

/** So many of one ingredient, taken from one slot. */
data class CountedIngredient(val ingredient: Ingredient, val count: Int) {

    fun isMetBy(stack: ItemStack): Boolean = ingredient.test(stack) && stack.count >= count

    companion object {
        private const val MOST_A_SLOT_HOLDS = 64

        val CODEC: Codec<CountedIngredient> = RecordCodecBuilder.create { instance ->
            instance.group(
                Ingredient.CODEC.fieldOf("ingredient").forGetter { it.ingredient },
                ExtraCodecs.intRange(1, MOST_A_SLOT_HOLDS).optionalFieldOf("count", 1).forGetter { it.count },
            ).apply(instance, ::CountedIngredient)
        }

        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, CountedIngredient> = StreamCodec.composite(
            Ingredient.CONTENTS_STREAM_CODEC, CountedIngredient::ingredient,
            ByteBufCodecs.VAR_INT, CountedIngredient::count,
            ::CountedIngredient,
        )
    }
}

/** The compounder's stacks, slot by slot. */
class CompounderInput(private val stacks: List<ItemStack>) : RecipeInput {
    override fun getItem(index: Int): ItemStack = stacks[index]

    override fun size(): Int = stacks.size
}

/**
 * What must stand beside the compounder for a recipe to run. Power runs every recipe; the rest are asked
 * for by name, and only nara's asks for any. Heat and cold are placeholders (Jonah, 2026-10-01): lava and
 * rime are probably too easy, so what nara needs may become something else.
 */
enum class CompounderNeed(private val key: String, private val metBy: TagKey<Block>?) : StringRepresentable {
    /** A block of arc crystal, as both stations take; not a tag, so it is the crystal's block or nothing. */
    POWER("power", null),
    HEAT("heat", TagKey.create(Registries.BLOCK, "gives_heat".location())),
    COLD("cold", TagKey.create(Registries.BLOCK, "gives_cold".location())),
    ;

    fun isMetBy(state: BlockState): Boolean = when (this) {
        POWER -> state.`is`(AgeContent.ARC_CRYSTAL_BLOCK_BLOCK)
        HEAT, COLD -> metBy?.let(state::`is`) == true
    }

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<CompounderNeed> = StringRepresentable.fromEnum(CompounderNeed::values)

        val STREAM_CODEC: StreamCodec<ByteBuf, CompounderNeed> =
            ByteBufCodecs.VAR_INT.map({ entries[it] }, CompounderNeed::ordinal)
    }
}

/**
 * A server's switch on a recipe that makes something nothing else in the game can, named by a recipe's
 * `allowed_by` — see [AgeConfig.compoundsBedrock].
 */
enum class CompoundingSwitch(private val key: String) : StringRepresentable {
    BEDROCK("bedrock"),
    REINFORCED_DEEPSLATE("reinforced_deepslate"),
    BUDDING_AMETHYST("budding_amethyst"),
    HEAVY_CORE("heavy_core"),
    ;

    val isOn: Boolean get() = when (this) {
        BEDROCK -> AgeConfig.compoundsBedrock.get()
        REINFORCED_DEEPSLATE -> AgeConfig.compoundsReinforcedDeepslate.get()
        BUDDING_AMETHYST -> AgeConfig.compoundsBuddingAmethyst.get()
        HEAVY_CORE -> AgeConfig.compoundsHeavyCore.get()
    }

    override fun getSerializedName(): String = key

    companion object {
        val CODEC: Codec<CompoundingSwitch> = StringRepresentable.fromEnum(CompoundingSwitch::values)

        val STREAM_CODEC: StreamCodec<ByteBuf, CompoundingSwitch> =
            ByteBufCodecs.VAR_INT.map({ entries[it] }, CompoundingSwitch::ordinal)
    }
}

/** The recipe type, its serializer and its book category, for the loaders to register. */
object Compounding {
    val ID: Identifier = "compounding".location()

    val TYPE: RecipeType<CompoundingRecipe> = object : RecipeType<CompoundingRecipe> {
        override fun toString(): String = ID.toString()
    }

    private const val MOST_INGREDIENTS = CompounderBlockEntity.INPUT_SLOTS

    private val MAP_CODEC: MapCodec<CompoundingRecipe> = RecordCodecBuilder.mapCodec { instance ->
        instance.group(
            CountedIngredient.CODEC.listOf(1, MOST_INGREDIENTS).fieldOf("ingredients").forGetter { it.ingredients },
            ItemStackTemplate.CODEC.fieldOf("result").forGetter { it.result },
            CompounderNeed.CODEC.listOf().optionalFieldOf("also_needs", emptyList()).forGetter { it.alsoNeeds },
            CompoundingSwitch.CODEC.optionalFieldOf("allowed_by").forGetter { it.allowedBy },
        ).apply(instance, ::CompoundingRecipe)
    }

    private val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, CompoundingRecipe> = StreamCodec.composite(
        CountedIngredient.STREAM_CODEC.apply(ByteBufCodecs.list()), CompoundingRecipe::ingredients,
        ItemStackTemplate.STREAM_CODEC, CompoundingRecipe::result,
        CompounderNeed.STREAM_CODEC.apply(ByteBufCodecs.list()), CompoundingRecipe::alsoNeeds,
        ByteBufCodecs.optional(CompoundingSwitch.STREAM_CODEC), CompoundingRecipe::allowedBy,
        ::CompoundingRecipe,
    )

    val SERIALIZER: RecipeSerializer<CompoundingRecipe> = RecipeSerializer(MAP_CODEC, STREAM_CODEC)

    val BOOK_CATEGORY: RecipeBookCategory = RecipeBookCategory()
}
