package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.ui.scrollChildUnder
import co.voik.agesandtheart.age.word.WordNames
import co.voik.agesandtheart.client.ui.DecorationWidget
import co.voik.agesandtheart.client.ui.DialKnob
import co.voik.agesandtheart.client.ui.ItemIconButton
import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.PanelSurface
import co.voik.agesandtheart.client.ui.Rect
import co.voik.agesandtheart.content.AgeContent
import co.voik.agesandtheart.client.panel.PanelDistortion
import co.voik.agesandtheart.desk.FrequencyTunerMenu
import co.voik.agesandtheart.desk.InterferenceBand
import co.voik.agesandtheart.math.mix64
import co.voik.agesandtheart.math.unitFloat
import co.voik.agesandtheart.client.ui.ScrollingText
import net.minecraft.util.ARGB
import net.minecraft.util.Util
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
 * The frequency tuner's screen: a power switch, a switch between the two signals, three knobs for the one switched
 * to and the interference knob, beside two screens — that signal over the other, muted, and the two mixed beneath —
 * and under them the book the signal proposes, scrolling where it is long, with a button to copy it to the desk.
 *
 * The traces drift leftward, as a scope's do when it is not triggered in step with what it shows; each repeats
 * across the screen exactly, so the drift wraps without a seam. Interference tears them as instability tears a
 * linking panel ([PanelDistortion]): bands of a screen taken from a copy of the trace a little out of step, and a
 * white wash over it, more of both the further it is turned.
 */
