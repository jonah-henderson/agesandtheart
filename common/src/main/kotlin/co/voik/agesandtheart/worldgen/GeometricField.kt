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
import kotlin.math.pow

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
     * The shapes of [formations], pyramids for each that is null, arranged as asked: one formation as likely as the
     * next wherever a copy stands, those that are dug cut into the plain and the rest stood on it. Where [landmarks] are
     * drawn a great one of the first stands, in whatever arrangement the rest keep.
     */
    fun world(
        arrangement: String,
        formations: List<Formation?> = PYRAMIDS_ALONE,
        salt: Long = 0L,
        scale: Double = SizeScale.ORDINARY,
        landmarks: Landmarks = Landmarks.NONE,
        /** How far apart the shapes stand, twice as far at one and half at minus one; as they always did where null. */
        spacing: Double? = null,
    ): TerrainField {
        val kinds = formations.ifEmpty { PYRAMIDS_ALONE }
        val ofHeights = kinds.map { kind -> if (kind == null) ::pyramidsOfHeight else shapesOfHeight(kind) }
        val each = balanced(ofHeights.map { ofHeight -> HEIGHTS.flatMap(ofHeight) })
        val isDug = kinds.zip(each).flatMap { (kind, templates) -> templates.map { kind?.sunk == true } }
        val templates = each.flatten()
        // Laid twice over one layout, so a copy draws the same template in both and stands in one or is dug in the other.
        val standingTemplates = templates.mapIndexed { at, template -> if (isDug[at]) NOTHING else template }
        val dugTemplates = templates.mapIndexed { at, template -> if (isDug[at]) template else NOTHING }
        fun spaced(placement: Placement) = spacing?.let { placement.resized(SPACING_DOUBLING.pow(it)) } ?: placement
        fun laid(templates: List<TerrainField>) = when (arrangement) {
            "rings" -> scattered(templates, spaced(rings()), Variation.NONE, salt, scale)
            "varied" -> scattered(templates, spaced(gradientGrid()), variedPoses(), salt, scale)
            else -> scattered(templates, spaced(gradientGrid()), Variation.NONE, salt, scale)
        }
        val firstIsDug = kinds.first()?.sunk == true
        fun withTheGreat(shapes: TerrainField, isItsKind: Boolean): TerrainField =
            if (landmarks.anyDrawn && isItsKind) Union(listOf(shapes, greatOnes(ofHeights.first(), landmarks, scale))) else shapes
        val anyStand = isDug.any { !it }
        val anyDug = isDug.any { it }
        val standing = if (anyStand) withTheGreat(laid(standingTemplates), isItsKind = !firstIsDug) else null
        val dug = if (anyDug) withTheGreat(laid(dugTemplates), isItsKind = firstIsDug) else null
        val stoodOn = standing?.let { Union(listOf(plain(), it)) } ?: plain()
        return dug?.let { Subtract(stoodOn, it) } ?: stoodOn
    }

    /**
     * [lists] each repeated until all are as long, so drawing one template among them all draws each list alike: rings'
     * five leans as likely between them as a pyramid alone.
     */
    private fun balanced(lists: List<List<TerrainField>>): List<List<TerrainField>> {
        if (lists.size <= 1) return lists
        val longest = lists.fold(1) { common, list -> leastCommonMultiple(common, list.size) }
        return lists.map { list -> List(longest / list.size) { list }.flatten() }
    }

    private fun leastCommonMultiple(one: Int, other: Int): Int {
        tailrec fun greatestCommonDivisor(a: Int, b: Int): Int = if (b == 0) a else greatestCommonDivisor(b, a % b)
        return one / greatestCommonDivisor(one, other) * other
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

    /** The pyramids built in code, which is what a geometric landform asked for nothing else is built of. */
    private val PYRAMIDS_ALONE: List<Formation?> = listOf(null)

    /** A template with nothing in it, where a copy is laid on the other side of the plain. */
    private val NOTHING: TerrainField = Union(emptyList())

    /** The plain the shapes stand on, without them: how a territory beside them reads their ground. */
    fun plain(): TerrainField = Slab(lowY = VerticalWindow.MIN_Y, highY = GROUND_TOP)

    /** Two and a half times the tallest of the rest, before the size. */
    private const val GREAT_HEIGHT = 80

    /** The stretch a spacing of one gives the layout: twice as far apart, and at minus one half. */
    private const val SPACING_DOUBLING = 2.0

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
