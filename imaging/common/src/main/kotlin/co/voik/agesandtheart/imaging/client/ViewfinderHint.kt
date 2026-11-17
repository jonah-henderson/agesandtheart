package co.voik.agesandtheart.imaging.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.util.ARGB

/**
 * How to lower the viewfinder and how to zoom, a line each where the action bar stands and drawn as it is, for as long
 * and fading as it does. Its own, since the action bar holds one line.
 */
object ViewfinderHint {

    private const val SHOWN_FOR_MILLIS = 3_000L
    private const val FADES_FOR_MILLIS = 1_000L
    private const val FROM_THE_BOTTOM = 72
    private const val LINE_HEIGHT = 11
    private const val OPAQUE = 255

    private var lines: List<Component> = emptyList()
    private var shownAt = 0L

    fun show(vararg shown: Component) {
        lines = shown.toList()
        shownAt = System.currentTimeMillis()
    }

    @JvmStatic
    fun draw(graphics: GuiGraphicsExtractor) {
        val left = SHOWN_FOR_MILLIS + FADES_FOR_MILLIS - (System.currentTimeMillis() - shownAt)
        if (lines.isEmpty() || left <= 0) return
        val alpha = (OPAQUE * left / FADES_FOR_MILLIS).coerceAtMost(OPAQUE.toLong()).toInt()
        val font = Minecraft.getInstance().font
        val top = graphics.guiHeight() - FROM_THE_BOTTOM
        lines.forEachIndexed { index, line ->
            val width = font.width(line)
            graphics.textWithBackdrop(font, line, (graphics.guiWidth() - width) / 2, top + index * LINE_HEIGHT, width, ARGB.white(alpha))
        }
    }
}
