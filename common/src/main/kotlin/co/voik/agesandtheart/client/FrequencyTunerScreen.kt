package co.voik.agesandtheart.client

import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.DialKnob
import co.voik.agesandtheart.client.ui.ItemIconButton
import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.Rect
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.desk.FrequencyTunerMenu
import co.voik.agesandtheart.desk.TunerProposalPayload
import co.voik.agesandtheart.desk.Tuning
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import kotlin.math.roundToInt

/**
 * The frequency tuner's screen: a power switch, a switch between the two signals and three knobs for the
 * one switched to, beside two screens — that signal over the other, muted, and the two mixed beneath — and
 * under them the book the signal proposes, with a button to copy it to the desk.
 */
class FrequencyTunerScreen(menu: FrequencyTunerMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<FrequencyTunerMenu>(menu, inventory, title, WIDTH, HEIGHT) {

    private val knobs = mutableListOf<DialKnob>()
    private lateinit var powerButton: Button
    private lateinit var signalButton: Button
    private lateinit var copyButton: ItemIconButton

    override fun init() {
        super.init()
        addRenderableWidget(
            DecorationWidget(PanelSurface.RAISED).also {
                it.setPosition(leftPos, topPos)
                it.setSize(imageWidth, imageHeight)
            },
        )
        powerButton = addRenderableWidget(
            Button.builder(powerLabel()) { press(FrequencyTunerMenu.POWER) }
                .bounds(leftPos + MARGIN, topPos + POWER_TOP, CONTROLS_WIDTH, BUTTON_HEIGHT).build(),
        )
        signalButton = addRenderableWidget(
            Button.builder(signalLabel()) { switchSignal() }
                .bounds(leftPos + MARGIN, topPos + SIGNAL_TOP, CONTROLS_WIDTH, BUTTON_HEIGHT).build(),
        )
        knobs.clear()
        for (role in 0..<DIALS_PER_SIGNAL) {
            val knob = DialKnob(KNOB, Tuning.STEPS, translated(DIAL_NAMES[role])) { step ->
                press(dialIndex(role) * Tuning.STEPS + step)
            }
            knob.setPosition(leftPos + MARGIN, topPos + KNOBS_TOP + role * KNOB_PITCH)
            knob.setTooltip(Tooltip.create(translated(DIAL_NAMES[role])))
            knob.follow(menu.tuning.dials[dialIndex(role)])
            knobs += addRenderableWidget(knob)
        }
        copyButton = addRenderableWidget(
            ItemIconButton(BUTTON_HEIGHT, translated("copy"), { ItemStack(AgeContent.WRITERS_DESK) }) {
                press(FrequencyTunerMenu.COPY)
            }.also {
                it.setPosition(leftPos + imageWidth - MARGIN - BUTTON_HEIGHT, topPos + READOUT_TOP)
                it.setTooltip(Tooltip.create(translated("copy")))
            },
        )
    }

    override fun containerTick() {
        super.containerTick()
        val server = menu.tuning.dials
        knobs.forEachIndexed { role, knob -> knob.follow(server[dialIndex(role)]) }
        powerButton.message = powerLabel()
        signalButton.message = signalLabel()
        val why = whyNotCopied()
        copyButton.active = why == null
        if (why != copyRefusal) {
            copyRefusal = why
            val said = translated("copy").copy()
            why?.let { said.append(CommonComponents.NEW_LINE).append(translated(it).copy().withStyle(ChatFormatting.GRAY)) }
            copyButton.setTooltip(Tooltip.create(said))
        }
    }

    /** What the copy button says it is waiting for — the tuner off, no desk in the room, nothing heard. */
    private var copyRefusal: String? = null

    private fun whyNotCopied(): String? = when {
        !menu.isPowered -> "copy.off"
        !menu.hasADesk -> "copy.no_desk"
        proposal.isEmpty() -> "copy.nothing"
        else -> null
    }

    /** Which of the six dials the knob for [role] turns: the switched-to signal's. */
    private fun dialIndex(role: Int): Int = selected * DIALS_PER_SIGNAL + role

    private fun switchSignal() {
        selected = 1 - selected
        signalButton.message = signalLabel()
        knobs.forEachIndexed { role, knob -> knob.follow(menu.tuning.dials[dialIndex(role)]) }
    }

    /** The dials as the screen shows them, so the trace moves with a knob before the server has answered. */
    private fun shownTuning(): Tuning {
        val dials = menu.tuning.dials.toMutableList()
        knobs.forEachIndexed { role, knob -> dials[dialIndex(role)] = knob.step }
        return Tuning.ofDials(dials) ?: menu.tuning
    }

    private fun press(button: Int) {
        Minecraft.getInstance().gameMode?.handleInventoryButtonClick(menu.containerId, button)
    }

    private fun powerLabel(): Component = translated(if (menu.isPowered) "power.on" else "power.off")

    private fun signalLabel(): Component = translated(if (selected == 0) "signal.a" else "signal.b")

    /** Title and the knobs' names: there is no inventory on this screen. */
    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        graphics.text(font, title, titleLabelX, titleLabelY, Palette.TEXT, false)
        for ((role, name) in DIAL_NAMES.withIndex()) {
            val labelY = KNOBS_TOP + role * KNOB_PITCH + (KNOB - font.lineHeight) / 2 + 1
            graphics.text(font, translated(name), MARGIN + KNOB + LABEL_GAP, labelY, Palette.TEXT, false)
        }
    }

    override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractContents(graphics, mouseX, mouseY, a)
        val tuning = shownTuning()
        val screensLeft = leftPos + SCREENS_X
        val screensWidth = imageWidth - SCREENS_X - MARGIN
        val signalScreen = Rect(screensLeft, topPos + SIGNAL_SCREEN_TOP, screensWidth, SCREEN_HEIGHT)
        val mixedScreen = Rect(screensLeft, topPos + MIXED_SCREEN_TOP, screensWidth, SCREEN_HEIGHT)
        drawScreen(graphics, signalScreen)
        drawScreen(graphics, mixedScreen)
        if (menu.isPowered) {
            val other = 1 - selected
            drawTrace(graphics, signalScreen, MOST_ONE_SIGNAL, MUTED_INKS[other], tuning.signal(other)::at)
            drawTrace(graphics, signalScreen, MOST_ONE_SIGNAL, INKS[selected], tuning.signal(selected)::at)
            drawTrace(graphics, mixedScreen, MOST_MIXED, MIXED_INK, tuning::mixedAt)
        }
        drawReadout(graphics)
    }

    /** What the signal proposes, beside the button that copies it; nothing while the tuner is off. */
    private fun drawReadout(graphics: GuiGraphicsExtractor) {
        if (!menu.isPowered) return
        val heard = if (proposal.isEmpty()) {
            translated("nothing_heard")
        } else {
            Component.literal(proposal.joinToString(" ") { WordNames.readable(it).string })
        }
        val width = imageWidth - 2 * MARGIN - BUTTON_HEIGHT - LABEL_GAP
        graphics.textWithWordWrap(font, heard, leftPos + MARGIN, topPos + READOUT_TOP, width, READOUT_INK)
    }

    private fun drawScreen(graphics: GuiGraphicsExtractor, at: Rect) {
        graphics.fill(at.x - 1, at.y - 1, at.right + 1, at.bottom + 1, Palette.WELL_EDGE)
        graphics.fill(at.x, at.y, at.right, at.bottom, SCREEN)
    }

    /** [sample] across [at], one sample a pixel, scaled so [most] reaches nearly to the screen's edge. */
    private fun drawTrace(graphics: GuiGraphicsExtractor, at: Rect, most: Double, ink: Int, sample: (Double) -> Double) {
        val middle = at.y + at.height / 2
        val reach = at.height / 2 - 2
        fun heightAt(column: Int) = middle - (sample(column.toDouble() / at.width) / most * reach).roundToInt()
        var previous = heightAt(0)
        for (column in 0..<at.width) {
            val here = heightAt(column)
            graphics.fill(at.x + column, minOf(previous, here), at.x + column + 1, maxOf(previous, here) + 1, ink)
            previous = here
        }
    }

    companion object {
        /** The last book the server proposed, which arrives just after the screen. */
        private var proposal: List<Identifier> = emptyList()

        /** Which signal the knobs turn — remembered between openings, as a real dial's switch would be. */
        private var selected = 0

        fun remember(payload: TunerProposalPayload) {
            proposal = payload.words
        }

        private fun translated(key: String): Component = Component.translatable("container.agesandtheart.frequency_tuner.$key")

        private val DIAL_NAMES = listOf("frequency", "amplitude", "phase")
        private const val DIALS_PER_SIGNAL = 3

        private const val WIDTH = 248
        private const val HEIGHT = 178
        private const val MARGIN = 8
        private const val CONTROLS_WIDTH = 76
        private const val BUTTON_HEIGHT = 20
        private const val POWER_TOP = 18
        private const val SIGNAL_TOP = 42
        private const val KNOBS_TOP = 66
        private const val KNOB = 20
        private const val KNOB_PITCH = 24
        private const val LABEL_GAP = 4
        private const val SCREENS_X = MARGIN + CONTROLS_WIDTH + 6
        private const val SIGNAL_SCREEN_TOP = 18
        private const val MIXED_SCREEN_TOP = 80
        private const val SCREEN_HEIGHT = 58
        private const val READOUT_TOP = 146

        /** One signal at full strength reaches this; the two summed reach twice it. */
        private const val MOST_ONE_SIGNAL = 1.0
        private const val MOST_MIXED = 2.0

        private val SCREEN = 0xFF101014.toInt()
        private val READOUT_INK = 0xFFFFFFFF.toInt()

        /** Signal A red and signal B blue, the Spire's and Haven's; mixed, the violet between them. */
        private val INKS = listOf(0xFFF07C7C.toInt(), 0xFF7CA8F0.toInt())
        private val MUTED_INKS = listOf(0xFF5A2E2E.toInt(), 0xFF2E3E5A.toInt())
        private val MIXED_INK = 0xFFC09CF0.toInt()
    }
}
