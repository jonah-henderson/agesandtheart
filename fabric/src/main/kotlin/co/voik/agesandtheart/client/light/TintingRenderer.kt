package co.voik.agesandtheart.client.light

import net.fabricmc.fabric.api.client.renderer.v1.Renderer
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableMesh
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadTransform
import net.fabricmc.fabric.api.client.renderer.v1.render.AltModelBlockRenderer
import net.minecraft.client.color.block.BlockColors
import net.minecraft.client.renderer.block.BlockAndTintGetter
import net.minecraft.client.renderer.block.dispatch.BlockStateModel
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Consumer

/**
 * Whatever renderer Fabric is using, wrapped so `TintedLightPainter` gets a look at the blocks it draws.
 *
 * **The whole Fabric half of coloured light is this file and one injector.** Vanilla's mesher is reached
 * through `ModelBlockRenderer.putQuadWithTint`, but Fabric API's own renderer — Indigo, which ships inside
 * Fabric API and is therefore present in every Fabric installation — redirects chunk building away from
 * that method entirely. What it offers instead is a quad transform: a hook every emitted quad passes
 * through, carrying the corner colours and light coords the painter wants.
 *
 * **A transform pushed early runs late, which is the one fact that makes this work.** The emitter walks its
 * stack from the top down, so the transform Indigo pushes for each block runs *first* and ours, pushed
 * before it, runs after — after tint and shade, on the finished lighting, which is exactly where a tint has
 * to land. It is also why a face Indigo culls never reaches us: a transform that returns false ends the
 * walk.
 *
 * **Why a wrapper and not a registration.** `Renderer.register` takes exactly one plug-in for the whole
 * game and Indigo takes it; the only way to stop Indigo claiming it is the `contains_renderer` flag in a
 * mod's manifest, which also disables Indigo's own Mixins and so breaks the very renderer we would be
 * delegating to. Decorating what `Renderer.get()` answers with is the same shape without the exclusivity —
 * and it names no renderer, so anything that hands out block renderers this way is covered.
 */
class TintingRenderer private constructor(private val inner: Renderer) : Renderer {

    override fun quadEmitter(consumer: Consumer<in MutableQuadView>): QuadEmitter = inner.quadEmitter(consumer)

    override fun mutableMesh(): MutableMesh = inner.mutableMesh()

    override fun altModelBlockRenderer(
        ambientOcclusion: Boolean,
        cull: Boolean,
        colours: BlockColors,
    ): AltModelBlockRenderer = TintingBlockRenderer(inner.altModelBlockRenderer(ambientOcclusion, cull, colours))

    companion object {

        /** [renderer] wrapped, or the wrapper it already has. */
        fun around(renderer: Renderer): Renderer =
            if (renderer is TintingRenderer) renderer else wrappers.computeIfAbsent(renderer, ::TintingRenderer)

        /**
         * One wrapper per renderer, because `Renderer.get()` is asked twice for every section compiled and
         * a fresh wrapper each time would be litter. In practice this holds a single entry for the life of
         * the game: what it answers with is set once and never replaced.
         */
        private val wrappers = ConcurrentHashMap<Renderer, TintingRenderer>()
    }
}

/**
 * One block being tesselated, with the painter's transform on the emitter for the duration.
 *
 * A fresh one of these is made per section compile, so it is owned by the mesher thread that made it and
 * the block it is aimed at is state that thread alone reads.
 */
private class TintingBlockRenderer(private val inner: AltModelBlockRenderer) : AltModelBlockRenderer, QuadTransform {

    private var at: BlockPos = BlockPos.ZERO
    private var originX = 0.0f
    private var originY = 0.0f
    private var originZ = 0.0f
    private val corners = FabricQuad()

    override fun tesselateBlock(
        emitter: QuadEmitter,
        x: Float,
        y: Float,
        z: Float,
        level: BlockAndTintGetter,
        pos: BlockPos,
        state: BlockState,
        model: BlockStateModel,
        seed: Long,
    ) {
        if (TintedLightPainter.isIdle()) {
            inner.tesselateBlock(emitter, x, y, z, level, pos, state, model, seed)
            return
        }
        // Where this block's own corner sits in the emitter's coordinates, so a vertex can be read back as
        // a position within the block. Quads arrive translated by the section offset *and* by whatever
        // wander the block state asks for, and both are already in these three numbers.
        val wander = state.getOffset(pos)
        at = pos
        originX = x + wander.x.toFloat()
        originY = y + wander.y.toFloat()
        originZ = z + wander.z.toFloat()
        emitter.pushTransform(this)
        try {
            inner.tesselateBlock(emitter, x, y, z, level, pos, state, model, seed)
        } finally {
            emitter.popTransform()
        }
    }

    override fun transform(quad: MutableQuadView): Boolean {
        TintedLightPainter.paint(at, corners.over(quad, originX, originY, originZ))
        return true
    }
}

/**
 * A quad Fabric is emitting, seen as a [LitQuad].
 *
 * Reused rather than allocated per quad: this runs for every quad of every block in every section, and its
 * subject is replaced immediately before every use.
 */
private class FabricQuad : LitQuad {

    private lateinit var quad: MutableQuadView
    private var originX = 0.0f
    private var originY = 0.0f
    private var originZ = 0.0f

    fun over(quad: MutableQuadView, originX: Float, originY: Float, originZ: Float): LitQuad = also {
        this.quad = quad
        this.originX = originX
        this.originY = originY
        this.originZ = originZ
    }

    override fun lightAt(corner: Int): Int = quad.lightmap(corner)

    override fun colourAt(corner: Int): Int = quad.color(corner)

    override fun tint(corner: Int, colour: Int) {
        quad.color(corner, colour)
    }

    override fun cornerX(corner: Int): Float = quad.x(corner) - originX

    override fun cornerY(corner: Int): Float = quad.y(corner) - originY

    override fun cornerZ(corner: Int): Float = quad.z(corner) - originZ
}
