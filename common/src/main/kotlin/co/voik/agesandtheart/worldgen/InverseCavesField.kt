package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Caved
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Ellipsoid
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.Landmarks
import co.voik.agesandtheart.worldgen.field.Scatter
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Variation

/**
 * Minecraft's noise caves with the rock and the air exchanged: solid where a cave would have been cut, open
 * everywhere the rock would have stood.
 *
 * **The cast of the caves rather than the caves themselves.** Spaghetti tunnels come out as long sinuous
 * ridges, cheese chambers as broad plateaux hanging in nothing, and the pillars vanilla leaves standing in
 * a cavern come out as round shafts punched clean through them. Roughly a twentieth of the world is solid
 * and two columns in three hold anything at all, so the rest is a drop to the bedrock — which is the point,
 * and why nothing here builds a floor.
 *
 * It needs no node of its own: [Caved] already knows how to take the caves out of a shape, so the cast is
 * that shape with the caved version taken back out of it. What the composition does have to say is
 * [ENTRANCE_REACH] — vanilla's rule that only entrances cut through shallow rock, which against a slab
 * would leave a lid of untouched ceiling rather than the hillside openings it is for.
 */
object InverseCavesField {

    /**
     * Where a sea stands when a book names one: shallow, sixteen blocks off the bottom of the world, so it
     * drowns only the lowest of the tangle and leaves the rest hanging over it.
     */
    const val NAMED_SEA_LEVEL = VerticalWindow.MIN_Y + 16

    /**
     * [scale] is [SizeScale]'s factor, and it is the caves' own: the noise they are cast from is read that
     * much coarser, so every ridge, plateau and shaft grows while the cast still fills the world top to
     * bottom. Stretching the finished shape instead would squash or lose half of it against the ceiling.
     */
    fun world(salt: Long, scale: Double = SizeScale.ORDINARY, landmarks: Landmarks = Landmarks.NONE): TerrainField {
        // The whole band: the cave noises keep producing all the way up, nine columns in ten past y=280.
        val everything = Slab(lowY = VerticalWindow.MIN_Y, highY = VerticalWindow.TOP_Y)
        val hollowed = Caved(
            base = everything,
            seed = CAVE_SEED xor salt,
            fromY = VerticalWindow.MIN_Y,
            toY = VerticalWindow.TOP_Y,
            entranceReach = ENTRANCE_REACH,
            featureScale = scale,
        )
        val cast = Subtract(everything, hollowed)
        if (!landmarks.anyDrawn) return cast
        return Subtract(cast, hollows(landmarks))
    }

    /**
     * A vast open hollow in the tangle where each landmark is drawn — the landmark of a landform with no top to raise,
     * as wide as a landmark reaches and a little over half the world's height.
     */
    private fun hollows(landmarks: Landmarks): TerrainField {
        val hollow = Ellipsoid(
            centerX = 0,
            centerY = HOLLOW_Y,
            centerZ = 0,
            radiusXZ = landmarks.radius,
            radiusY = HOLLOW_HALF_HEIGHT,
        )
        val drawn = Density(atOrigin = landmarks.chance, atEdge = landmarks.chance, falloffRadius = 1.0)
        val layout = Scatter(cellSize = landmarks.spacing, leastPerCell = 1, mostPerCell = 1, density = drawn)
        return Instanced(listOf(hollow), layout, Variation.NONE, seed = landmarks.seed)
    }

    /**
     * No shallow-rock rule at all. It exists to keep chambers from opening the ground out from under a
     * forest, and there is no ground here to open — left at its default the top of the slab counts as
     * shallow, and the cast grows a ceiling it should not have.
     */
    private const val ENTRANCE_REACH = 0

    private const val CAVE_SEED = 0x1_C0_57L

    /** The middle of a hollow, and how far it reaches up and down from it — clear of the floor and of the sea. */
    private const val HOLLOW_Y = 128
    private const val HOLLOW_HALF_HEIGHT = 96.0
}
