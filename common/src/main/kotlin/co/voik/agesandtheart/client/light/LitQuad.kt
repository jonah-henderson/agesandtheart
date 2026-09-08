package co.voik.agesandtheart.client.light

/**
 * One quad, at the moment its lighting has been worked out and before it is written to a buffer.
 *
 * **The whole of what the two renderers have to agree on**, and it is six accessors. `TintedLightPainter`
 * needs a corner's light coordinate, its colour, somewhere to put a new colour, and where the corner sits
 * within its block; nothing else about a quad matters to a tint. Vanilla's chunk mesher offers those on
 * `QuadInstance` plus `BakedQuad`, and Fabric's on a single `MutableQuadView` — so the painter is written
 * against this and neither renderer's types reach it.
 *
 * [cornerX], [cornerY] and [cornerZ] are **relative to the block's own corner**, the space
 * `BakedQuad.position` already answers in, so a caller working in section coordinates subtracts the block
 * origin before it gets here.
 */
interface LitQuad {

    /** The packed light coordinate at [corner], as `LightCoordsUtil` reads it. */
    fun lightAt(corner: Int): Int

    fun colourAt(corner: Int): Int

    fun tint(corner: Int, colour: Int)

    fun cornerX(corner: Int): Float

    fun cornerY(corner: Int): Float

    fun cornerZ(corner: Int): Float
}
