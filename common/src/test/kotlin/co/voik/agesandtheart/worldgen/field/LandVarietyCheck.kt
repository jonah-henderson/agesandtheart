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

    test("terraces draw a slope to treads within a step of it, and leave the land outside them alone") {
        val slope = Union(listOf(Slab(-60, PIVOT), Cone(0, 0, baseRadius = 400.0, baseY = PIVOT, tipY = PIVOT + 200)))
        val everywhere = Terraced(slope, SEED, stepHeight = STEP, share = 1.0, floorY = PIVOT)
        val tops = mutableMapOf<Int, Int>()
        for (x in -390..390 step 5) for (z in -390..390 step 5) {
            val before = slope.columnSpans(x, z).highestSolidY ?: continue
            val after = everywhere.columnSpans(x, z).highestSolidY ?: error("the column at $x, $z emptied")
            check(abs(after - before) <= STEP) { "the column at $x, $z moved from $before to $after, more than a step" }
            check(after >= PIVOT) { "a terrace at $x, $z cut under the floor" }
            tops.merge(after - PIVOT, 1, Int::plus)
        }
        val onATread = tops.filterKeys { it % STEP.toInt() == 0 }.values.sum()
        check(onATread > tops.values.sum() / 2) { "only $onATread of ${tops.values.sum()} columns stand on a tread" }
        val nowhere = Terraced(slope, SEED, stepHeight = STEP, share = 0.0, floorY = PIVOT)
        for (x in -390..390 step 37) check(nowhere.columnSpans(x, 11).ranges == slope.columnSpans(x, 11).ranges) { "untouched ground at $x moved" }
    }

    test("undercuts notch the foot of a face under a cap, and nothing else") {
        val mesa = Union(listOf(Slab(-60, LOW_GROUND), Cylinder(Direction.Axis.Y, 0, HIGH_GROUND / 2, 0, MESA_RADIUS, HIGH_GROUND / 2.0)))
        val undercut = Undercut(mesa, SEED, depth = NOTCH_DEPTH, height = NOTCH_HEIGHT, floorY = PIVOT)
        var notched = 0
        for (x in -200..200 step 2) for (z in -200..200 step 2) {
            val before = mesa.columnSpans(x, z)
            val after = undercut.columnSpans(x, z)
            if (after.ranges == before.ranges) continue
            val fromCentre = sqrt((x * x + z * z).toDouble())
            check(fromCentre in (MESA_RADIUS - NOTCH_DEPTH - 1)..MESA_RADIUS) { "a notch at $x, $z is not at the mesa's edge" }
            check(after.highestSolidY == before.highestSolidY) { "the cap at $x, $z did not stand" }
            val notch = (LOW_GROUND + 1)..(LOW_GROUND + NOTCH_HEIGHT.toInt())
            check(notch.none(after::contains) && (after.ranges.first().last == LOW_GROUND)) { "the notch at $x, $z is ${after.ranges}" }
            notched++
        }
        check(notched > 0) { "the mesa's foot took no notch" }
    }

    test("wear swings from place to place about its mean, and none wears nothing") {
        val swinging = Wear(SEED, mean = 1.0, spread = 0.8)
        val readings = (-5000..5000 step 97).flatMap { x -> (-5000..5000 step 89).map { z -> swinging.at(x, z) } }
        check(readings.min() < 0.5 && readings.max() > 1.5) { "the wear only ran ${readings.min()}..${readings.max()}" }
        check(readings.all { it in 0.0..Wear.MOST }) { "the wear left its bounds" }
        check(Wear(SEED, mean = 1.3, spread = 0.0).at(123, -456) == 1.3) { "even wear varied" }
        val peak = Union(listOf(Slab(-60, PIVOT), Cone(0, 0, baseRadius = 120.0, baseY = PIVOT, tipY = PIVOT + 120)))
        val unworn = Wear(SEED, mean = 0.0, spread = 1.0)
        for (x in -110..110 step 9) for (z in -110..110 step 9) {
            val gullied = Gullied(peak, SEED, depth = 8.0, cellBlocks = 40.0, floorY = PIVOT, wear = unworn)
            check(gullied.columnSpans(x, z).ranges == peak.columnSpans(x, z).ranges) { "unworn ground at $x, $z was gullied" }
        }
    }

    test("an Age written before variety stays the same everywhere and unworn, and a new one does not") {
        fun read(version: Int): AgeComposition {
            val recipe = AgeRecipe(AgeWorld.Composed(AgeComposition(terrains = listOf(Terrain.CLIFFS))), seed = SEED, generatorVersion = version)
            val saved = AgeRecipe.CODEC.encodeStart(JsonOps.INSTANCE, recipe).getOrThrow()
            return AgeRecipe.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow().composition ?: error("no composition")
        }
        val former = read(FormerUniformity.LAST_GENERATOR_BEFORE_VARIETY).optionsFor(Aspect.TERRAIN, 0)
        val current = read(FormerUniformity.LAST_GENERATOR_BEFORE_VARIETY + 1).optionsFor(Aspect.TERRAIN, 0)
        for (dial in listOf(Terrain.VARIETY, Terrain.EROSION)) {
            check(former.steer(dial, SEED) == -1.0) { "the old Age's ${dial.name} reads ${former.steer(dial, SEED)}" }
            check(current.steer(dial, SEED) == null) { "a new Age's ${dial.name} was pinned" }
        }
    }
})

private const val SEED = 4242L
private const val PIVOT = 63
private const val CEILING = 296
private const val LOW_GROUND = 64
private const val HIGH_GROUND = 140
private const val WIDTH = 96.0
private const val MESA_RADIUS = 100.0
private const val STEP = 7.0
private const val NOTCH_DEPTH = 3.0
private const val NOTCH_HEIGHT = 4.0

/** As far out as scree ten deep, falling at its angle of rest, can reach past the grid point at a face's foot. */
private const val SCREE_REACH = 32.0

/** A reconciled seam eases seventy-six blocks over about two widths; a sheared one would step all of them at once. */
private const val STEEPEST_STEP = 4
