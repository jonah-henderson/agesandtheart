package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.InkTier
import co.voik.agesandtheart.client.ui.CapsuleGauge
import co.voik.agesandtheart.client.ui.CountedItem
import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.PlayerInventoryView
import co.voik.agesandtheart.client.ui.Rect
import co.voik.agesandtheart.client.ui.SlotView
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.content.AgeFluids
import co.voik.agesandtheart.desk.DeskSlots
import co.voik.agesandtheart.desk.DeskWingMenu
import co.voik.agesandtheart.desk.InkCaseMenu
import co.voik.agesandtheart.desk.SupplyBinMenu
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * A wing of the desk: one doorway, one thing to look at, and the player's own inventory.
 *
 * **Deliberately small**, which is the whole point of taking them out of the desk. Each answers one
 * question, so neither needs tabs, a search box or a list — and a screen with nothing to arrange can be a
 * panel, a slot and a column.
 *
 * Widgets go on **back to front**, as everywhere in `client/ui`: the order here is the order they stack.
 */
abstract class DeskWingScreen<Menu : DeskWingMenu>(
    menu: Menu,
    inventory: Inventory,
    title: Component,
) : AbstractContainerScreen<Menu>(menu, inventory, title, DeskSlots.PANEL_WIDTH, DeskSlots.WING_PANEL_HEIGHT) {

    override fun init() {
        super.init()
        inventoryLabelY = DeskSlots.WING_INVENTORY_LABEL_Y

        addRenderableWidget(panel(Rect(leftPos, topPos, DeskSlots.PANEL_WIDTH, DeskSlots.WING_PANEL_HEIGHT)))
        addRenderableWidget(SlotView(leftPos + DeskSlots.WING_INTAKE_X, topPos + DeskSlots.WING_INTAKE_Y))
        addRenderableWidget(
            PlayerInventoryView(
                leftPos + DeskSlots.INVENTORY_X,
                topPos + DeskSlots.WING_INVENTORY_Y,
                DeskSlots.HOTBAR_DROP,
            ),
        )

        val shown = contents()
        shown.arrangeElements()
        // Centred in the room the doorway does not want — see `DeskSlots.WING_INTAKE_X`.
        shown.setPosition(
            leftPos + (DeskSlots.PANEL_WIDTH - shown.width) / 2,
            topPos + CONTENT_TOP,
        )
        shown.visitWidgets(::addRenderableWidget)
    }

    /** What this wing is *for*, laid out. Arranged and positioned by [init]. */
    protected abstract fun contents(): LinearLayout

    /** The desk's own line, so a page filed from here says where it went. */
    override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractContents(graphics, mouseX, mouseY, a)
        DeskNotice.extract(
            graphics, font,
            Rect(leftPos, topPos, DeskSlots.PANEL_WIDTH, DeskSlots.WING_PANEL_HEIGHT),
        )
    }

    private fun panel(at: Rect): AbstractWidget = DecorationWidget(PanelSurface.RAISED).also {
        it.setPosition(at.x, at.y)
        it.setSize(at.width, at.height)
    }

    protected companion object {
        /** Clear of the title, as every container screen's contents are. */
        const val CONTENT_TOP = 20

        const val GAUGE_WIDTH = 9

        /** Short enough to clear the inventory's label, which the taller one ran into. */
        const val GAUGE_HEIGHT = 46
        const val GAUGE_GAP = 6
        const val STOCK_WIDTH = 46

        /** Four lines of stock in the same room the three gauges have. */
        const val STOCK_LINE = 12
    }
}

/** The ink case: three tanks, read as gauges, and a doorway to pour into them. */
class InkCaseScreen(menu: InkCaseMenu, inventory: Inventory, title: Component) :
    DeskWingScreen<InkCaseMenu>(menu, inventory, title) {

    override fun contents(): LinearLayout = DeskStockDisplay.inkGauges(GAUGE_WIDTH, GAUGE_HEIGHT, GAUGE_GAP)
}

/** The supply bin: what a book is made of, as opposed to what it says. */
class SupplyBinScreen(menu: SupplyBinMenu, inventory: Inventory, title: Component) :
    DeskWingScreen<SupplyBinMenu>(menu, inventory, title) {

    override fun contents(): LinearLayout = DeskStockDisplay.stockColumn(STOCK_WIDTH, STOCK_LINE)
}

/**
 * How the desk's stock is shown, the same on the desk's own wing and on the wing screens — each passes its
 * own sizes, and nothing else about the two may differ.
 */
object DeskStockDisplay {
    /** What a binding looks like in a stock column. */
    val BINDING: ItemStack = ItemStack(Items.LEATHER)

    fun paperIcon(tier: InkTier): ItemStack = when (tier) {
        InkTier.COMMON -> ItemStack(Items.PAPER)
        InkTier.FINE -> ItemStack(AgeContent.FINE_PAPER)
        InkTier.MASTERWORK -> ItemStack(AgeContent.MASTERWORK_PAPER)
    }

    /** Masterwork ink has no bottle of its own yet, so it borrows fine's until the asset pass. */
    fun inkIcon(tier: InkTier): ItemStack = ItemStack(AgeContent.INK_BOTTLES[tier] ?: AgeContent.FINE_INK_BOTTLE)

    /** What [tier] paper is called, wherever the desk names it. */
    fun paperName(tier: InkTier): Component = paperIcon(tier).hoverName

    /** What [tier] ink is called, wherever the desk names it. */
    fun inkName(tier: InkTier): Component =
        Component.translatable("container.agesandtheart.writers_desk.ink.${tier.key}")

    /** Exactly what the tank holds, since a gauge can only ever say roughly — in buckets, never loader units. */
    fun tankTooltip(tier: InkTier): Component = Component.translatable(
        "container.agesandtheart.writers_desk.ink",
        inkName(tier),
        DeskModel.inBuckets(DeskModel.ink(tier)),
        AgeFluids.TANK_CAPACITY_BUCKETS,
    )

    /** The three tanks side by side, each a gauge in its own colour. */
    fun inkGauges(width: Int, height: Int, spacing: Int): LinearLayout =
        LinearLayout.horizontal().spacing(spacing).apply {
            InkTier.entries.forEach { tier -> addChild(inkGauge(tier, width, height)) }
        }

    /** Paper by tier and then bindings, a line each. */
    fun stockColumn(width: Int, line: Int): LinearLayout = LinearLayout.vertical().apply {
        InkTier.entries.forEach { tier ->
            addChild(CountedItem(width, line, icon = { paperIcon(tier) }, count = { DeskModel.paper(tier) }))
        }
        addChild(CountedItem(width, line, icon = { BINDING }, count = { DeskModel.binding() }))
    }

    private fun inkGauge(tier: InkTier, width: Int, height: Int): CapsuleGauge =
        CapsuleGauge(
            width, height,
            reading = { DeskModel.ink(tier).toFloat() / DeskModel.inkCapacity().coerceAtLeast(1) },
            colour = { AgeFluids.INKS[tier]?.tint ?: Palette.TEXT },
            tooltip = { tankTooltip(tier) },
        )
}
