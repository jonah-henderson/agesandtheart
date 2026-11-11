package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.feature.Formation
import co.voik.agesandtheart.worldgen.field.Density
import co.voik.agesandtheart.worldgen.field.Grid
import co.voik.agesandtheart.worldgen.field.Instanced
import co.voik.agesandtheart.worldgen.field.Landmarks
import co.voik.agesandtheart.worldgen.field.Lift
import co.voik.agesandtheart.worldgen.field.Placement
import co.voik.agesandtheart.worldgen.field.Pyramid
import co.voik.agesandtheart.worldgen.field.Radial
import co.voik.agesandtheart.worldgen.field.Scatter
import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.Subtract
import co.voik.agesandtheart.worldgen.field.TerrainField
import co.voik.agesandtheart.worldgen.field.Union
import co.voik.agesandtheart.worldgen.field.Variation
import net.minecraft.core.registries.Registries
import net.minecraft.core.RegistryAccess
import net.minecraft.resources.Identifier

/**
 * The formation a placed feature lays, by the feature's id, for a landform built of its shapes; null where the id
 * names no formation, and for everything offline, where there is no registry to ask.
 */
fun interface FormationLookup {
    fun formation(id: Identifier): Formation?

    companion object {
        val NONE = FormationLookup { null }

        fun of(registries: RegistryAccess) = FormationLookup { id ->
            registries.lookupOrThrow(Registries.PLACED_FEATURE).getValue(id)?.feature()?.value() as? Formation
        }
    }
}

/**
 * Instancing presets: a handful of differently-sized shapes (heights 8/16/24/32) strewn on a flat plain — pyramids
 * unless another formation's shapes are asked for, and cut into the plain where the formation is dug rather than
 * stood. Three worlds exercise the placement seam — a density-gradient [Grid] (packed at the origin, thinning
 * outward), concentric [Radial] rings, and a gradient grid whose copies are turned and resized by a [Variation].
 * Deliberate, regular geography; any detailing is a later pass, not the instancer's.
 */
object GeometricField {

    private fun gradientGrid() = Grid(
        spacing = GRID_SPACING,
        jitter = GRID_JITTER,
        density = Density(
            atOrigin = DENSITY_AT_ORIGIN,
            atEdge = DENSITY_AT_EDGE,
            falloffRadius = DENSITY_FALLOFF_RADIUS,
        ),
    )

    private fun rings() = Radial(
        ringSpacing = RING_SPACING,
        arcSpacing = ARC_SPACING,
        jitter = RING_JITTER,
        density = Density.uniform(),
    )

    /**
     * The shapes of [formation], or pyramids where it is null, arranged as asked. Where [landmarks] are drawn a great
     * one stands, in whatever arrangement the rest keep.
     */
    fun world(
        arrangement: String,
        formation: Formation? = null,
        salt: Long = 0L,
        scale: Double = SizeScale.ORDINARY,
        landmarks: Landmarks = Landmarks.NONE,
    ): TerrainField {
        val ofHeight = if (formation == null) ::pyramidsOfHeight else shapesOfHeight(formation)
        val templates = HEIGHTS.flatMap(ofHeight)
        val shapes = when (arrangement) {
            "rings" -> scattered(templates, rings(), Variation.NONE, salt, scale)
            "varied" -> scattered(templates, gradientGrid(), variedPoses(), salt, scale)
            else -> scattered(templates, gradientGrid(), Variation.NONE, salt, scale)
        }
        val standing = if (landmarks.anyDrawn) Union(listOf(shapes, greatOnes(ofHeight, landmarks, scale))) else shapes
        val isDug = formation?.sunk == true
        return if (isDug) Subtract(plain(), standing) else Union(listOf(plain(), standing))
    }

    /** A pyramid as wide as it is tall, standing on the plain. */
    private fun pyramidsOfHeight(height: Int): List<TerrainField> =
        listOf(Pyramid(centerX = 0, centerZ = 0, baseY = GROUND_TOP + 1, height = height, baseHalfWidth = height))

