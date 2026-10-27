package co.voik.agesandtheart.worldgen.field

import co.voik.agesandtheart.age.AgeComposition
import co.voik.agesandtheart.age.AgeRecipe
import co.voik.agesandtheart.age.AgeWorld
import co.voik.agesandtheart.age.FormerUniformity
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Terrain
import com.mojang.serialization.JsonOps
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs
import kotlin.math.sqrt
import net.minecraft.core.Direction

/** How a landform changes as it is crossed, how the weather wears it, and how two landforms joined by `and` meet. */
class LandVarietyCheck : FunSpec({

    test("a stretch leaves everything at or below its pivot where it stands") {
        val column = Spans.of(-60, 120)
        val raised = column.stretchedAbove(PIVOT, 1.5)
        val lowered = column.stretchedAbove(PIVOT, 0.5)
        check(raised.ranges.single() == -60..(PIVOT + 86)) { "raised to ${raised.ranges}" }
        check(lowered.ranges.single() == -60..(PIVOT + 29)) { "lowered to ${lowered.ranges}" }
        check(Spans.of(-60, PIVOT).stretchedAbove(PIVOT, 2.0).highestSolidY == PIVOT) { "ground at the pivot moved" }
    }

    test("a stretch never carries ground past its ceiling, and never lowers it to keep it under") {
        val tall = Slab(-60, CEILING - 10)
        val stretched = Exaggerated(tall, PIVOT, SEED, -7, listOf(1.0), 1.0, swing = 3.0, ceilingY = CEILING)
        for (x in -2000..2000 step 37) {
            val top = stretched.columnSpans(x, 0).highestSolidY ?: error("the column at $x emptied")
            check(top <= CEILING) { "the column at $x stands at $top, over the ceiling" }
        }
    }

    test("two reconciled grounds meet without a step, and keep their own heights away from the seam") {
        val low = Slab(-60, LOW_GROUND)
        val high = Slab(-60, HIGH_GROUND)
        val map = RegionMap(members = 2, scale = 400.0, blend = 0, originX = 0, originZ = 0, seed = SEED)
        val reconciled = Reconciled(listOf(low, high), listOf(low, high), map, WIDTH, keepsFloor = true)
        var steepest = 0
        var keptOwnHeight = 0
        for (z in -1000..1000 step 50) {
            var previous: Int? = null
            for (x in -2000..2000) {
                val spans = reconciled.columnSpans(x, z)
                val top = spans.highestSolidY ?: error("the column at $x, $z emptied")
                check(spans.ranges.first().first <= -60) { "the column at $x, $z lifted off its floor" }
                previous?.let { steepest = maxOf(steepest, abs(top - it)) }
                previous = top
                if (map.blocksFromSeamAt(x, z) > WIDTH) {
                    check(top == LOW_GROUND || top == HIGH_GROUND) { "$x, $z is far from a seam and stands at $top" }
                    keptOwnHeight++
                }
            }
        }
        check(keptOwnHeight > 0) { "no column stood far enough from a seam to keep its own height" }
        check(steepest <= STEEPEST_STEP) { "reconciled ground still steps $steepest blocks between neighbours" }
    }

    test("gullies cut steep ground, never level ground, and never below their floor") {
        val level = Slab(-60, LOW_GROUND)
        val flatGullied = Gullied(level, SEED, depth = 8.0, cellBlocks = 40.0, floorY = PIVOT)
        for (x in -200..200 step 7) for (z in -200..200 step 7) {
            check(flatGullied.columnSpans(x, z).ranges == level.columnSpans(x, z).ranges) { "level ground at $x, $z was cut" }
        }
        val peak = Union(listOf(Slab(-60, PIVOT), Cone(0, 0, baseRadius = 120.0, baseY = PIVOT, tipY = PIVOT + 120)))
        val steepGullied = Gullied(peak, SEED, depth = 8.0, cellBlocks = 40.0, floorY = PIVOT)
        var cut = 0
        for (x in -110..110 step 3) for (z in -110..110 step 3) {
            val before = peak.columnSpans(x, z).highestSolidY ?: continue
            val after = steepGullied.columnSpans(x, z).highestSolidY ?: error("the column at $x, $z emptied")
            check(after >= minOf(before, PIVOT)) { "a gully at $x, $z cut to $after, under the floor" }
            if (after < before) cut++
        }
        check(cut > 0) { "a cone's flanks took no gully" }
    }

    test("scree piles at the foot of a face, and nowhere else") {
        // A mesa a hundred blocks across, standing seventy-six over level ground.
        val mesa = Union(listOf(Slab(-60, LOW_GROUND), Cylinder(Direction.Axis.Y, 0, HIGH_GROUND / 2, 0, MESA_RADIUS, HIGH_GROUND / 2.0)))
        val screed = Scree(mesa, SEED, share = 0.3, deepest = 10.0, floorY = PIVOT)
        var piled = 0
        for (x in -200..200 step 3) for (z in -200..200 step 3) {
            val before = mesa.columnSpans(x, z).highestSolidY ?: continue
            val after = screed.columnSpans(x, z).highestSolidY ?: error("the column at $x, $z emptied")
            val fromCentre = sqrt((x * x + z * z).toDouble())
            check(after >= before) { "scree at $x, $z lowered the ground to $after" }
            if (fromCentre < MESA_RADIUS - 1) check(after == before) { "scree climbed onto the mesa at $x, $z" }
            if (fromCentre > MESA_RADIUS + SCREE_REACH) check(after == before) { "scree lay out on the plain at $x, $z" }
            if (after > before) piled++
        }
        check(piled > 0) { "no scree lay at the mesa's foot" }
        check(Scree(Slab(-60, LOW_GROUND), SEED, 0.3, 10.0, PIVOT).columnSpans(5, 5).highestSolidY == LOW_GROUND) { "level ground took scree" }
    }

    test("an Age written before variety stays the same everywhere, and a new one does not") {
        fun read(version: Int): AgeComposition {
            val recipe = AgeRecipe(AgeWorld.Composed(AgeComposition(terrains = listOf(Terrain.CLIFFS))), seed = SEED, generatorVersion = version)
            val saved = AgeRecipe.CODEC.encodeStart(JsonOps.INSTANCE, recipe).getOrThrow()
            return AgeRecipe.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow().composition ?: error("no composition")
        }
        val former = read(FormerUniformity.LAST_GENERATOR_BEFORE_VARIETY).optionsFor(Aspect.TERRAIN, 0)
        val current = read(FormerUniformity.LAST_GENERATOR_BEFORE_VARIETY + 1).optionsFor(Aspect.TERRAIN, 0)
        check(former.steer(Terrain.VARIETY, SEED) == -1.0) { "the old Age's variety reads ${former.steer(Terrain.VARIETY, SEED)}" }
        check(current.steer(Terrain.VARIETY, SEED) == null) { "a new Age's variety was pinned" }
    }
})

private const val SEED = 4242L
private const val PIVOT = 63
private const val CEILING = 296
private const val LOW_GROUND = 64
private const val HIGH_GROUND = 140
private const val WIDTH = 96.0
private const val MESA_RADIUS = 100.0

/** As far out as scree ten deep, falling at its angle of rest, can reach past the grid point at a face's foot. */
private const val SCREE_REACH = 32.0

/** A reconciled seam eases seventy-six blocks over about two widths; a sheared one would step all of them at once. */
private const val STEEPEST_STEP = 4
