package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.PlayerInventoryView
import co.voik.agesandtheart.client.ui.ProgressBar
import co.voik.agesandtheart.client.ui.SlotView
import co.voik.agesandtheart.desk.DeskSlots
import co.voik.agesandtheart.desk.GrammarGuideMenu
import co.voik.agesandtheart.desk.StudyTier
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * A full grammar guide: its notebook slot and a button to study a word of each tier, greyed and saying what it still
 * wants until the notebook can pay; while a study runs, how far it has gone in their place.
 */
class GrammarGuideScreen(menu: GrammarGuideMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<GrammarGuideMenu>(menu, inventory, title, DeskSlots.PANEL_WIDTH, DeskSlots.WING_PANEL_HEIGHT) {

    private val studyButtons = mutableMapOf<StudyTier, Button>()
    private lateinit var progressBar: ProgressBar

    override fun init() {
        super.init()
        inventoryLabelY = DeskSlots.WING_INVENTORY_LABEL_Y
        addRenderableWidget(
            DecorationWidget(PanelSurface.RAISED).also {
                it.setPosition(leftPos, topPos)
                it.setSize(imageWidth, imageHeight)
            },
        )
        addRenderableWidget(SlotView(leftPos + DeskSlots.WING_INTAKE_X, topPos + DeskSlots.WING_INTAKE_Y))
        addRenderableWidget(
            PlayerInventoryView(leftPos + DeskSlots.INVENTORY_X, topPos + DeskSlots.WING_INVENTORY_Y, DeskSlots.HOTBAR_DROP),
        )
        studyButtons.clear()
        for (tier in StudyTier.entries) {
            studyButtons[tier] = addRenderableWidget(
                Button.builder(buttonLabel(tier)) { press(tier) }
                    .bounds(leftPos + DeskSlots.INVENTORY_X, topPos + BUTTONS_TOP + tier.ordinal * BUTTON_PITCH, CONTENT_WIDTH, BUTTON_HEIGHT)
                    .build(),
            )
        }
        progressBar = addRenderableWidget(
            ProgressBar(CONTENT_WIDTH, BAR_HEIGHT, reading = { menu.studied }, colour = BAR_COLOUR, tooltip = ::progressTooltip).also {
                it.setPosition(leftPos + DeskSlots.INVENTORY_X, topPos + BAR_Y)
            },
        )
        showWhatTheGuideIsDoing()
    }

    override fun containerTick() {
        super.containerTick()
        showWhatTheGuideIsDoing()
    }

    private fun showWhatTheGuideIsDoing() {
        val isIdle = menu.state == GrammarGuideMenu.State.IDLE
        for ((tier, button) in studyButtons) {
            button.visible = isIdle
            button.active = menu.pagesWanting(tier) == 0
            button.message = buttonLabel(tier)
        }
        progressBar.visible = !isIdle
    }

    private fun buttonLabel(tier: StudyTier): Component {
        val wanting = menu.pagesWanting(tier)
        return if (wanting == 0) Component.translatable(STUDY + tier.serializedName)
        else Component.translatable(NEEDS + menu.paidIn(tier).serializedName, wanting)
    }

    private fun press(tier: StudyTier) {
        Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, tier.ordinal)
    }

    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        super.extractLabels(graphics, mouseX, mouseY)
        val label = when (menu.state) {
            GrammarGuideMenu.State.IDLE -> return
            GrammarGuideMenu.State.STUDYING -> Component.translatable(STUDYING + menu.tierStudied.serializedName)
            GrammarGuideMenu.State.DONE -> Component.translatable(STUDIED_FOR_ANOTHER)
        }
        graphics.text(font, label, DeskSlots.INVENTORY_X, LABEL_Y, Palette.TEXT, false)
    }

    private fun progressTooltip(): Component =
        Component.translatable(PROGRESS, (menu.studied * PERCENT).toInt().coerceIn(0, PERCENT.toInt()))

    private companion object {
        const val STUDY = "container.agesandtheart.grammar_guide.study."
        const val NEEDS = "container.agesandtheart.grammar_guide.needs."
        const val STUDYING = "container.agesandtheart.grammar_guide.studying."
        const val STUDIED_FOR_ANOTHER = "container.agesandtheart.grammar_guide.studied_for_another"
        const val PROGRESS = "container.agesandtheart.grammar_guide.progress"
        const val BUTTONS_TOP = 18
        const val BUTTON_PITCH = 18
        const val BUTTON_HEIGHT = 16
        const val CLEAR_OF_THE_SLOT = 6
        const val CONTENT_WIDTH = DeskSlots.WING_INTAKE_X - DeskSlots.INVENTORY_X - CLEAR_OF_THE_SLOT
        const val LABEL_Y = 24
        const val BAR_Y = 36
        const val BAR_HEIGHT = 10
        const val PERCENT = 100f

        /** The empty guide's purple, as studying it into being was. */
        val BAR_COLOUR = 0xFF8A5CC9.toInt()
    }
}
