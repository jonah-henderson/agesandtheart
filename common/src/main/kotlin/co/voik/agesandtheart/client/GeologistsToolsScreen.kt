package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.reward.EarlyGameRareMaterial
import co.voik.agesandtheart.age.reward.Yield
import co.voik.agesandtheart.desk.GeologistsToolsMenu
import co.voik.agesandtheart.desk.ReadingSource
import co.voik.agesandtheart.desk.materialsIn
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * What the geologist's tools say, on their own panel.
 *
 * **Quantities and names, and never a forecast of what makes them** (design §7.7). Saying what the danger
 * *was* would be a preview of the Age; saying what comes out of the ground is an outcome, and it survives
 * an evocative word the writer themselves cannot unpack. The lesson — that the Ages surveying well are the
 * ones with hazards written into them — is left to be noticed rather than told.
 */
class GeologistsToolsScreen(menu: GeologistsToolsMenu, inventory: Inventory, title: Component) :
    ReadoutScreen<GeologistsToolsMenu>(
        menu, inventory, title,
        mostListedLines = EarlyGameRareMaterial.entries.size,
        translationPrefix = "container.agesandtheart.geologists_tools",
    ) {

    /**
     * A header, then one line per thing the Age's ground holds: deretheni by how much of it there is, and
     * each early material it would grow by name (Jonah, 2026-10-01).
     *
     * **Like the seismograph, it is never idle**, and the calm cases are not interchangeable: a bare desk
     * has nothing to read at all, a plain world was never written and has no recipe to survey, and an Age
     * that holds nothing is a true answer about somewhere real.
     */
    override fun lines(): List<Component> {
        if (menu.source == ReadingSource.AN_IDLE_DESK) return listOf(translated("desk_idle"))
        if (menu.source == ReadingSource.A_PLAIN_WORLD) return listOf(translated("plain_world"))
        val deposit = Yield.entries.getOrNull(menu.deposit) ?: Yield.NONE
        val deretheni = if (deposit == Yield.NONE) null else translated("amount.${deposit.key}", translated("material.pitchstone"))
        val held = listOfNotNull(deretheni) + materialsIn(menu.materials).map { translated("material.${keyOf(it)}") }
        if (held.isEmpty()) return listOf(translated("contains_nothing"))
        return listOf(translated("contains")) + held.map { translated("item", it) }
    }

    /** An early material's survey name: plain and uncoloured, rime being any of its colours. */
    private fun keyOf(material: EarlyGameRareMaterial): String = when (material) {
        EarlyGameRareMaterial.RIME -> "rime"
        EarlyGameRareMaterial.TEMPERSTONE -> "temperstone"
        EarlyGameRareMaterial.ARC_CRYSTAL -> "arc_crystal"
        EarlyGameRareMaterial.GLOOMGRIT -> "gloomgrit"
    }
}
