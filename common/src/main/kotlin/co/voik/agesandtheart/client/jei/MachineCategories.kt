package co.voik.agesandtheart.client.jei

import co.voik.agesandtheart.client.needsSaying
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.location
import co.voik.agesandtheart.station.CompoundingRecipeDisplay
import co.voik.agesandtheart.station.Compounder
import co.voik.agesandtheart.station.ListedRecipe
import co.voik.agesandtheart.station.Station
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder
import mezz.jei.api.helpers.IGuiHelper
import mezz.jei.api.recipe.IFocusGroup
import mezz.jei.api.recipe.category.AbstractRecipeCategory
import mezz.jei.api.recipe.types.IRecipeType
import net.minecraft.client.Minecraft
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.display.SlotDisplayContext
import net.minecraft.world.level.ItemLike

/** The recipe types our categories hold, one per machine. */
object MachineRecipeTypes {
    val STATIONS: Map<Station, IRecipeType<ListedRecipe>> =
        Station.entries.associateWith { IRecipeType.create(it.recipeName.location(), ListedRecipe::class.java) }

    val DRYING: IRecipeType<ListedRecipe> = IRecipeType.create("drying".location(), ListedRecipe::class.java)

    val COMPOUNDING: IRecipeType<CompoundingRecipeDisplay> =
        IRecipeType.create("compounding".location(), CompoundingRecipeDisplay::class.java)

    /** The block a station is. */
    fun machineOf(station: Station): ItemLike = when (station) {
        Station.GRINDER -> AgeContent.GRINDER
        Station.PULPER -> AgeContent.PULPER
    }
}

/** A machine that makes one thing of another — a station or the drying rack. */
class MachineCategory(type: IRecipeType<ListedRecipe>, machine: ItemLike, guiHelper: IGuiHelper) :
    AbstractRecipeCategory<ListedRecipe>(type, ItemStack(machine).hoverName, guiHelper.createDrawableItemLike(machine), WIDTH, HEIGHT) {

    override fun setRecipe(builder: IRecipeLayoutBuilder, recipe: ListedRecipe, focuses: IFocusGroup) {
        builder.addInputSlot(INPUT_X, SLOT_Y).setStandardSlotBackground().add(recipe.input)
        builder.addOutputSlot(OUTPUT_X, SLOT_Y).setOutputSlotBackground().add(recipe.result)
    }

    override fun createRecipeExtras(builder: IRecipeExtrasBuilder, recipe: ListedRecipe, focuses: IFocusGroup) {
        builder.addRecipeArrow().setPosition(ARROW_X, ARROW_Y)
    }

    private companion object {
        const val WIDTH = 86
        const val HEIGHT = 26
        const val INPUT_X = 5
        const val OUTPUT_X = 65
        const val SLOT_Y = 5
        const val ARROW_X = 30
        const val ARROW_Y = 4
    }
}

/** The fusion compounder: up to four inputs, each in a count, and what it needs standing beside it. */
class CompoundingCategory(guiHelper: IGuiHelper) : AbstractRecipeCategory<CompoundingRecipeDisplay>(
    MachineRecipeTypes.COMPOUNDING,
    ItemStack(Compounder.ITEM).hoverName,
    guiHelper.createDrawableItemLike(Compounder.ITEM),
    WIDTH,
    HEIGHT,
) {

    override fun setRecipe(builder: IRecipeLayoutBuilder, recipe: CompoundingRecipeDisplay, focuses: IFocusGroup) {
        val level = Minecraft.getInstance().level
        for ((index, input) in recipe.inputs.withIndex()) {
            val slot = builder.addInputSlot(FIRST_INPUT_X + index * SLOT_PITCH, SLOT_Y).setStandardSlotBackground()
            val count = recipe.counts.getOrElse(index) { 1 }
            // A display carries no count, so the stacks are resolved here to be shown in the recipe's.
            if (level == null) slot.add(input)
            else slot.addItemStacks(input.resolveForStacks(SlotDisplayContext.fromLevel(level)).map { it.copyWithCount(count) })
        }
        builder.addOutputSlot(OUTPUT_X, SLOT_Y).setOutputSlotBackground().add(recipe.result())
    }

    override fun createRecipeExtras(builder: IRecipeExtrasBuilder, recipe: CompoundingRecipeDisplay, focuses: IFocusGroup) {
        builder.addRecipeArrow().setPosition(ARROW_X, ARROW_Y)
        if (recipe.needs.isNotEmpty()) {
            builder.addText(needsSaying(recipe.needs), WIDTH, NEEDS_HEIGHT).setPosition(0, NEEDS_Y)
        }
    }

    private companion object {
        const val SLOT_PITCH = 18
        const val FIRST_INPUT_X = 1
        const val SLOT_Y = 5
        const val ARROW_X = 4 * SLOT_PITCH + 6
        const val ARROW_Y = 4
        const val OUTPUT_X = ARROW_X + 34
        const val WIDTH = OUTPUT_X + 22
        const val NEEDS_Y = 30
        const val NEEDS_HEIGHT = 20
        const val HEIGHT = NEEDS_Y + NEEDS_HEIGHT
    }
}
