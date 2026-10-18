package co.voik.agesandtheart.client.light

import co.voik.agesandtheart.Constants
import com.mojang.blaze3d.vertex.QuadInstance
import net.minecraft.client.resources.model.geometry.BakedQuad
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.ARGB
import net.minecraft.util.LightCoordsUtil

/**
 * Tints a quad by whatever coloured light reaches it, at chunk-mesh time.
 *
 * **The brightness is vanilla's and only the colour is ours.** Where the light reaching a corner is a coloured
 * source's own ([ColouredLightField]), the corner takes the source's colour in full, at any distance, and the
 * light engine dims it as it dims a jack o'lantern's. Where something else lights it more brightly, a torch
 * beside the crystal, it takes the colour only as far as the source's own light is a share of what is shown
 * there, so the colour fades to plain light exactly where the source's light ends.
 *
 * **Per corner rather than per quad**, which costs nothing extra: a quad carries four colours and four
 * light coords, so a face nearer the source at one end comes out graded across it.
 */
object TintedLightPainter {

    /** Whether there is nothing to paint — the free question, asked before a quad is even wrapped. */
    fun isIdle(): Boolean = !TintedLights.anythingIsPlaced() || !ColouredLightField.holdsAnything()

    /**
     * Paints [instance] for the face of the block at [pos], or leaves it alone — vanilla's chunk mesher,
     * where a quad's geometry and its lighting are two objects.
     */
    fun paint(pos: BlockPos, quad: BakedQuad, instance: QuadInstance) {
        if (isIdle()) return
        paintCorners(pos, vanillaQuads.get().over(quad, instance))
    }

    fun paint(pos: BlockPos, quad: LitQuad) {
        if (isIdle()) return
        paintCorners(pos, quad)
    }

    private fun paintCorners(pos: BlockPos, quad: LitQuad) {
        for (corner in 0..<CORNERS) {
            val litByAnything = LightCoordsUtil.smoothBlock(quad.lightAt(corner))
            if (litByAnything <= UNLIT) continue
            val coloured = ColouredLightField.cornerOf(
                Math.round(pos.x + quad.cornerX(corner)),
                Math.round(pos.y + quad.cornerY(corner)),
                Math.round(pos.z + quad.cornerZ(corner)),
                quad.facing,
            ) ?: continue
            val hue = hueOf(coloured) ?: continue
            val share = shareOfTheLight(coloured.brightest, litByAnything)
            if (litByAnything > brightestSeen) noteTheBrightest(litByAnything, share)
            val before = quad.colourAt(corner)
            val tinted = ARGB.multiply(before, softened(hue, share))
            quad.tint(corner, asBrightAs(before, tinted))
        }
    }

    /**
     * How much of the light shown at a corner is the coloured sources' own: their brightest light there against
     * all of it, each as bright as the lightmap draws it. The whole of it where they are the brightest light,
     * and falling to nothing as their light gives out under a brighter one.
     */
    private fun shareOfTheLight(colouredLevel: Int, litByAnything: Int): Float {
        val shown = ColouredLightField.brightnessOf(litByAnything / LIGHT_UNITS_PER_LEVEL)
        if (shown <= NONE) return NONE
        return (ColouredLightField.brightnessOf(colouredLevel.toFloat()) / shown).coerceIn(NONE, ALL_OF_IT)
    }

    /**
     * The sources' colours summed and scaled until the brightest channel is full: **light adds**, so red and blue
     * make magenta and two reds the same red, where averaging them would mix them as paint does.
     */
    private fun hueOf(coloured: ColouredLightField.Corner): Int? {
        val peak = maxOf(coloured.red, coloured.green, coloured.blue)
        if (peak <= NONE) return null
        fun atFullSaturation(channel: Float) = (channel / peak * FULL_CHANNEL).toInt().coerceIn(0, FULL_CHANNEL)
        return ARGB.color(FULL_CHANNEL, atFullSaturation(coloured.red), atFullSaturation(coloured.green), atFullSaturation(coloured.blue))
    }