class FrequencyTunerScreen(menu: FrequencyTunerMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<FrequencyTunerMenu>(menu, inventory, title, WIDTH, HEIGHT) {

    private val knobs = mutableListOf<DialKnob>()
    private lateinit var powerButton: Button
    private lateinit var signalButton: Button
    private lateinit var copyButton: ItemIconButton
    private lateinit var interferenceKnob: DialKnob
    private lateinit var readout: ScrollingText
    private var shownMessage: String? = null

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
        interferenceKnob = DialKnob(KNOB, InterferenceBand.STEPS, translated("interference")) { step ->
            press(FrequencyTunerMenu.INTERFERENCE_BUTTONS.first + step)
        }
        interferenceKnob.setPosition(leftPos + MARGIN, topPos + KNOBS_TOP + DIALS_PER_SIGNAL * KNOB_PITCH)
        interferenceKnob.follow(menu.interference)
        addRenderableWidget(interferenceKnob)
        val readoutWidth = imageWidth - 2 * MARGIN - BUTTON_HEIGHT - LABEL_GAP - ScrollingText.ROOM
        readout = addRenderableWidget(ScrollingText(leftPos + MARGIN, topPos + READOUT_TOP, readoutWidth, READOUT_HEIGHT, font))
        shownMessage = null
        copyButton = addRenderableWidget(
            ItemIconButton(BUTTON_HEIGHT, translated("copy"), { ItemStack(AgeContent.WRITERS_DESK) }) {
                press(FrequencyTunerMenu.COPY)
            }.also {
                it.setPosition(leftPos + imageWidth - MARGIN - BUTTON_HEIGHT, topPos + READOUT_TOP)
                it.setTooltip(Tooltip.create(translated("copy")))
            },
        )
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean =
        super.mouseScrolled(mouseX, mouseY, scrollX, scrollY) || scrollChildUnder(mouseX, mouseY, scrollX, scrollY)

    override fun containerTick() {
        super.containerTick()
        val server = menu.tuning.dials
        knobs.forEachIndexed { role, knob -> knob.follow(server[dialIndex(role)]) }
        interferenceKnob.follow(menu.interference)
        showProposal()
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
        if (button != FrequencyTunerMenu.COPY) isAwaitingProposal = true
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
        val interferenceY = KNOBS_TOP + DIALS_PER_SIGNAL * KNOB_PITCH + (KNOB - font.lineHeight) / 2 + 1
        graphics.text(font, translated("interference"), MARGIN + KNOB + LABEL_GAP, interferenceY, Palette.TEXT, false)
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
            val interfered = Interference(interferenceKnob.step.toFloat() / (InterferenceBand.STEPS - 1))
            // One drift for all three traces, so the mixed one stays the sum of the two above it.
            val drift = (Util.getMillis() * DRIFT_PIXELS_PER_SECOND / MILLIS_PER_SECOND % screensWidth).toInt()
            interfered.draw(graphics, signalScreen) { shift ->
                drawTrace(graphics, signalScreen, MOST_ONE_SIGNAL, MUTED_INKS[other], drift + shift, tuning.signal(other)::at)
                drawTrace(graphics, signalScreen, MOST_ONE_SIGNAL, INKS[selected], drift + shift, tuning.signal(selected)::at)
            }
            interfered.draw(graphics, mixedScreen) { shift ->
                drawTrace(graphics, mixedScreen, MOST_MIXED, MIXED_INK, drift + shift, tuning::mixedAt)
            }
        }
    }

    /**
     * What the signal proposes, beside the button that copies it: an ellipsis while the server works it out, and
     * nothing while the tuner is off.
     */
    private fun showProposal() {
        readout.visible = menu.isPowered
        val heard = when {
            !menu.isPowered -> Component.empty()
            isAwaitingProposal -> Component.literal(".".repeat(1 + (Util.getMillis() / MILLIS_PER_DOT % DOTS).toInt()))
            proposal.isEmpty() && menu.interference > 0 -> translated("nothing_heard.interference")
            proposal.isEmpty() -> translated("nothing_heard")
            else -> Component.literal(proposal.joinToString(" ") { WordNames.readable(it).string })
        }
        if (heard.string == shownMessage) return
        shownMessage = heard.string
        readout.setMessage(heard)
    }

    private fun drawScreen(graphics: GuiGraphicsExtractor, at: Rect) {
        graphics.fill(at.x - 1, at.y - 1, at.right + 1, at.bottom + 1, Palette.WELL_EDGE)
        graphics.fill(at.x, at.y, at.right, at.bottom, SCREEN)
    }

    /**
     * [sample] across [at], one sample a pixel, scaled so [most] reaches nearly to the screen's edge — [shift] pixels
     * out of step, for a band interference has taken from the other copy.
     */
    private fun drawTrace(graphics: GuiGraphicsExtractor, at: Rect, most: Double, ink: Int, shift: Int, sample: (Double) -> Double) {
        val middle = at.y + at.height / 2
        val reach = at.height / 2 - 2
        fun heightAt(column: Int) = middle - (sample((column + shift).toDouble() / at.width) / most * reach).roundToInt()
        var previous = heightAt(0)
        for (column in 0..<at.width) {
            val here = heightAt(column)
            graphics.fill(at.x + column, minOf(previous, here), at.x + column + 1, maxOf(previous, here) + 1, ink)
            previous = here
        }
    }

    /**
     * The traces' tearing at [unsettled], nought to one: a screen drawn whole, then each stray band cleared and drawn
     * again out of step, as a panel's bands take the other field; changing at a cut, every [MILLIS_PER_CUT].
     */
    private class Interference(private val unsettled: Float) {
        fun draw(graphics: GuiGraphicsExtractor, at: Rect, traces: (shift: Int) -> Unit) {
            traces(0)
            if (!PanelDistortion.distorts(unsettled)) return
            val cut = Util.getMillis() / MILLIS_PER_CUT
            val strays = PanelDistortion.strayChanceAt(unsettled)
            val mostShift = (MOST_SHIFT * unsettled).roundToInt().coerceAtLeast(1)
            for (band in 0..<at.height / BAND_HEIGHT) {
                val roll = unitFloat(mix64(cut * CUT_STRIDE xor band.toLong() * BAND_STRIDE))
                if (roll >= strays) continue
                val top = at.y + band * BAND_HEIGHT
                val shift = (unitFloat(mix64(roll.toBits().toLong() xor SHIFT_SALT)) * 2 * mostShift).roundToInt() - mostShift
                graphics.enableScissor(at.x, top, at.right, top + BAND_HEIGHT)
                graphics.fill(at.x, top, at.right, top + BAND_HEIGHT, SCREEN)
                traces(shift)
                graphics.disableScissor()
            }
            val wash = PanelDistortion.washAt(unsettled)
            if (wash > 0) graphics.fill(at.x, at.y, at.right, at.bottom, ARGB.color(wash, FULLY, FULLY, FULLY))
        }
    }

    companion object {
        /** The last book the server proposed, which arrives just after the screen. */
        private var proposal: List<Identifier> = emptyList()

        /** Which signal the knobs turn — remembered between openings, as a real dial's switch would be. */
        private var selected = 0

        /** Whether a knob has moved since the server last proposed a book. */
        private var isAwaitingProposal = false

        fun remember(payload: TunerProposalPayload) {
            proposal = payload.words
            isAwaitingProposal = false
        }

        private fun translated(key: String, vararg arguments: Any): Component =
            Component.translatable("container.agesandtheart.frequency_tuner.$key", *arguments)

        private val DIAL_NAMES = listOf("frequency", "amplitude", "phase")
        private const val DIALS_PER_SIGNAL = 3

        private const val WIDTH = 248
        private const val HEIGHT = 214
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
        private const val READOUT_TOP = 170
        private const val READOUT_HEIGHT = 36

        /** A panel's band height, two pixels, and how far out of step a torn band of trace can sit at full interference. */
        private const val BAND_HEIGHT = 2
        private const val MOST_SHIFT = 12
        private const val MILLIS_PER_CUT = 150L
        private const val FULLY = 255
        private const val MILLIS_PER_DOT = 300L
        private const val DOTS = 3
        private const val DRIFT_PIXELS_PER_SECOND = 9L
        private const val MILLIS_PER_SECOND = 1000L
        private const val CUT_STRIDE = -0x61C8_8646_80B5_83EBL
        private const val BAND_STRIDE = 0x2545_F491_4F6C_DD1DL
        private const val SHIFT_SALT = 0x3C6E_F372L

        /** One signal at full strength reaches this; the two summed reach twice it. */
        private const val MOST_ONE_SIGNAL = 1.0
        private const val MOST_MIXED = 2.0

        private val SCREEN = 0xFF101014.toInt()

        /** Signal A red and signal B blue, the Spire's and Haven's; mixed, the violet between them. */
        private val INKS = listOf(0xFFF07C7C.toInt(), 0xFF7CA8F0.toInt())
        private val MUTED_INKS = listOf(0xFF5A2E2E.toInt(), 0xFF2E3E5A.toInt())
        private val MIXED_INK = 0xFFC09CF0.toInt()
    }
}
