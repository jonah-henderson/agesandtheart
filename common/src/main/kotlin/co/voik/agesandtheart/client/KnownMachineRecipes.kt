package co.voik.agesandtheart.client

import co.voik.agesandtheart.station.MachineCatalogPayload
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.display.SlotDisplayContext
import net.minecraft.world.level.Level

/**
 * The recipes of ours the server last sent ([MachineCatalogPayload]), and who wants to hear when they change — a
 * recipe viewer that has already started, since the catalog can arrive after it has.
 */
object KnownMachineRecipes {
    var catalog: MachineCatalogPayload = MachineCatalogPayload.EMPTY
        private set

    private val listeners = mutableListOf<(MachineCatalogPayload) -> Unit>()

    fun remember(payload: MachineCatalogPayload) {
        catalog = payload
        listeners.forEach { it(payload) }
    }

    /** Calls [listener] with every catalog heard from now on. */
    fun whenHeard(listener: (MachineCatalogPayload) -> Unit) {
        listeners += listener
    }

    /** Whether [stack] dries on a drying rack, as far as the recipes the server sent say. */
    fun dries(stack: ItemStack, level: Level): Boolean {
        if (stack.isEmpty) return false
        val context = SlotDisplayContext.fromLevel(level)
        return catalog.drying.any { recipe -> recipe.input.resolveForStacks(context).any { ItemStack.isSameItem(it, stack) } }
    }

    /** For a client leaving a server, whose recipes mean nothing on the next. */
    fun forget() {
        catalog = MachineCatalogPayload.EMPTY
    }
}
