package co.voik.agesandtheart.client

import co.voik.agesandtheart.client.AgeBookModel.BindingFace
import co.voik.agesandtheart.client.AgeBookModel.BindingPiece
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.network.chat.Style
import net.minecraft.util.ARGB
import net.minecraft.util.FormattedCharSequence
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.item.DyeColor
import org.joml.Quaternionf
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A book's name on its front board and down its spine, gilt until it is dyed, and glowing where a glow ink sac made it. A name too long to stand at the largest size is set
 * smaller, down to the smallest, rather than break a word; only at the smallest is a word broken, and what will not
 * fit even then is cut short with an ellipsis.
 */
object BookTitle {

    /** Where a name is set, in the face's own units, and how large it may stand. */
    internal data class Area(
        val width: Float,
        val height: Float,
        val mostLines: Int,
        val largestLine: Float,
        val smallestLine: Float,
    )

    /** The name's lines, and how many face units one unit of the font stands. */
    internal data class Fitted(val lines: List<String>, val scale: Float)

    /** On the front board: across it within a narrow margin, in its upper half. */
    internal val FRONT = Area(width = 5.0f, height = 3.5f, mostLines = 3, largestLine = 1.25f, smallestLine = 0.4f)

    /** Down the spine, within a margin at each end and side. */
    internal val SPINE = Area(width = 7.0f, height = 1.4f, mostLines = 1, largestLine = 1.2f, smallestLine = 0.45f)

    /**
     * What a name is written in: its colour, and whether it glows as a sign's writing does after a glow ink sac —
     * lit whatever the light, and outlined in a darker shade of itself.
     */
    data class Ink(val colour: Int, val glows: Boolean) {
        val outline: Int get() = if (!glows) NO_OUTLINE else if (colour == DyeColor.BLACK.textColor) GLOWING_BLACK_OUTLINE else ARGB.scaleRGB(colour, OUTLINE_SHADE)

        fun light(around: Int): Int = if (glows) LightCoordsUtil.FULL_BRIGHT else around
    }

    /** [title] in [ink] on [model]'s binding as the model was last posed. */
    fun submit(
        model: AgeBookModel,
        title: String,
        ink: Ink,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        lightCoords: Int,
    ) {
        val font = Minecraft.getInstance().font
        for (face in model.bindingFaces) {
            val area = when (face.piece) {
                BindingPiece.FRONT -> FRONT
                BindingPiece.SPINE -> SPINE
                BindingPiece.BACK -> continue
            }
            val fitted = fit(title, area, font.lineHeight) { font.width(it) }
            poseStack.pushPose()
            model.poseOnto(face.piece, poseStack)
            turnToRead(face, area, poseStack)
            poseStack.scale(fitted.scale / UNITS_PER_BLOCK, fitted.scale / UNITS_PER_BLOCK, fitted.scale / UNITS_PER_BLOCK)
            val linesTall = fitted.lines.size * font.lineHeight
            for ((index, line) in fitted.lines.withIndex()) {
                val x = -font.width(line) / 2.0f
                val y = index * font.lineHeight - linesTall / 2.0f + GLYPH_TOP_PADDING
                collector.order(TEXT_ORDER).submitText(
                    poseStack, x, y, FormattedCharSequence.forward(line, Style.EMPTY), false,
                    Font.DisplayMode.POLYGON_OFFSET, ink.light(lightCoords), ink.colour, NO_BACKGROUND, ink.outline,
                )
            }
            poseStack.popPose()
        }
    }