    /**
     * [tinted] scaled back up to [before]'s brightness, as near as it can go.
     *
     * **A tint is a multiply, which only darkens**: a saturated colour takes most of a surface's brightness with
     * the channels it removes, and under the sun, where nothing draws brighter, it is a dark stain. Scaling back up
     * keeps the surface as bright as vanilla draws it wherever the channels have room. Where a channel would pass
     * full it stops, and what is left over shows as a paler tint rather than a darker one.
     */
    private fun asBrightAs(before: Int, tinted: Int): Int {
        val wanted = luminanceOf(before)
        val got = luminanceOf(tinted)
        if (got <= NONE) return tinted
        val raise = wanted / got
        return ARGB.color(
            ARGB.alpha(tinted),
            raised(ARGB.red(tinted), raise),
            raised(ARGB.green(tinted), raise),
            raised(ARGB.blue(tinted), raise),
        )
    }

    private fun raised(channel: Int, by: Float): Int = (channel * by).toInt().coerceAtMost(FULL_CHANNEL)

    /** Rec. 709's weights, which are how bright each channel reads. */
    private fun luminanceOf(colour: Int): Float =
        RED_WEIGHT * ARGB.red(colour) + GREEN_WEIGHT * ARGB.green(colour) + BLUE_WEIGHT * ARGB.blue(colour)

    /**
     * Says how bright the brightest corner painted so far was, each time that climbs — the one number the two
     * renderers can disagree about, since they hand over different objects and everything after that is shared.
     * Two workers can climb past each other and say so twice, which costs a duplicate line and nothing else.
     */
    private fun noteTheBrightest(lit: Int, share: Float) {
        brightestSeen = lit
        Constants.LOG.info(
            "Coloured light: brightest corner so far {} of {}, the coloured light {}% of it",
            lit,
            FULLY_LIT,
            Math.round(share * PERCENT),
        )
    }

    @Volatile
    private var brightestSeen = 0

    fun forgetTheBrightest() {
        brightestSeen = 0
    }

    /**
     * Vanilla's two objects seen as one [LitQuad].
     *
     * Reused per mesher thread rather than allocated per quad: this is the hottest loop the mod touches,
     * and the pair it holds is replaced by [over] immediately before every use.
     */
    private class VanillaQuad : LitQuad {
        private lateinit var quad: BakedQuad
        private lateinit var instance: QuadInstance

        fun over(quad: BakedQuad, instance: QuadInstance): LitQuad = also {
            this.quad = quad
            this.instance = instance
        }

        override fun lightAt(corner: Int): Int = instance.getLightCoords(corner)

        override fun colourAt(corner: Int): Int = instance.getColor(corner)

        override fun tint(corner: Int, colour: Int) = instance.setColor(corner, colour)

        override fun cornerX(corner: Int): Float = quad.position(corner).x()

        override fun cornerY(corner: Int): Float = quad.position(corner).y()

        override fun cornerZ(corner: Int): Float = quad.position(corner).z()

        override val facing: Direction get() = quad.direction()
    }

    private val vanillaQuads = ThreadLocal.withInitial { VanillaQuad() }

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

    private const val PERCENT = 100

    /** The brightest corner [LightCoordsUtil.smoothBlock] reports, and how many of its units make a light level. */
    private const val FULLY_LIT = 240
    private const val LIGHT_UNITS_PER_LEVEL = 16f

    private const val UNLIT = 0

    private const val FULL_CHANNEL = 255
    private const val RED_WEIGHT = 0.2126f
    private const val GREEN_WEIGHT = 0.7152f
    private const val BLUE_WEIGHT = 0.0722f
    private const val NONE = 0.0f
    private const val ALL_OF_IT = 1.0f
    private const val WHITE = 255
}
