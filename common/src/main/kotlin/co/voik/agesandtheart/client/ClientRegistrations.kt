package co.voik.agesandtheart.client

import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.PalmWood
import net.minecraft.client.model.geom.ModelLayers
import net.minecraft.client.renderer.entity.BoatRenderer
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.gui.screens.inventory.MenuAccess
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.entity.NoopRenderer
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.entity.BlockEntityTypes

/**
 * The renderers and screens the client registers, listed once: each loader's client entrypoint loops over
 * these from its own registration event.
 *
 * Each entry keeps its pair's types together, since a loader's registration call is generic over them.
 */
object ClientRegistrations {
    class RendererForEntity<T : Entity>(val type: EntityType<out T>, val provider: EntityRendererProvider<T>)

    class RendererForBlockEntity<T : BlockEntity, S : BlockEntityRenderState>(
        val type: BlockEntityType<out T>,
        val provider: BlockEntityRendererProvider<T, S>,
    )

    /**
     * A plain function rather than vanilla's `MenuScreens.ScreenConstructor`, which is private in vanilla
     * and only widened on the loaders' side; each loader converts it.
     */
    class ScreenForMenu<M : AbstractContainerMenu, U>(
        val menu: MenuType<out M>,
        val screen: (M, Inventory, Component) -> U,
    ) where U : Screen, U : MenuAccess<M>

    val ENTITY_RENDERERS: List<RendererForEntity<*>> = listOf(
        RendererForEntity(AgeContent.HADALFISH, ::HadalfishRenderer),
        RendererForEntity(AgeContent.BOOK_ENTITY, ::BookEntityRenderer),
        RendererForEntity(AgeContent.SAND_COLUMN, ::SandColumnRenderer),
        RendererForEntity(AgeContent.VOLCANIC_BOMB) { MoltenLumpRenderer(it, MoltenLumpRenderer.WHOLE_LUMP) },
        RendererForEntity(AgeContent.LAVA_DROPLET) { MoltenLumpRenderer(it, MoltenLumpRenderer.GOBBET) },
        RendererForEntity(AgeContent.METEOR) {
            MoltenLumpRenderer(it, MoltenLumpRenderer.METEOR, MoltenLumpRenderer.METEOR_ROCK, MoltenLumpRenderer.COLD_FIRE)
        },
        RendererForEntity(AgeContent.DRIFTING_ORE) { DriftingOreRenderer(it) },
        // Vanilla's lightning, turned to point at what was bitten — see [ArcBoltRenderer].
        RendererForEntity(AgeContent.ARC_BOLT) { ArcBoltRenderer(it) },
        RendererForEntity(AgeContent.ASTRITE_GOLEM, ::AstriteGolemRenderer),
        RendererForEntity(AgeContent.SCARAB, ::ScarabRenderer),
        // The storm is a clock standing in the sky and is drawn by the sky, not as an entity.
        RendererForEntity(AgeContent.METEOR_STORM) { NoopRenderer(it) },
        RendererForEntity(AgeContent.CAVE_IN) { NoopRenderer(it) },
        RendererForEntity(AgeContent.CRUMBLING_COLUMN) { NoopRenderer(it) },
        // Birch's boats until the asset pass: a boat's texture follows its model layer, which no tint reaches.
        RendererForEntity(PalmWood.BOAT) { BoatRenderer(it, ModelLayers.BIRCH_BOAT) },
        RendererForEntity(PalmWood.CHEST_BOAT) { BoatRenderer(it, ModelLayers.BIRCH_CHEST_BOAT) },
    )

    val BLOCK_ENTITY_RENDERERS: List<RendererForBlockEntity<*, *>> = listOf(
        // The fissure's shaft, a block entity drawn by shader rather than by a baked model.
        RendererForBlockEntity(AgeContent.STAR_FISSURE_ENTITY) { StarFissureRenderer() },
        // In vanilla's place, for the books of ours a lectern can hold; vanilla's own it still draws as before.
        RendererForBlockEntity(BlockEntityTypes.LECTERN) { LecternBookRenderer(it) },
    )

    val MENU_SCREENS: List<ScreenForMenu<*, *>> = listOf(
        ScreenForMenu(AgeContent.WRITERS_DESK_MENU, ::WritersDeskScreen),
        ScreenForMenu(AgeContent.INK_CASE_MENU, ::InkCaseScreen),
        ScreenForMenu(AgeContent.SUPPLY_BIN_MENU, ::SupplyBinScreen),
        // Its own screen rather than a line on the desk's -- an implement that does something is the thing
        // you go and look at (Jonah, 2026-09-07).
        ScreenForMenu(AgeContent.SEISMOGRAPH_MENU, ::SeismographScreen),
        ScreenForMenu(AgeContent.GEOLOGISTS_TOOLS_MENU, ::GeologistsToolsScreen),
        ScreenForMenu(AgeContent.ARCHIVE_MENU, ::ArchiveScreen),
        ScreenForMenu(AgeContent.STATION_MENU, ::StationScreen),
        // Vanilla's own container screen: a toolbox is a chest's grid with a fence on what may go in it, and
        // the fence lives in the menu rather than in the drawing.
        ScreenForMenu(AgeContent.TOOLBOX_MENU, ::ContainerScreen),
    )
}
