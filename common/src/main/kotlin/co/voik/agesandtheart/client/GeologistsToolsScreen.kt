package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.reward.EarlyGameRareMaterial
import co.voik.agesandtheart.age.reward.Yield
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.RimeColour
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
     * The deposit, then one line per early material the Age would grow.
     *
     * **Like the seismograph, it is never idle**, and the calm cases are not interchangeable: a bare desk
     * has nothing to read at all, a plain world was never written and has no recipe to survey, and an Age
     * that holds nothing is a true answer about somewhere real.
     */
    override fun lines(): List<Component> {
        if (menu.source == ReadingSource.AN_IDLE_DESK) return listOf(translated("desk_idle"))
        if (menu.source == ReadingSource.A_PLAIN_WORLD) return listOf(translated("plain_world"))
        val deposit = Yield.entries.getOrNull(menu.deposit) ?: Yield.NONE
        val headline = translated(
            "deposit",
            Component.translatable(AgeContent.PITCHSTONE.descriptionId),
            translated(deposit.key),
        )
        return listOf(headline) + materialsIn(menu.materials).map { translated("grows", nameOf(it)) }
    }

    /**
     * What an early material is called, **taken from the block itself** so the survey can never name it
     * something other than what the player ends up holding.
     */
    private fun nameOf(material: EarlyGameRareMaterial): Component = when (material) {
        EarlyGameRareMaterial.RIME -> AgeContent.RIME_CRYSTAL_BLOCKS.getValue(RimeColour.CYAN).name
        EarlyGameRareMaterial.TEMPERSTONE -> AgeContent.TEMPERSTONE_BLOCK.name
        EarlyGameRareMaterial.ARC_CRYSTAL -> AgeContent.ARC_CRYSTAL_CLUSTER.name
        EarlyGameRareMaterial.GLOOMGRIT -> AgeContent.GLOOMGRIT_CLUSTER.name
    }
}
