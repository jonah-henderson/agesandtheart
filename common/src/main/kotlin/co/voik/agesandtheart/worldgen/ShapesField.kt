package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.SeaFill
import co.voik.agesandtheart.worldgen.field.Box
import co.voik.agesandtheart.worldgen.field.Cylinder
import co.voik.agesandtheart.worldgen.field.HalfSpace
import co.voik.agesandtheart.worldgen.field.Intersect
import co.voik.agesandtheart.worldgen.field.NoiseHeightmap
import co.voik.agesandtheart.worldgen.field.Palette
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union
import net.minecraft.core.Direction
import net.minecraft.world.level.biome.BiomeSource

/**
 * A walkable sampler of the shape vocabulary: one composition per station, strung out along +X from
 * spawn. Its point is *composability* rather than scenery — each station past the first combines two or
 * more fields, so what is on show is whether the combinators behave when their pieces differ.
 */
object ShapesField {

    fun world(): TerrainField {
        val ground = Slab(lowY = FLOOR_Y, highY = GROUND_TOP)
        return Union(listOf(ground, tower(), monolith(), octahedron(), ramp(), mesa(), arch()))
    }

    fun generator(biomeSource: BiomeSource): AgeChunkGenerator =
        AgeChunkGenerator(biomeSource, world(), SeaFill.NONE, Palette.BARE_ROCK)

    /** Station 0 — a bare upright [Cylinder]. */
    private fun tower() = Cylinder(
        axis = Direction.Axis.Y,
        centerX = station(0), centerY = BASE_Y + 17, centerZ = 0,
        radius = 10.0, halfLength = 17.0,
    )

    /** Station 1 — a bare [Box]. */
    private fun monolith() = Box(
        minX = station(1) - 8, minY = BASE_Y, minZ = -14,
        maxX = station(1) + 8, maxY = BASE_Y + 40, maxZ = 14,
    )

    /**
     * Station 2 — a polyhedron with no polyhedron primitive behind it: eight [HalfSpace] planes
     * intersected. `|x| + |y| + |z| <= radius` about the centre is exactly "inside all eight", so each
     * sign combination contributes one plane.
     */
    private fun octahedron(): TerrainField {
        val centerX = station(2)
        val centerY = BASE_Y + 22
        val centerZ = 0
        val radius = 20.0
        val planes = SIGNS.flatMap { signX ->
            SIGNS.flatMap { signY ->
                SIGNS.map { signZ ->
                    HalfSpace(
                        normalX = -signX,
                        normalY = -signY,
                        normalZ = -signZ,
                        // Rearranged from `sign · (position − centre) <= radius` into `normal · position >= distance`.
                        distance = -radius - (signX * centerX + signY * centerY + signZ * centerZ),
                    )
                }
            }
        }
        return Intersect(planes)
    }

    /** Station 3 — a [Box] sliced by a tilted [HalfSpace]: the wedge, the simplest real composition. */
    private fun ramp(): TerrainField {
        val startX = station(3) - 15
        val block = Box(
            minX = startX, minY = BASE_Y, minZ = -12,
            maxX = startX + 30, maxY = BASE_Y + 26, maxZ = 12,
        )
        // Solid below the sloping face `y = BASE_Y + RISE·(x − startX)`.
        val slope = HalfSpace(normalX = RISE, normalY = -1.0, normalZ = 0.0, distance = RISE * startX - BASE_Y)
        return Intersect(listOf(block, slope))
    }

    /**
     * Station 4 — a [NoiseHeightmap] clipped to an upright [Cylinder]: an organic top with a cut edge.
     * The one station where the two children are genuinely different *kinds* of field, which is the
     * composability question worth answering.
     */
    private fun mesa(): TerrainField {
        val bumpyTop = NoiseHeightmap(
            seed = MESA_SEED,
            firstOctave = -5,
            amplitudes = listOf(1.0, 0.5),
            scaleX = 1.0,
            scaleZ = 1.0,
            baseY = BASE_Y + 26,
            relief = 8.0,
            flatY = FLOOR_Y,
        )
        val footprint = Cylinder(
            axis = Direction.Axis.Y,
            centerX = station(4), centerY = BASE_Y, centerZ = 0,
            radius = 22.0, halfLength = MESA_HALF_HEIGHT,
        )
        return Intersect(listOf(bumpyTop, footprint))
    }

    /**
     * Station 5 — a [Box] with a **lying** [Cylinder] taken out of it: an arched opening you can walk
     * through along the row. Centring the shaft on the ground leaves the top half as the arch.
     */
    private fun arch(): TerrainField {
        val block = Box(
            minX = station(5) - 18, minY = BASE_Y, minZ = -10,
            maxX = station(5) + 18, maxY = BASE_Y + 30, maxZ = 10,
        )
        val opening = Cylinder(
            axis = Direction.Axis.X,
            centerX = station(5), centerY = BASE_Y, centerZ = 0,
            radius = 9.0,
            // Longer than the block is deep, so the opening cuts clean through both faces.
            halfLength = 20.0,
        )
        return Subtract(block, opening)
    }

    /** Stations are strung out along +X so they can be walked in order, leaving the spawn itself clear. */
    private fun station(index: Int) = FIRST_STATION_X + index * STATION_SPACING

    private val SIGNS = listOf(-1.0, 1.0)
    private const val FIRST_STATION_X = 40
    private const val STATION_SPACING = 56
    private const val FLOOR_Y = -64
    private const val GROUND_TOP = 63
    private const val BASE_Y = GROUND_TOP + 1
    // Tall enough that the clipping cylinder never cuts the noise top — only its edge does the work.
    private const val MESA_HALF_HEIGHT = 128.0

    // Exactly 1 so the staircase is uniform. A plane is rasterised exactly, so a slope of p/q steps in a
    // repeating but *uneven* pattern unless p or q is 1 — 0.8 gave runs of 1 and 2 blocks.
    private const val RISE = 1.0
    private const val MESA_SEED = 0x5A1EL
}
