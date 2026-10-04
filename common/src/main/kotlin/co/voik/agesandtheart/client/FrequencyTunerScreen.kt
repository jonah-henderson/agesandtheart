package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.desk.FrequencyTunerMenu
import co.voik.agesandtheart.desk.TunerProposalPayload
import co.voik.agesandtheart.desk.Tuning
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory
import kotlin.math.roundToInt

/**
 * The frequency tuner's screen: the mixed signal traced across the top, each signal's three dials beneath
 * it, and the book the signal proposes with a button to lay it on the desk.
 */
class FrequencyTunerScreen(menu: FrequencyTunerMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<FrequencyTunerMenu>(menu, inventory, title, WIDTH, HEIGHT) {

    private val dials = mutableListOf<DialSlider>()
    private lateinit var loadButton: Button
    private lateinit var releaseButton: Button

    override fun init() {
        super.init()
        addRenderableWidget(
            DecorationWidget(PanelSurface.RAISED).also {
                it.setPosition(leftPos, topPos)
                it.setSize(imageWidth, imageHeight)
            },
        )
        dials.clear()
        for (index in 0..<Tuning.DIALS) {
            val column = index / DIALS_PER_SIGNAL
            val row = index % DIALS_PER_SIGNAL
            val slider = DialSlider(
                x = leftPos + MARGIN + column * (DIAL_WIDTH + GAP),
                y = topPos + DIALS_TOP + row * (DIAL_HEIGHT + DIAL_GAP),
                index = index,
                step = menu.tuning.dials[index],
            )
            dials += addRenderableWidget(slider)
        }
        loadButton = addRenderableWidget(
            Button.builder(translated("load")) { press(FrequencyTunerMenu.LOAD) }
                .bounds(leftPos + MARGIN, topPos + BUTTONS_TOP, BUTTON_WIDTH, DIAL_HEIGHT).build(),
        )
        releaseButton = addRenderableWidget(
            Button.builder(translated("release")) { press(FrequencyTunerMenu.RELEASE) }
                .bounds(leftPos + imageWidth - MARGIN - BUTTON_WIDTH, topPos + BUTTONS_TOP, BUTTON_WIDTH, DIAL_HEIGHT).build(),
        )
    }

    override fun containerTick() {
        super.containerTick()
        val server = menu.tuning.dials
        dials.forEach { it.follow(server[it.index]) }
        loadButton.active = menu.hasADesk && proposal.isNotEmpty()
        releaseButton.active = menu.isTuned
    }

    /** The dials as the screen shows them, so the trace moves with a slider before the server has answered. */
    private fun shownTuning(): Tuning = Tuning.ofDials(dials.map { it.step }) ?: menu.tuning

    private fun press(button: Int) {
        Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, button)
    }

    /** Title only: there is no inventory on this screen. */
    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        graphics.text(font, title, titleLabelX, titleLabelY, Palette.TEXT, false)
        graphics.text(font, translated("first"), MARGIN, SIGNAL_LABEL_TOP, Palette.TEXT, false)
        graphics.text(font, translated("second"), MARGIN + DIAL_WIDTH + GAP, SIGNAL_LABEL_TOP, Palette.TEXT, false)
    }

    override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractContents(graphics, mouseX, mouseY, a)
        drawTrace(graphics)
        val heard = if (proposal.isEmpty()) {
            translated("nothing_heard")
        } else {
            Component.literal(proposal.joinToString(" ") { WordNames.readable(it).string })
        }
        graphics.textWithWordWrap(font, heard, leftPos + MARGIN, topPos + PROPOSAL_TOP, imageWidth - 2 * MARGIN, Palette.TEXT)
    }

    /** The two signals summed, one sample a pixel; faint while the desk is drawing its own seed. */
    private fun drawTrace(graphics: GuiGraphicsExtractor) {
        val left = leftPos + MARGIN
        val top = topPos + TRACE_TOP
        val width = imageWidth - 2 * MARGIN
        graphics.fill(left - 1, top - 1, left + width + 1, top + TRACE_HEIGHT + 1, Palette.WELL_EDGE)
        graphics.fill(left, top, left + width, top + TRACE_HEIGHT, SCREEN)
        val middle = top + TRACE_HEIGHT / 2
        val reach = TRACE_HEIGHT / 2 - 2
        val tuning = shownTuning()
        val ink = if (menu.isTuned) TRACE else TRACE_IDLE
        fun heightAt(column: Int) = middle - (tuning.mixedAt(column.toDouble() / width) / MOST_MIXED * reach).roundToInt()
        var previous = heightAt(0)
        for (column in 0..<width) {
            val here = heightAt(column)
            graphics.fill(left + column, minOf(previous, here), left + column + 1, maxOf(previous, here) + 1, ink)
            previous = here
        }
        if (!menu.isTuned) graphics.text(font, translated("untuned"), left + 3, top + 3, TRACE_IDLE, false)
    }

    /** One dial: sixteen steps, saying what it sets and never the number. */
    private inner class DialSlider(x: Int, y: Int, val index: Int, step: Int) :
        AbstractSliderButton(x, y, DIAL_WIDTH, DIAL_HEIGHT, Component.empty(), step.toDouble() / (Tuning.STEPS - 1)) {

        private var held = false

        val step: Int get() = (value * (Tuning.STEPS - 1)).roundToInt()

        private var sent = step

        init {
            updateMessage()
        }

        /** Takes the server's step, unless the writer is holding this dial. */
        fun follow(serverStep: Int) {
            if (held || serverStep == step) return
            value = serverStep.toDouble() / (Tuning.STEPS - 1)
            sent = serverStep
        }

        override fun updateMessage() {
            message = translated(DIAL_NAMES[index % DIALS_PER_SIGNAL])
        }

        override fun applyValue() {
            if (step == sent) return
            sent = step
            press(index * Tuning.STEPS + step)
        }

        override fun onClick(event: MouseButtonEvent, doubleClick: Boolean) {
            held = true
            super.onClick(event, doubleClick)
        }

        override fun onRelease(event: MouseButtonEvent) {
            held = false
            super.onRelease(event)
        }
    }

    companion object {
        /** The last book the server proposed, which arrives just after the screen. */
        private var proposal: List<Identifier> = emptyList()

        fun remember(payload: TunerProposalPayload) {
            proposal = payload.words
        }

        private fun translated(key: String): Component = Component.translatable("container.agesandtheart.frequency_tuner.$key")

        private val DIAL_NAMES = listOf("frequency", "amplitude", "phase")
        private const val DIALS_PER_SIGNAL = 3

        private const val WIDTH = 248
        private const val HEIGHT = 214
        private const val MARGIN = 8
        private const val GAP = 8
        private const val TRACE_TOP = 18
        private const val TRACE_HEIGHT = 48
        private const val SIGNAL_LABEL_TOP = 72
        private const val DIALS_TOP = 82
        private const val DIAL_WIDTH = 112
        private const val DIAL_HEIGHT = 20
        private const val DIAL_GAP = 2
        private const val PROPOSAL_TOP = 152
        private const val BUTTONS_TOP = 186
        private const val BUTTON_WIDTH = 112

        /** Two signals at full strength sum to at most this. */
        private const val MOST_MIXED = 2.0

        private val SCREEN = 0xFF0E1A14.toInt()
        private val TRACE = 0xFF7CF0C8.toInt()
        private val TRACE_IDLE = 0xFF3C6E5C.toInt()
    }
}
