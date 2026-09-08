package co.voik.agesandtheart.client.light

import com.mojang.blaze3d.vertex.QuadInstance
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.util.ARGB
import net.minecraft.util.LightCoordsUtil
import net.minecraft.client.renderer.block.BlockAndTintGetter

/**
 * Tints a quad by whatever coloured light reaches it, at chunk-mesh time.
 *
 * **This is the whole of the rendering side**, and it is four lines of arithmetic on data vanilla has
 * already worked out. The single Mixin behind it exists only to reach a private method; everything it
 * touches is public API on [QuadInstance].
 *
 * **Occlusion is not computed here and does not need to be.** By the time this runs,
 * `BlockModelLighter.prepareQuadAmbientOcclusion` has filled the instance's per-corner light coords, so
 * `LightCoordsUtil.smoothBlock` says how much block light *actually reached* that corner — propagation,
 * shadowing and light bending round a corner all already resolved by the light engine. Weighting the hue
 * by that is what makes a tint stop at a wall for free.
 *
 * **Per corner rather than per quad**, which costs nothing extra: [QuadInstance] stores four colours and
 * four light coords, so a face nearer the source at one end comes out graded across it.
 */
object TintedLightPainter {

    /**
     * Paints [instance] for the face of the block at [pos], or leaves it alone.
     *
     * **The early-outs are the design.** This runs for every quad of every block in every section of every
     * world, so the common case — a pack with nothing registered, or country with nothing glowing in it —
     * has to cost a field read and a return. The order is deliberate: cheapest question first.
     */
    fun paint(level: BlockAndTintGetter, pos: BlockPos, instance: QuadInstance) {
        if (!TintedLights.castsAnything()) return
        // **The client's own level rather than the one being meshed**, because a `RenderSectionRegion`
        // keeps its level private and reaching it would cost a widener for a lookup key. The one place the
        // two differ is the linking panel's preview level, where the worst case is a previewed crystal not
        // colouring its wall. Worth revisiting if the panel ever wants this.
        val world = Minecraft.getInstance().level ?: return
        val tint = TintedLights.reaching(world, pos) ?: return
        for (corner in 0..<CORNERS) {
            val lit = LightCoordsUtil.smoothBlock(instance.getLightCoords(corner))
            if (lit <= UNLIT) continue
            val share = (lit.toFloat() / FULLY_LIT).coerceIn(NONE, ALL_OF_IT)
            instance.setColor(corner, ARGB.multiply(instance.getColor(corner), softened(tint, share)))
        }
    }

    /**
     * [tint] pulled back toward white by [share].
     *
     * A corner in shadow keeps its own colour and a corner under the source takes all of it, which is what
     * turns a flat multiply into something that reads as light falling on a surface rather than as paint.
     */
    private fun softened(tint: Int, share: Float): Int = ARGB.srgbLerp(
        share,
        ARGB.color(WHITE, WHITE, WHITE),
        tint,
    )

    private const val CORNERS = 4

    /**
     * The block light at which a surface takes the tint fully.
     *
     * Below vanilla's fifteen on purpose: a crystal at light twelve should still colour what it is standing
     * against, and anything brighter than this is being lit by something else as well.
     */
    private const val FULLY_LIT = 11.0f

    private const val UNLIT = 0
    private const val NONE = 0.0f
    private const val ALL_OF_IT = 1.0f
    private const val WHITE = 255
}
