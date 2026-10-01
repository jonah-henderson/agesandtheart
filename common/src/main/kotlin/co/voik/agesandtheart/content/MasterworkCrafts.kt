package co.voik.agesandtheart.content

import co.voik.agesandtheart.location
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.Item

/**
 * What the masterwork grades pass through on their way (design §7.1.2): the ink cake, pressed of carapace
 * powder and cured under a black sun, and the wet sheet, formed of yema pulp and dried in an Age with a lava sea.
 */
object MasterworkCrafts {

    private val registeredItems = mutableListOf<Pair<Identifier, Item>>()

    val INK_CAKE: Item = item("ink_cake")
    val CURED_INK_CAKE: Item = item("cured_ink_cake")
    val WET_PAPER_SHEET: Item = item("wet_paper_sheet")

    val items: List<Pair<Identifier, Item>> get() = registeredItems

    private fun item(path: String): Item {
        val id = path.location()
        return Item(Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))).also { registeredItems += id to it }
    }
}