    /**
     * [poseStack] carried from [face]'s part to the middle of [area] on its outside, turned so the font's X reads
     * along it, its Y runs down the lines, and its face looks out. On the front the lines read away from the spine;
     * down the spine they read top to bottom, their tops toward the front.
     */
    private fun turnToRead(face: BindingFace, area: Area, poseStack: PoseStack) {
        val outside = (face.outside + TEXT_LIFT) / UNITS_PER_BLOCK
        val acrossTheMiddle = (face.acrossFrom + face.acrossTo) / 2 / UNITS_PER_BLOCK
        when (face.piece) {
            BindingPiece.FRONT -> {
                val middleOfTheArea = (face.top + TITLE_FROM_THE_TOP + area.height / 2) / UNITS_PER_BLOCK
                poseStack.translate(acrossTheMiddle, middleOfTheArea, outside)
                poseStack.rotate(Axis.YP.rotation(PI.toFloat()))
            }
            BindingPiece.SPINE -> {
                poseStack.translate(acrossTheMiddle, (face.top + face.bottom) / 2 / UNITS_PER_BLOCK, outside)
                poseStack.rotate(Quaternionf().rotateAxis(PI.toFloat(), DIAGONAL, DIAGONAL, 0.0f))
            }
            BindingPiece.BACK -> Unit
        }
    }

    /**
     * [title] set as large as it will stand in [area] with every word whole, or at the smallest, words broken and
     * cut short, where it will not.
     */
    internal fun fit(title: String, area: Area, lineHeight: Int, widthOf: (String) -> Int): Fitted {
        val words = title.trim().split(WHITESPACE)
        var lineSize = area.largestLine
        while (true) {
            val scale = lineSize / lineHeight
            val roomForLines = minOf(area.mostLines, floor(area.height / lineSize).toInt()).coerceAtLeast(1)
            val lineWidth = area.width / scale
            val lines = wrap(title, lineWidth, widthOf)
            val everyWordIsWhole = words.all { widthOf(it) <= lineWidth }
            if (everyWordIsWhole && lines.size <= roomForLines) return Fitted(lines, scale)
            val isTheSmallest = lineSize * SHRINK < area.smallestLine
            if (isTheSmallest) return Fitted(cutShort(lines, roomForLines, area.width / scale, widthOf), scale)
            lineSize *= SHRINK
        }
    }

    /** [text] in lines no wider than [width], broken between words, and within a word only where one is too wide. */
    internal fun wrap(text: String, width: Float, widthOf: (String) -> Int): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.trim().split(WHITESPACE)) {
            val joined = if (line.isEmpty()) word else "$line $word"
            if (widthOf(joined) <= width) {
                line = joined
                continue
            }
            if (line.isNotEmpty()) lines += line
            line = word
            while (widthOf(line) > width && line.length > 1) {
                val fits = (1..line.length).last { widthOf(line.take(it)) <= width || it == 1 }
                lines += line.take(fits)
                line = line.drop(fits)
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    /** The first [room] of [lines], the last ending in an ellipsis that still fits [width]. */
    private fun cutShort(lines: List<String>, room: Int, width: Float, widthOf: (String) -> Int): List<String> {
        val kept = lines.take(room)
        var last = kept.last()
        while (last.isNotEmpty() && widthOf(last + ELLIPSIS) > width) last = last.dropLast(1)
        return kept.dropLast(1) + (last.trimEnd() + ELLIPSIS)
    }

    private val WHITESPACE = Regex("\\s+")
    private const val ELLIPSIS = "…"

    /** Each step down in size, from the largest a name may stand to the smallest. */
    private const val SHRINK = 0.9f

    /** How far below the front board's top edge the name's area starts. */
    private const val TITLE_FROM_THE_TOP = 1.0f

    /** Off the face by a little more than the pattern, so the name lies over it. */
    private const val TEXT_LIFT = 0.01f

    /** The font's glyphs sit a pixel below the top of their line. */
    private const val GLYPH_TOP_PADDING = 1.0f

    private val DIAGONAL = (1 / sqrt(2.0)).toFloat()
    private const val UNITS_PER_BLOCK = 16.0f
    /** A name's colour until it is dyed. */
    const val GILT = 0xFFE3C565.toInt()
    private const val NO_BACKGROUND = 0
    private const val NO_OUTLINE = 0

    /** A sign's: a glowing name's outline is its own colour at this shade, and black's is a pale one instead. */
    private const val OUTLINE_SHADE = 0.4f
    private const val GLOWING_BLACK_OUTLINE = -988212

    /** After the pattern's base and its sixteen layers. */
    private const val TEXT_ORDER = 18
}
