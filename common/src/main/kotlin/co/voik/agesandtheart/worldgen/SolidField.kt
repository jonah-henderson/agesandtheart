package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField

/**
 * Rock from the floor of the world to its ceiling — **the one landform with no surface at all**, and so
 * the only one that shuts the world overhead by being what it is rather than by wearing a lid.
 *
 * **What is hollowed out of it is the underground's business**, which is the whole of why the shape is one
 * node. `noise_caves` gives vanilla's own cave stack over the entire build height, `chambered` gives
 * lakes and halls in it, and `none` gives a world with no space in it at all. Baking caves in here as
 * well would run two cave systems over one another and pay for both.
 */
object SolidField {

    fun world(): TerrainField = Slab(lowY = WORLD_FLOOR, highY = WORLD_CEILING)

    private const val WORLD_FLOOR = -64

    /**
     * The topmost block the band admits, so the rock genuinely reaches the ceiling rather than stopping
     * under a strip of air nobody can get to.
     */
    const val WORLD_CEILING = 319

    /**
     * How high an underground may reach here — clear of the bedrock roof, and there is nothing else above
     * it to clear. Far higher than any other landform's, because no surface can be opened into.
     */
    const val UNDERGROUND_CEILING = 288

    /**
     * Where standing water settles. Vanilla's own, which is where every shape that has not said otherwise
     * puts it — here it is a **water table** rather than a sea, since there is no open ground for a sea to
     * stand on, so what it decides is which of the caves run wet.
     */
    const val WATERLINE = 63
}