    /**
     * Each of [formation]'s shapes at about [height], standing on the plain or dug a block into it as the formation
     * is. A formation is authored [AUTHORED_HEIGHT] tall at its own ordinary size, which its [Formation.largest] says.
     */
    private fun shapesOfHeight(formation: Formation): (Int) -> List<TerrainField> {
        val ordinarySize = formation.largest / SizeScale.COLOSSAL
        val standsAt = if (formation.sunk) GROUND_TOP else GROUND_TOP + 1
        val standing = Lift(datumY = 0, toY = standsAt)
        return { height ->
            formation.shapes.map { shape -> standing.over(shape.resized(ordinarySize * height / AUTHORED_HEIGHT, 0), keepsFloor = false) }
        }
    }

    /** One great shape where each landmark is drawn, as tall as the world leaves room for at its [scale]. */
    private fun greatOnes(ofHeight: (Int) -> List<TerrainField>, landmarks: Landmarks, scale: Double): TerrainField {
        val height = minOf(GREAT_HEIGHT, ((VerticalWindow.HIGHEST_BLOCK_Y - GROUND_TOP - 1) / scale).toInt())
        val great = ofHeight(height).first()
        val drawn = Density(atOrigin = landmarks.chance, atEdge = landmarks.chance, falloffRadius = 1.0)
        val layout = Scatter(cellSize = landmarks.spacing / scale, leastPerCell = 1, mostPerCell = 1, density = drawn)
        return Instanced(listOf(great), layout, Variation.NONE, seed = landmarks.seed).resized(scale, GROUND_TOP + 1)
    }

    private fun variedPoses() = Variation(
        yawSteps = YAW_STEPS,
        minScale = MIN_SCALE,
        maxScale = MAX_SCALE,
        scaleSteps = SCALE_STEPS,
        pivotY = GROUND_TOP + 1,
    )

    /**
     * [scale] is [SizeScale]'s factor, and the whole scatter takes it about the plain: taller shapes, further apart,
     * thinning out over a proportionally wider country, so every size reads alike.
     */
    private fun scattered(
        templates: List<TerrainField>,
        placement: Placement,
        variation: Variation,
        salt: Long,
        scale: Double,
    ): TerrainField = Instanced(templates, placement, variation, seed = SCATTER_SEED xor salt).resized(scale, GROUND_TOP + 1)

    private val HEIGHTS = listOf(8, 16, 24, 32)

    /** The plain the shapes stand on, without them: how a territory beside them reads their ground. */
    fun plain(): TerrainField = Slab(lowY = VerticalWindow.MIN_Y, highY = GROUND_TOP)

    /** Two and a half times the tallest of the rest, before the size. */
    private const val GREAT_HEIGHT = 80

    /** How tall a formation stands at its ordinary size, as written: twenty-five, or a hundred `colossal`. */
    private const val AUTHORED_HEIGHT = 25.0

    private const val GROUND_TOP = 63
    private const val SCATTER_SEED = 0x5EED_C0DEL

    // Wide enough that the largest pyramid still stands clear of its neighbours away from the origin,
    // with enough jitter to break the lattice without disguising that it is one.
    private const val GRID_SPACING = 40.0
    private const val GRID_JITTER = 10.0

    // Packed at the origin and thinning to near-empty by the falloff radius — the gradient is the
    // whole point of this world, so it runs the full range rather than a subtle one.
    private const val DENSITY_AT_ORIGIN = 1.0
    private const val DENSITY_AT_EDGE = 0.1
    private const val DENSITY_FALLOFF_RADIUS = 420.0

    // Rings far enough apart to walk between, and spaced along their arc so a ring reads as a row of
    // separate pyramids rather than a wall.
    private const val RING_SPACING = 64.0
    private const val ARC_SPACING = 56.0
    private const val RING_JITTER = 8.0

    // Enough orientations to read as freely turned, but a fixed set so the yaw table stays tiny.
    private const val YAW_STEPS = 16
    // Kept modest: the gradient grid already overlaps near the origin, and a large upper bound there
    // merges neighbours into one mass rather than reading as distinct, differently-sized pyramids.
    private const val MIN_SCALE = 0.6
    private const val MAX_SCALE = 1.3
    private const val SCALE_STEPS = 4
}
