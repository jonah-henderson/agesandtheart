package co.voik.agesandtheart.client

import co.voik.agesandtheart.Release
import com.mojang.serialization.MapCodec
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractSelectionList
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.LayoutElement
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.dialog.DialogConnectionAccess
import net.minecraft.client.gui.screens.dialog.DialogControlSet
import net.minecraft.client.gui.screens.dialog.DialogScreens
import net.minecraft.client.gui.screens.dialog.SimpleDialogScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.server.dialog.ActionButton
import net.minecraft.server.dialog.CommonDialogData
import net.minecraft.server.dialog.DialogAction
import net.minecraft.server.dialog.NoticeDialog
import net.minecraft.server.dialog.SimpleDialog
import net.minecraft.server.dialog.action.Action
import net.minecraft.util.FormattedCharSequence
import java.util.Optional

/** One release's notes, as written. */
data class ReleaseNotes(val release: Release, val text: String)

/**
 * The changelog as one of the game's dialogs, so a chat link opens it, drawn by [ChangelogScreen] rather than
 * vanilla's notice, whose every line is centred. Built on the client and never sent, so its codec is never asked
 * to read or write one.
 */
data class ChangelogDialog(val title: Component, val notes: List<ReleaseNotes>) : SimpleDialog {

    override fun common(): CommonDialogData =
        CommonDialogData(title, Optional.empty(), true, false, DialogAction.CLOSE, emptyList(), emptyList())

    override fun codec(): MapCodec<ChangelogDialog> = MAP_CODEC

    override fun onCancel(): Optional<Action> = Optional.empty()

    override fun mainActions(): List<ActionButton> = listOf(NoticeDialog.DEFAULT_ACTION)

    companion object {
        val MAP_CODEC: MapCodec<ChangelogDialog> =
            MapCodec.unit { throw UnsupportedOperationException("The changelog dialog is never read from data") }
    }
}

/**
 * The changelog: each release's heading over its notes, left aligned on the darkened panel vanilla's in-game lists
 * sit on, a bullet's wrapped lines hung beneath its text, and the whole scrolled where it is taller than the screen.
 * Vanilla's warning button, which marks a dialog a server sent, is left out: this one the client made.
 */
class ChangelogScreen(previous: Screen?, dialog: ChangelogDialog, access: DialogConnectionAccess) :
    SimpleDialogScreen<ChangelogDialog>(previous, dialog, access) {

    override fun createTitleWithWarningButton(): LayoutElement = StringWidget(title, font)

    override fun populateBodyElements(
        body: LinearLayout,
        controls: DialogControlSet,
        dialog: ChangelogDialog,
        access: DialogConnectionAccess,
    ) {
        body.addChild(NotesPanel(font, minOf(width - SIDE_MARGINS, WIDEST), dialog.notes))
    }

    companion object {
        /** Adds this screen beside vanilla's dialog screens, in the map they are looked up in by dialog type. */
        fun register() =
            DialogScreens.register(ChangelogDialog.MAP_CODEC) { previous, dialog, access -> ChangelogScreen(previous, dialog, access) }

        /** The panel's widest, so a long line stays a comfortable read on a wide window. */
        private const val WIDEST = 360

        private const val SIDE_MARGINS = 40
    }
}

/**
 * The notes laid out once, at a width, on a panel of vanilla's in-game list background: each release's heading
 * centred, a line's `[tag]` right aligned in a column as wide as the widest tag, bold and in its colour, and its text beside it, wrapped lines hung a little under it.
 */
private class NotesPanel(private val font: Font, panelWidth: Int, notes: List<ReleaseNotes>) :
    AbstractWidget(0, 0, panelWidth, 0, Component.empty()) {

    private data class Line(val indent: Int, val text: FormattedCharSequence, val top: Int)

    private data class Note(val tag: Component?, val text: String)

    private val lines: List<Line>
    private val spoken: Component

    init {
        val textWidth = panelWidth - 2 * PADDING
        val notesByRelease = notes.map { release -> release to release.text.lines().map(::noteOf) }
        val widestTag = notesByRelease.flatMap { (_, written) -> written.mapNotNull { it.tag } }.maxOfOrNull(font::width) ?: 0
        val textColumn = if (widestTag > 0) widestTag + COLUMN_GAP else 0
        val laid = mutableListOf<Line>()
        var top = PADDING
        for ((index, entry) in notesByRelease.withIndex()) {
            val (release, written) = entry
            if (index > 0) top += BETWEEN_RELEASES
            val heading = Component.translatable("chat.agesandtheart.changelog.heading", release.release.name)
                .withStyle(ChatFormatting.BOLD)
            laid += Line((textWidth - font.width(heading)) / 2, heading.visualOrderText, top)
            top += font.lineHeight + UNDER_HEADING
            for (note in written) {
                if (note.text.isBlank() && note.tag == null) {
                    top += BLANK_LINE
                    continue
                }
                val column = if (note.tag != null) textColumn else 0
                if (note.tag != null) laid += Line(widestTag - font.width(note.tag), note.tag.visualOrderText, top)
                val firstLine = font.splitter.splitLines(note.text, textWidth - column, Style.EMPTY).firstOrNull()?.string.orEmpty()
                laid += Line(column, FormattedCharSequence.forward(firstLine, Style.EMPTY), top)
                top += font.lineHeight + LINE_GAP
                val wrapped = note.text.removePrefix(firstLine).trimStart()
                if (wrapped.isEmpty()) continue
                for (part in font.split(FormattedText.of(wrapped), textWidth - column - WRAPPED_HANG)) {
                    laid += Line(column + WRAPPED_HANG, part, top)
                    top += font.lineHeight + LINE_GAP
                }
            }
        }
        lines = laid
        height = top + PADDING
        spoken = Component.literal(notes.joinToString("\n\n") { "${it.release.name}\n${it.text}" })
    }

    override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Screen.extractMenuBackgroundTexture(graphics, AbstractSelectionList.INWORLD_MENU_LIST_BACKGROUND, x, y, 0f, 0f, width, height)
        for (line in lines) graphics.text(font, line.text, x + PADDING + line.indent, y + line.top, TEXT, true)
    }

    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean = false

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        output.add(NarratedElementType.TITLE, spoken)
    }

    private companion object {
        /** A line's `[tag]` and the text after it. */
        val TAGGED = Regex("""^\s*\[([^\]]+)]\s*(.*)$""")

        /** Each tag's colour; one not named here is grey. */
        val TAG_COLOURS = mapOf(
            "feature" to 0xC77DFF,
            "art" to 0x6FA8FF,
            "balance" to 0xFF7043,
            "bugfix" to 0xFFE066,
        )
        const val UNNAMED_TAG_COLOUR = 0xAAAAAA

        fun noteOf(written: String): Note {
            val tagged = TAGGED.find(written) ?: return Note(null, written.trim())
            val (name, text) = tagged.destructured
            val colour = TAG_COLOURS[name.lowercase()] ?: UNNAMED_TAG_COLOUR
            val tag = Component.literal("[$name]").withStyle(Style.EMPTY.withBold(true).withColor(colour))
            return Note(tag, text)
        }

        const val PADDING = 6
        const val COLUMN_GAP = 6
        const val WRAPPED_HANG = 6
        const val LINE_GAP = 3
        const val UNDER_HEADING = 4
        const val BLANK_LINE = 5
        const val BETWEEN_RELEASES = 20
        const val TEXT = 0xFFFFFFFF.toInt()
    }
}
