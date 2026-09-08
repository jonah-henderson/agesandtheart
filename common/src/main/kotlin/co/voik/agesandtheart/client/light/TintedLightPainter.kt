package co.voik.agesandtheart.client.light

import com.mojang.blaze3d.vertex.QuadInstance
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.model.geometry.BakedQuad
import net.minecraft.core.BlockPos
import net.minecraft.util.ARGB
import net.minecraft.util.LightCoordsUtil
import net.minecraft.world.level.Level

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
     * world, so the common case has to cost a field read and a return.
     *
     * **Two of them, and the second is the one that matters.** `castsAnything` asks whether any block
     * *kind* is registered, and the answer is always yes — the crystals register at startup and never
     * unregister, so on its own it never fires and every quad in the game went on to a nine-chunk scan.
     * `anythingIsPlaced` asks whether any such block actually stands in a world, which is the question
     * that is false almost always.
     */
    fun paint(pos: BlockPos, quad: BakedQuad, instance: QuadInstance) {
        if (!TintedLights.castsAnything()) return
        if (!TintedLights.anythingIsPlaced()) return
        // **The client's own level rather than the one being meshed**, because a `RenderSectionRegion`
        // keeps its level private and reaching it would cost a widener for a lookup key. The one place the
        // two differ is the linking panel's preview level, where the worst case is a previewed crystal not
        // colouring its wall. Worth revisiting if the panel ever wants this.
        val world = Minecraft.getInstance().level ?: return
        // One reading at the block to reject the common case, **through the same cache the corners use**:
        // every quad of a block asks it, so it is answered once and read from memory thereafter.
        tintAt(world, pos.x, pos.y, pos.z) ?: return
        for (corner in 0..<CORNERS) {
            val lit = LightCoordsUtil.smoothBlock(instance.getLightCoords(corner))
            if (lit <= UNLIT) continue
            val tint = tintAtVertex(world, pos, quad, corner) ?: continue
            val share = (lit.toFloat() / FULLY_LIT).coerceIn(NONE, ALL_OF_IT)
            instance.setColor(corner, ARGB.multiply(instance.getColor(corner), softened(tint, share)))
        }
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
    private fun tintAtVertex(world: Level, pos: BlockPos, quad: BakedQuad, corner: Int): Int? {
        val local = quad.position(corner)
        return tintAt(world, Math.round(pos.x + local.x()), Math.round(pos.y + local.y()), Math.round(pos.z + local.z()))
    }

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
    private const val NONE = 0.0f
    private const val ALL_OF_IT = 1.0f
    private const val WHITE = 255
}
