package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Cone
import co.voik.agesandtheart.worldgen.field.Cylinder
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Grid
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.Variation
import net.minecraft.core.Direction
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * An **underground of great halls**: storey upon storey of open floor under the world, everything taken
 * away but the fluted piers holding the ceiling up and the slabs of rock between one storey and the next.
 *
 * Nothing here is a new kind of node. The halls are a stack of [Slab]s, the piers are an [Instanced] grid
 * of [Cylinder]s with their flutes cut out of them, and the whole underground is the one taken out of the
 * other — so what gets subtracted from an Age's rock is `Union(storeys) - piers`. The rock between storeys
 * survives because it was never inside a storey to begin with; it does not have to be put back.
 *
 * **The piers are one template spanning every storey**, not one per storey. A grid re-seeded per storey
 * would stand its piers in different places on each, and a hall whose ceiling is held up by nothing that
 * reaches the floor below it reads as a mistake even when it stands.
 */
object GreatHalls {

    /**
     * The volume to take out of an Age's rock, between [floorY] and [roofY] inclusive.
     *
     * Empty when the band cannot hold a single storey, which is what a landform with no room under it
     * gets — [storeysBetween] is the one place that decides, and it is allowed to decide none.
     */
    fun voidBetween(floorY: Int, roofY: Int, salt: Long): TerrainField {
        val storeys = storeysBetween(floorY, roofY)
        if (storeys.isEmpty()) return Union(emptyList())
        val open = storeys.map { storey -> Slab(lowY = storey.first, highY = storey.last) }
        return Subtract(Union(open), piers(storeys, salt))
    }

    /**
     * Which bands of the world are open hall, lowest first — as many whole storeys as the band will take.
     *
     * Rock first and rock last: a storey never begins at [floorY] itself, so there is always a floor to
     * stand on and always a roof over the top one. A partial storey is not laid at all, since a hall two
     * blocks tall is a crawlspace rather than a hall.
     */
    fun storeysBetween(floorY: Int, roofY: Int): List<IntRange> {
        val band = roofY - floorY + 1
        val perStorey = HALL_HEIGHT + SLAB_THICKNESS
        // One slab under the lowest hall as well as one over each, hence the extra before dividing.
        val count = (band - SLAB_THICKNESS) / perStorey
        if (count <= 0) return emptyList()
        return (0..<count).map { storey ->
            val lowest = floorY + SLAB_THICKNESS + storey * perStorey
            lowest..lowest + HALL_HEIGHT - 1
        }
    }

    /**
     * The piers of every storey as one instanced template, authored about the local origin — see
     * [Variation] on why a template is written in absolute Y.
     */
    private fun piers(storeys: List<IntRange>, salt: Long): TerrainField {
        val pier = Union(storeys.map(::pierIn))
        return Instanced(
            templates = listOf(pier),
            placement = Grid(spacing = SPACING, jitter = JITTER, density = Density.uniform()),
            variation = Variation.NONE,
            seed = PIER_SEED xor salt,
        )
    }

    /**
     * One storey's pier: a round shaft with vertical grooves cut down it, standing on a flared base and
     * spreading into a capital where it meets the ceiling.
     *
     * The flutes are **cut** rather than added, which is what makes the shaft read as one worked column
     * instead of a bundle of small ones. Base and capital are unfluted on purpose — they are the mass the
     * grooves stop against, and a groove running out into open air looks unfinished.
     */
    private fun pierIn(storey: IntRange): TerrainField {
        val floorY = storey.first
        val ceilingY = storey.last
        val middleY = (floorY + ceilingY) / 2
        val halfHeight = (ceilingY - floorY) / 2.0

        val shaft = Cylinder(
            axis = Direction.Axis.Y,
            centerX = 0, centerY = middleY, centerZ = 0,
            radius = SHAFT_RADIUS,
            halfLength = halfHeight,
        )
        val flutes = Union(
            (0..<FLUTE_COUNT).map { flute ->
                val around = FULL_TURN * flute / FLUTE_COUNT
                Cylinder(
                    axis = Direction.Axis.Y,
                    centerX = (cos(around) * SHAFT_RADIUS).roundToInt(),
                    centerY = middleY,
                    centerZ = (sin(around) * SHAFT_RADIUS).roundToInt(),
                    radius = FLUTE_RADIUS,
                    halfLength = halfHeight,
                )
            },
        )
        // Widest where it meets the rock and tapering back to the shaft, both ways up.
        val base = Cone(baseX = 0, baseZ = 0, baseRadius = FLARE_RADIUS, baseY = floorY, tipY = floorY + FLARE_HEIGHT)
        val capital =
            Cone(baseX = 0, baseZ = 0, baseRadius = FLARE_RADIUS, baseY = ceilingY, tipY = ceilingY - FLARE_HEIGHT)
        return Union(listOf(Subtract(shaft, flutes), base, capital))
    }

    /**
     * How tall one hall stands, floor to ceiling. Read with [SLAB_THICKNESS]: together they decide how many
     * storeys a given depth of rock will take, and a landform only ever gets whole ones.
     */
    const val HALL_HEIGHT = 56

    /** The rock between one storey and the next, and over the top one. */
    const val SLAB_THICKNESS = 8

    // 26 across on a 72-block grid, so a bay is about a pier and a half of open floor either side.
    private const val SHAFT_RADIUS = 13.0
    private const val FLUTE_COUNT = 12
    private const val FLUTE_RADIUS = 3.5
    private const val FLARE_RADIUS = 17.0
    private const val FLARE_HEIGHT = 9

    private const val SPACING = 72.0

    // Small against the spacing: the ranks should still read as ranks, only not as a ruled grid.
    private const val JITTER = 5.0

    private const val FULL_TURN = 2.0 * PI
    private const val PIER_SEED = 0x4D_0217_A5L
}
