package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.TerrainField

/**
 * Solid ground to one level, everywhere, for ever — Minecraft's own superflat said as a field.
 *
 * **The one landform here with no seed in it**, so two Ages wearing it stand on the same ground and differ
 * only in what is laid over it. That is what it is for: with the relief taken out, the biomes, the rock,
 * the sky and whatever is built on the plain are the only things left to look at.
 */
object FlatlandsField {

    fun world(): TerrainField = Slab(lowY = WORLD_FLOOR, highY = SURFACE_Y)

    private const val WORLD_FLOOR = -64

    /**
     * Vanilla's own ground level, so that what a feature or a structure assumes about where the world's
     * surface is holds here — a plain that reads as ordinary country with the shape taken away, rather
     * than as a plateau or a seabed.
     */
    const val SURFACE_Y = 64
}
