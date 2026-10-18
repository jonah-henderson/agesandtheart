package co.voik.agesandtheart.client.light

import co.voik.agesandtheart.Constants
import com.mojang.blaze3d.vertex.QuadInstance
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.model.geometry.BakedQuad
import net.minecraft.core.BlockPos
import net.minecraft.util.ARGB
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.level.Level
import kotlin.math.sqrt

/**
 * Tints a quad by whatever coloured light reaches it, at chunk-mesh time.
 *
 * **This is the whole of the rendering side**, and it is four lines of arithmetic on data the renderer has
 * already worked out. It is written against [LitQuad] so that it is the same four lines on both loaders;
 * what differs is only where a renderer hands its quads over.
 *
 * **Occlusion is not computed here and does not need to be.** By the time this runs the per-corner light
 * coords are filled, so `LightCoordsUtil.smoothBlock` says how much block light *actually reached* that
 * corner — propagation, shadowing and light bending round a corner all already resolved by the light
 * engine. Weighting the hue by that is what makes a tint stop at a wall for free.
 *
 * **Per corner rather than per quad**, which costs nothing extra: a quad carries four colours and four
 * light coords, so a face nearer the source at one end comes out graded across it.
 */
object TintedLightPainter {

    /** Whether there is nothing to paint — the free question, asked before a quad is even wrapped. */
    fun isIdle(): Boolean = !TintedLights.anythingIsPlaced()

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
        // **The client's own level rather than the one being meshed**, because a `RenderSectionRegion`
        // keeps its level private and reaching it would cost a widener for a lookup key. The one place the
        // two differ is the linking panel's preview level, where the worst case is a previewed crystal not
        // colouring its wall. Worth revisiting if the panel ever wants this.
        val world = Minecraft.getInstance().level ?: return
        // One reading at the block to reject the common case, **through the same cache the corners use**:
        // every quad of a block asks it, so it is answered once and read from memory thereafter.
        tintAt(world, pos.x, pos.y, pos.z) ?: return
        for (corner in 0..<CORNERS) {
            val light = quad.lightAt(corner)
            val litByAnything = LightCoordsUtil.smoothBlock(light)
            if (litByAnything <= UNLIT) continue
            if (litByAnything > brightestSeen) noteTheBrightest(litByAnything)
            val reaching = tintAtVertex(world, pos, quad, corner) ?: continue
            // **No more of the light than the source itself could have cast here**: a torch beside a crystal
            // lights the corner too, and taking the tint by all of it ended the hue on a hard edge at its reach.
            val lit = minOf(litByAnything, ownLightOf(reaching))
            val before = quad.colourAt(corner)
            val tinted = ARGB.multiply(before, softened(ARGB.opaque(reaching), shareOfTheTint(lit)))
            val inTheSun = skyLightingNow(LightCoordsUtil.smoothSky(light))
            quad.tint(corner, ARGB.srgbLerp(inTheSun, tinted, asBrightAs(before, tinted)))
        }
    }

    /** The light the source behind [reaching] could cast by itself, in [LightCoordsUtil.smoothBlock]'s units. */
    private fun ownLightOf(reaching: Int): Int = (ARGB.alpha(reaching) / FULL_CHANNEL.toFloat() * FULLY_LIT).toInt()

    /**
     * How brightly the sky lights a corner right now, from none to as bright as the lightmap goes — the
     * lightmap's own `sky_brightness`, from `lightmap.fsh`, against the sky as `TintedLights` last read it.
     */
    private fun skyLightingNow(sky: Int): Float {
        val level = (sky / FULLY_LIT).coerceIn(NONE, ALL_OF_IT)
        val brightness = level / (BRIGHTNESS_CURVE - (BRIGHTNESS_CURVE - ALL_OF_IT) * level)
        return (brightness * TintedLights.skyFactor).coerceIn(NONE, ALL_OF_IT)
    }

    /**
     * [tinted] scaled back up to [before]'s brightness, as near as it can go.
     *
     * **A tint is a multiply, and under the sun that is all it can be.** The lightmap is already at full
     * white there and nothing draws brighter, so a crystal's light adds nothing and its tint only took the
     * other channels away — a dark stain. Scaling back up recovers the brightness wherever the channels have
     * room, which a shaded face has: vanilla draws it darker than white. Where a channel would pass full it
     * stops, and what is left over shows as a paler tint rather than a darker one.
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
     * How much of the tint a corner takes, from the block light that actually reached it.
     *
     * **Not linear, and the curve is the difference between a room with coloured light in it and a stain
     * on one wall.** A crystal is light fifteen and block light falls a level a block, so a straight ramp
     * puts half the tint seven blocks out — which is most of the lit floor, and it is exactly where two
     * crystals meet and their hues mix. Rooting the share lifts that middle without moving either end:
     * still the whole tint at the source, still nothing in the dark.
     *
     * **This is the dial.** Squaring it back would restore the straight ramp.
     */
    private fun shareOfTheTint(lit: Int): Float = sqrt((lit.toFloat() / FULLY_LIT).coerceIn(NONE, ALL_OF_IT))

    /**
     * Says how bright the brightest corner painted so far was, each time that climbs.
     *
     * **The one number the two renderers can disagree about**, and the reason to have it is that they
     * hand over different objects and everything after that is shared code. If a loader's tint looks
     * weaker, either its light coordinates are lower — which this says outright — or the impression is
     * the light in the room rather than the tint. Approach a source and it converges in a few lines and
     * then goes quiet; it starts over when nothing is casting anywhere loaded.
     *
     * Two workers can climb past each other and say so twice, which costs a duplicate line and nothing
     * else — a lock here would be paid by every corner of every quad to tidy a log.
     */
    private fun noteTheBrightest(lit: Int) {
        brightestSeen = lit
        Constants.LOG.info(
            "Coloured light: brightest corner so far {} of {}, taking {}% of its tint",
            lit,
            FULLY_LIT.toInt(),
            Math.round(shareOfTheTint(lit) * PERCENT),
        )
    }

    @Volatile
    private var brightestSeen = 0

    fun forgetTheBrightest() {
        brightestSeen = 0
    }

    /**
     * The tint at one **corner of the quad** rather than at the block it belongs to.
     *
     * **This is what stops a mix seaming.** Where one source is in reach the hue is the same everywhere and
     * only the per-corner strength varies, so a block-wide hue is invisible; where two are, the hue is
     * genuinely different from place to place and reading it once per block steps it at every boundary.
     * Corners are **shared between neighbouring blocks**, so asking there makes the hue continuous across a
     * face by construction — the same reason smooth lighting samples at corners rather than at centres.
     *
     * Rounded to the block corner it sits on, which is both what makes neighbours agree exactly and what
     * gives [remembered] anything to hit: a vertex is shared by up to eight blocks and their quads.
     */
    private fun tintAtVertex(world: Level, pos: BlockPos, quad: LitQuad, corner: Int): Int? = tintAt(
        world,
        Math.round(pos.x + quad.cornerX(corner)),
        Math.round(pos.y + quad.cornerY(corner)),
        Math.round(pos.z + quad.cornerZ(corner)),
    )

    /**
     * The tint at one position, remembered.
     *
     * **The remembered answer carries the index's version with it**, because a mesher worker outlives the
     * thing it is drawing: `TintedLights.noticed` changes the index and then asks for the very sections
     * this cache was filled from to be rebuilt, on these same threads. Without the version the first
     * rebuild after placing or breaking a crystal repaints it in the colour that stood there before.
     */
    private fun tintAt(world: Level, x: Int, y: Int, z: Int): Int? {
        val cache = remembered.get()
        val version = TintedLights.version
        val key = BlockPos.asLong(x, y, z)
        val slot = (key * SPREAD).toInt() and SLOT_MASK
        if (cache.keys[slot] == key && cache.versions[slot] == version) {
            return cache.tints[slot].takeIf { it != NOTHING_THERE }
        }
        val found = TintedLights.reaching(world, BlockPos(x, y, z))
        cache.keys[slot] = key
        cache.versions[slot] = version
        cache.tints[slot] = found ?: NOTHING_THERE
        return found
    }

    /**
     * A vertex is asked for by every quad that meets at it, so the same answer is wanted a dozen times over
     * in a row — thread-confined, since a mesher worker owns its section and nothing else reads this.
     */
    private class VertexCache {
        val keys = LongArray(SLOTS) { Long.MIN_VALUE }
        val tints = IntArray(SLOTS)
        val versions = IntArray(SLOTS) { NEVER_FILLED }
    }

    private val remembered = ThreadLocal.withInitial { VertexCache() }

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

    private const val SLOTS = 128
    private const val SLOT_MASK = SLOTS - 1

    /** An odd multiplier, so neighbouring vertices land in different slots rather than colliding in a row. */
    private const val SPREAD = 0x9E3779B1L

    /** Remembered "nothing reaches here", which is not a colour and must not be read as one. */
    private const val NOTHING_THERE = Int.MIN_VALUE

    /** A version no index will ever report, so an untouched slot never reads as a hit. */
    private const val NEVER_FILLED = -1

    private const val PERCENT = 100

    /**
     * The lightmap coordinate at which a surface takes the tint fully — **vanilla's brightest**, in the
     * 0..240 units [LightCoordsUtil.smoothBlock] answers in.
     *
     * It was eleven *light levels*, which is below the twelve a powered crystal emits: everything the
     * crystal actually lit was therefore at or past the threshold, took the tint at full strength, and the
     * grade this whole per-corner path exists to draw was squeezed into the dim fringe beyond it. Setting
     * it at the top of the range spends the grade over the lit region instead, which is where it is seen.
     */
    private const val FULLY_LIT = 240.0f

    private const val UNLIT = 0

    /** `lightmap.fsh`'s `get_brightness` is `level / (4 - 3 * level)`. */
    private const val BRIGHTNESS_CURVE = 4.0f

    private const val FULL_CHANNEL = 255
    private const val RED_WEIGHT = 0.2126f
    private const val GREEN_WEIGHT = 0.7152f
    private const val BLUE_WEIGHT = 0.0722f
    private const val NONE = 0.0f
    private const val ALL_OF_IT = 1.0f
    private const val WHITE = 255
}
