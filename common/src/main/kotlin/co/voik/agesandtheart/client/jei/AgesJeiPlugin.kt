package co.voik.agesandtheart.client.jei

import co.voik.agesandtheart.client.CrystalViewerScreen
import co.voik.agesandtheart.client.FrequencyTunerScreen
import co.voik.agesandtheart.client.KnownMachineRecipes
import co.voik.agesandtheart.client.ReadoutScreen
import co.voik.agesandtheart.client.WritersDeskScreen
import co.voik.agesandtheart.location
import co.voik.agesandtheart.station.Compounder
import co.voik.agesandtheart.station.DryingRack
import co.voik.agesandtheart.station.MachineCatalogPayload
import co.voik.agesandtheart.station.Station
import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.constants.RecipeTypes
import mezz.jei.api.gui.handlers.IGuiContainerHandler
import mezz.jei.api.recipe.IRecipeManager
import mezz.jei.api.recipe.types.IRecipeType
import mezz.jei.api.registration.IGuiHandlerRegistration
import mezz.jei.api.registration.IRecipeCatalystRegistration
import mezz.jei.api.registration.IRecipeCategoryRegistration
import mezz.jei.api.registration.IRecipeRegistration
import mezz.jei.api.runtime.IJeiRuntime
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.Rect2i
import net.minecraft.resources.Identifier
import net.minecraft.world.item.crafting.CraftingRecipe
import net.minecraft.world.item.crafting.RecipeHolder

/**
 * What JEI shows of ours: a category for each machine, our crafting recipes the server never sends a client, and
 * no ingredient list beside the screens where looking up an item is no use.
 *
 * Loaded only by JEI, through `jei_mod_plugin` on Fabric and [JeiPlugin] on NeoForge, so nothing else touches a
 * class of JEI's. The recipes are [KnownMachineRecipes], which may arrive after JEI has started; a catalog heard
 * then replaces the one JEI was given.
 */
@JeiPlugin
class AgesJeiPlugin : IModPlugin {

    private var runtime: IJeiRuntime? = null

    /** What JEI was last given, so a later catalog can take its place. */
    private var given: Given? = null

    init {
        KnownMachineRecipes.whenHeard { catalog -> runtime?.let { replace(it.recipeManager, catalog) } }
    }

    override fun getPluginUid(): Identifier = "jei".location()

    override fun registerCategories(registration: IRecipeCategoryRegistration) {
        val guiHelper = registration.jeiHelpers.guiHelper
        val stations = Station.entries.map { station ->
            MachineCategory(MachineRecipeTypes.STATIONS.getValue(station), MachineRecipeTypes.machineOf(station), guiHelper)
        }
        registration.addRecipeCategories(
            *stations.toTypedArray(),
            MachineCategory(MachineRecipeTypes.DRYING, DryingRack.ITEM, guiHelper),
            CompoundingCategory(guiHelper),
        )
    }

    override fun registerRecipes(registration: IRecipeRegistration) {
        val next = Given(KnownMachineRecipes.catalog)
        for ((type, recipes) in next.byType) registration.addRecipes(type, recipes)
        given = next
    }

    override fun registerRecipeCatalysts(registration: IRecipeCatalystRegistration) {
        for (station in Station.entries) {
            registration.addCraftingStation(MachineRecipeTypes.STATIONS.getValue(station), MachineRecipeTypes.machineOf(station))
        }
        registration.addCraftingStation(MachineRecipeTypes.DRYING, DryingRack.ITEM)
        registration.addCraftingStation(MachineRecipeTypes.COMPOUNDING, Compounder.ITEM)
    }

    override fun registerGuiHandlers(registration: IGuiHandlerRegistration) {
        registration.addGenericGuiContainerHandler(ReadoutScreen::class.java, NoRoomBeside)
        registration.addGenericGuiContainerHandler(FrequencyTunerScreen::class.java, NoRoomBeside)
        registration.addGenericGuiContainerHandler(CrystalViewerScreen::class.java, NoRoomBeside)
        registration.addGenericGuiContainerHandler(WritersDeskScreen::class.java, NoRoomBeside)
    }

    override fun onRuntimeAvailable(jeiRuntime: IJeiRuntime) {
        runtime = jeiRuntime
        val catalog = KnownMachineRecipes.catalog
        if (given?.catalog != catalog) replace(jeiRuntime.recipeManager, catalog)
    }

    override fun onRuntimeUnavailable() {
        runtime = null
        given = null
    }

    private fun replace(recipes: IRecipeManager, catalog: MachineCatalogPayload) {
        given?.let { old -> for ((type, shown) in old.byType) recipes.hideRecipes(type, shown) }
        val next = Given(catalog)
        for ((type, shown) in next.byType) recipes.addRecipes(type, shown)
        given = next
    }

    /**
     * A catalog as JEI holds it: each recipe type with its recipes. The crafting recipes are made into
     * [RecipeHolder]s once and kept, since hiding one asks for the same holder that was added.
     */
    private class Given(val catalog: MachineCatalogPayload) {
        private val crafting: List<RecipeHolder<CraftingRecipe>> = catalog.crafting.map { RecipeHolder(it.key, it.recipe) }

        // Each list is of its own type's recipes; JEI's methods take the pair typed alike, which a list cannot say.
        @Suppress("UNCHECKED_CAST")
        val byType: List<Pair<IRecipeType<Any>, List<Any>>> = (
            Station.entries.map { MachineRecipeTypes.STATIONS.getValue(it) to catalog.recipesOf(it) } +
                listOf(
                    MachineRecipeTypes.DRYING to catalog.drying,
                    MachineRecipeTypes.COMPOUNDING to catalog.compounding,
                    RecipeTypes.CRAFTING to crafting,
                )
            ).map { (type, recipes) -> type as IRecipeType<Any> to recipes }
    }

    /**
     * Leaves JEI's ingredient list no room beside a screen, which is how it is kept off one: the readouts, the tuner
     * and the viewer have nothing to look up, and the writer's desk is too wide for the list to fit beside it.
     */
    private object NoRoomBeside : IGuiContainerHandler<AbstractContainerScreen<*>> {
        override fun getGuiExtraAreas(containerScreen: AbstractContainerScreen<*>): List<Rect2i> =
            listOf(Rect2i(0, 0, containerScreen.width, containerScreen.height))
    }
}
