package co.voik.agesandtheart.client

import co.voik.agesandtheart.station.Compounder
import co.voik.agesandtheart.station.CompounderMenu
import co.voik.agesandtheart.station.Compounding
import co.voik.agesandtheart.station.CompoundingRecipeDisplay
import net.minecraft.client.gui.components.WidgetSprites
import net.minecraft.client.gui.screens.recipebook.GhostSlots
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent
import net.minecraft.client.gui.screens.recipebook.RecipeCollection
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.context.ContextMap
import net.minecraft.world.entity.player.StackedItemContents
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.crafting.display.RecipeDisplay
import net.minecraft.world.item.crafting.display.SlotDisplay

/**
 * The compounder's recipe book: vanilla's own, with one tab for every compounding the player has learned.
 *
 * Vanilla does the rest — the search, the craftable filter, the pages, laying a recipe out on a click and
 * its ghost where the inventory cannot. A recipe a server has switched off is left out; the config is
 * synced, so the client knows.
 */
class CompoundingBook(menu: CompounderMenu) :
    RecipeBookComponent<CompounderMenu>(menu, listOf(TabInfo(Compounder.ITEM, Compounding.BOOK_CATEGORY))) {

    override fun getFilterButtonTextures(): WidgetSprites = FILTER_SPRITES

    override fun isCraftingSlot(slot: Slot): Boolean = slot in menu.inputSlots || slot == menu.resultSlot

    override fun selectMatchingRecipes(collection: RecipeCollection, stackedContents: StackedItemContents) {
        collection.selectRecipes(stackedContents) { display -> display is CompoundingRecipeDisplay && display.isAllowed }
    }

    /**
     * Each input as the item it wants and how many. Laid as results, since `GhostSlots` draws a count on a
     * result alone; the compounder's result slot is an ordinary one, so nothing else about them differs.
     */
    override fun fillGhostRecipe(ghostSlots: GhostSlots, recipe: RecipeDisplay, context: ContextMap) {
        val compounding = recipe as? CompoundingRecipeDisplay ?: return
        ghostSlots.setResult(menu.resultSlot, context, recipe.result())
        for ((index, input) in compounding.inputs.withIndex()) {
            ghostSlots.setResult(menu.inputSlots[index], context, counted(input, compounding.counts[index], context))
        }
    }

    private fun counted(input: SlotDisplay, count: Int, context: ContextMap): SlotDisplay =
        SlotDisplay.Composite(
            input.resolveForStacks(context).map { SlotDisplay.ItemStackSlotDisplay(ItemStackTemplate(it.item, count)) },
        )

    override fun getRecipeFilterName(): Component = ONLY_COMPOUNDABLE

    private companion object {
        /** The crafting book's own. */
        val FILTER_SPRITES = WidgetSprites(
            Identifier.withDefaultNamespace("recipe_book/filter_enabled"),
            Identifier.withDefaultNamespace("recipe_book/filter_disabled"),
            Identifier.withDefaultNamespace("recipe_book/filter_enabled_highlighted"),
            Identifier.withDefaultNamespace("recipe_book/filter_disabled_highlighted"),
        )

        val ONLY_COMPOUNDABLE: Component = Component.translatable("gui.agesandtheart.recipebook.toggleRecipes.compoundable")
    }
}
