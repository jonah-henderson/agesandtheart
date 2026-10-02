package co.voik.agesandtheart.station

import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import net.minecraft.core.Holder
import net.minecraft.world.entity.player.StackedContents
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.Ingredient
import java.util.Optional

/**
 * That vanilla's craftable check counts a compounding's ingredients — what the recipe book's red buttons,
 * its craftable filter and [CompoundingPlacement] all rest on, and none of them knows of counts.
 *
 * Asked of `StackedContents`, which `StackedItemContents` hands every question to unchanged: offline, no
 * `ItemStack` can be built, item components being unbound (see `ToolboxCheck`).
 */
@Tags(NEEDS_REGISTRIES)
class CompoundingPlacementCheck : FunSpec({

    fun diamondFromCoal() = CompoundingRecipe(
        ingredients = listOf(CountedIngredient(Ingredient.of(Items.COAL_BLOCK), COAL_FOR_A_DIAMOND)),
        result = ItemStackTemplate(Items.DIAMOND_BLOCK),
        alsoNeeds = emptyList(),
        allowedBy = Optional.empty(),
    )

    @Suppress("DEPRECATION")
    fun holding(vararg held: Pair<Item, Int>) = StackedContents<Holder<Item>>().also { contents ->
        held.forEach { (item, count) -> contents.account(item.builtInRegistryHolder(), count) }
    }

    fun StackedContents<Holder<Item>>.canMake(recipe: CompoundingRecipe) =
        tryPick(recipe.placementInfo().ingredients(), 1, null)

    test("a stack too few is not enough, and a full one is") {
        val recipe = diamondFromCoal()
        check(!holding(Items.COAL_BLOCK to COAL_FOR_A_DIAMOND - 1).canMake(recipe)) {
            "63 blocks of coal were enough for a recipe wanting 64"
        }
        check(holding(Items.COAL_BLOCK to COAL_FOR_A_DIAMOND).canMake(recipe)) {
            "64 blocks of coal were not enough for a recipe wanting 64"
        }
    }

    test("the count says how many times over a recipe can be made") {
        val recipe = diamondFromCoal()
        val times = holding(Items.COAL_BLOCK to COAL_FOR_TWO_AND_A_HALF)
            .tryPickAll(recipe.placementInfo().ingredients(), Int.MAX_VALUE, null)
        check(times == 2) { "160 blocks of coal made $times diamonds, not 2" }
    }

    test("two ingredients each want their own count") {
        val recipe = CompoundingRecipe(
            ingredients = listOf(
                CountedIngredient(Ingredient.of(Items.OBSIDIAN), SIXTEEN),
                CountedIngredient(Ingredient.of(Items.BLACKSTONE), SIXTEEN),
            ),
            result = ItemStackTemplate(Items.STONE),
            alsoNeeds = emptyList(),
            allowedBy = Optional.empty(),
        )
        val shortOfBlackstone = holding(Items.OBSIDIAN to SIXTEEN, Items.BLACKSTONE to SIXTEEN - 1)
        check(!shortOfBlackstone.canMake(recipe)) { "fifteen blackstone met a want of sixteen" }
        check(holding(Items.OBSIDIAN to SIXTEEN, Items.BLACKSTONE to SIXTEEN).canMake(recipe)) {
            "sixteen of each did not meet sixteen of each"
        }
    }
})

private const val COAL_FOR_A_DIAMOND = 64
private const val COAL_FOR_TWO_AND_A_HALF = 160
private const val SIXTEEN = 16
