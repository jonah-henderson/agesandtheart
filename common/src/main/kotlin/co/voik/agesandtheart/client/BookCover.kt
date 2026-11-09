package co.voik.agesandtheart.client

import co.voik.agesandtheart.book.CoverLayout
import co.voik.agesandtheart.client.AgeBookModel.BindingFace
import co.voik.agesandtheart.client.AgeBookModel.BindingPiece
import co.voik.agesandtheart.content.AgeComponents
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.renderer.Sheets
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.resources.model.sprite.SpriteGetter
import net.minecraft.client.resources.model.sprite.SpriteId
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.entity.BannerPatternLayers
import java.util.EnumSet
import kotlin.math.floor

/**
 * A book's banner pattern, drawn over every face of its binding but those turned to the pages, layer by layer as a
 * banner draws its own (`BannerRenderer.submitPatterns`): the base, then each pattern tinted with its dye, laid out
 * on the boards' and spine's outside faces as the book's [CoverLayout] says. Their edges, and the insets where the
 * boards stand past the spine, carry on the outside face's last row or column.
 */
object BookCover {

    /** What a patterned book wears. */
    data class Worn(val base: DyeColor, val layers: BannerPatternLayers, val layout: CoverLayout) {
        companion object {
            /** What [book] wears, or null where it was never patterned. */
            fun on(book: ItemStack): Worn? {
                val base = book.get(DataComponents.BASE_COLOR) ?: return null
                val layers = book.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY)
                return Worn(base, layers, book.getOrDefault(AgeComponents.COVER_LAYOUT, CoverLayout.DEFAULT))
            }
        }
    }

    /** [worn] over [model]'s binding as the model was last posed, after the book itself has been submitted. */
    fun submit(
        model: AgeBookModel,
        worn: Worn,
        poseStack: PoseStack,
        collector: SubmitNodeCollector,
        sprites: SpriteGetter,
        lightCoords: Int,
    ) {
        val layers = layersOf(worn)
        for (face in model.bindingFaces) {
            poseStack.pushPose()
            model.poseOnto(face.piece, poseStack)
            val quads = quadsOf(model.bindingFaces, face, worn.layout)
            for ((index, layer) in layers.withIndex()) {
                val sprite = sprites.get(layer.sprite)
                val colour = layer.colour.textureDiffuseColor
                val renderType = layer.sprite.renderType { atlas -> RenderTypes.bannerPattern(atlas) }
                collector.order(FIRST_LAYER_ORDER + index).submitCustomGeometry(poseStack, renderType) { pose, buffer ->
                    for (quad in quads) {
                        for (corner in quad.corners) {
                            buffer.addVertex(pose, corner.x, corner.y, corner.z)
                                .setColor(colour)
                                .setUv(sprite.getU(corner.u), sprite.getV(corner.v))
                                .setOverlay(OverlayTexture.NO_OVERLAY)
                                .setLight(lightCoords)
                                .setNormal(pose, quad.normalX, quad.normalY, quad.normalZ)
                        }
                    }
                }
            }
            poseStack.popPose()
        }
    }

    private data class Layer(val sprite: SpriteId, val colour: DyeColor)

    /** The base and as many layers as a banner draws. */
    private fun layersOf(worn: Worn): List<Layer> {
        val patterns = worn.layers.layers().take(MOST_LAYERS).map { Layer(Sheets.getBannerSprite(it.pattern()), it.color()) }
        return listOf(Layer(Sheets.BANNER_PATTERN_BASE, worn.base)) + patterns
    }

    /** One corner of a quad: where it lies in its part, in blocks, and where on the pattern sprite it reads. */
    private data class Corner(val x: Float, val y: Float, val z: Float, val u: Float, val v: Float)

    private class Quad(val corners: List<Corner>, val normalX: Float, val normalY: Float, val normalZ: Float)

    /**
     * The quads over [piece]'s faces: the faces of the piece grown by a hair all round, so they lie off the book's
     * own faces and still meet each other at every edge, wound as the cube's own are so the same side shows. Each
     * corner reads the pattern where it lies over the outside face, and an edge a hair inside it, so it reads the
     * outside face's last row or column rather than past it.
     */
    private fun quadsOf(binding: List<BindingFace>, piece: BindingFace, layout: CoverLayout): List<Quad> {
        val box = boxOf(piece)
        return box.polygons.map { polygon ->
            val normal = polygon.normal
            val isAnEndEdge = normal.x() != 0.0f
            val isATopOrBottomEdge = normal.y() != 0.0f
            val corners = polygon.vertices.map { vertex ->
                val acrossTheFace = (vertex.x - piece.acrossFrom) / (piece.acrossTo - piece.acrossFrom)
                val downTheFace = (vertex.y - piece.top) / (piece.bottom - piece.top)
                val across = if (isAnEndEdge) acrossTheFace.coerceIn(EDGE, 1 - EDGE) else acrossTheFace.coerceIn(0.0f, 1.0f)
                val down = if (isATopOrBottomEdge) downTheFace.coerceIn(EDGE, 1 - EDGE) else downTheFace.coerceIn(0.0f, 1.0f)
                val (fieldX, fieldY) = fieldAt(layout, binding, piece, across, down)
                Corner(
                    vertex.x / UNITS_PER_BLOCK,
                    vertex.y / UNITS_PER_BLOCK,
                    vertex.z / UNITS_PER_BLOCK,
                    (FIELD_LEFT + fieldX) / PATTERN_TEXTURE_SIZE,
                    (FIELD_TOP + fieldY) / PATTERN_TEXTURE_SIZE,
                )
            }
            Quad(corners, normal.x(), normal.y(), normal.z())
        }
    }

    /** [piece] as the model's own cube grown by [LIFT], every face but the one turned to the pages. */
    private fun boxOf(piece: BindingFace): ModelPart.Cube {
        val minX = minOf(piece.acrossFrom, piece.acrossTo)
        val minY = minOf(piece.top, piece.bottom)
        val shown = EnumSet.complementOf(EnumSet.of(Direction.NORTH))
        return ModelPart.Cube(
            0, 0,
            minX, minY, piece.outside - piece.thickness,
            piece.width, piece.height, piece.thickness,
            LIFT, LIFT, LIFT,
            false, PATTERN_TEXTURE_SIZE, PATTERN_TEXTURE_SIZE,
            shown,
        )
    }

    /**
     * Where on the banner's field, in its pixels, the point of [face] of [binding] [across] and [down] of the way
     * over it lies.
     * The field is cropped to each face's shape and never stretched, except along the spine's single column.
     */
    internal fun fieldAt(
        layout: CoverLayout,
        binding: List<BindingFace>,
        face: BindingFace,
        across: Float,
        down: Float,
    ): Pair<Float, Float> {
        val board = binding.first { it.piece == BindingPiece.FRONT }
        val spine = binding.first { it.piece == BindingPiece.SPINE }
        return when (layout) {
            CoverLayout.MIRRORED -> {
                val shown = FIELD_WIDTH * board.height / board.width
                val fieldY = (FIELD_HEIGHT - shown) / 2 + down * shown
                val fieldX = if (face.piece == BindingPiece.SPINE) columnAt(0.0f) else across * FIELD_WIDTH
                fieldX to fieldY
            }
            CoverLayout.WRAP_AROUND -> {
                val wrapped = board.width + spine.width + board.width
                val shown = FIELD_WIDTH * wrapped / board.height
                val alongTheWrap = when (face.piece) {
                    BindingPiece.BACK -> (1 - across) * board.width
                    BindingPiece.SPINE -> board.width + across * spine.width
                    BindingPiece.FRONT -> board.width + spine.width + across * board.width
                }
                (1 - down) * FIELD_WIDTH to (FIELD_HEIGHT - shown) / 2 + alongTheWrap / wrapped * shown
            }
            CoverLayout.FRONT_AND_BACK -> {
                val half = FIELD_HEIGHT / 2
                val shown = half * board.width / board.height
                val inset = (FIELD_WIDTH - shown) / 2
                when (face.piece) {
                    BindingPiece.FRONT -> inset + across * shown to down * half
                    BindingPiece.SPINE -> columnAt(inset) to down * half
                    BindingPiece.BACK -> inset + (1 - across) * shown to half + down * half
                }
            }
        }
    }

    /** The middle of the field's column at [fieldX], which a spine is drawn from end to end. */
    private fun columnAt(fieldX: Float): Float = floor(fieldX) + 0.5f

    /** A banner's own layers, after the book itself at the collector's first order. */
    private const val FIRST_LAYER_ORDER = 1

    /** As many layers as a banner draws. */
    private const val MOST_LAYERS = 16

    /** Off the board by a hair all round, so the layers lie over it rather than fight it for depth. */
    private const val LIFT = 0.005f

    /** How far inside the outside face an edge reads it, so as to read its last row rather than past it. */
    private const val EDGE = 0.01f

    private const val UNITS_PER_BLOCK = 16.0f

    /** The front of a banner's flag in its 64-pixel pattern textures: 20 by 40, a pixel in from the corner. */
    private const val PATTERN_TEXTURE_SIZE = 64.0f
    private const val FIELD_LEFT = 1.0f
    private const val FIELD_TOP = 1.0f
    internal const val FIELD_WIDTH = 20.0f
    internal const val FIELD_HEIGHT = 40.0f
}
