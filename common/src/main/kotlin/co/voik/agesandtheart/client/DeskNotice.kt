package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.ui.Palette
import co.voik.agesandtheart.client.ui.Rect
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/**
 * What the desk last had to say, across the foot of whichever of its screens is open.
 *
 * One statement of where a notice goes, because the desk and its two wings all need it and a line that
 * appeared in one place and not another would read as the desk having heard nothing.
 */
object DeskNotice {

    fun extract(graphics: GuiGraphicsExtractor, font: Font, panel: Rect) {
        val line = DeskModel.notice ?: return
        if (System.currentTimeMillis() - DeskModel.noticeAt > SHOWN_FOR_MS) return
        val text = Component.translatable("container.agesandtheart.writers_desk.$line")
        graphics.text(
            font, text,
            panel.x + (panel.width - font.width(text)) / 2,
            panel.bottom - LIFT,
            Palette.WARNING,
            false,
        )
    }

    private const val SHOWN_FOR_MS = 4000L
    private const val LIFT = 14
}
