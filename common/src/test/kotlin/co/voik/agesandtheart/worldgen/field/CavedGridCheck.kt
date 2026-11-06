package co.voik.agesandtheart.worldgen.field

import io.kotest.core.spec.style.FunSpec
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs

/** The caves cut column by column, unchanged, beside the caves cut on a grid. */
class CavedGridCheck : FunSpec({

    test("the caves cut column by column are what they were") {
        check(fingerprintOf(caves()) == 5686457257866994010L) { "the caves under level ground moved" }
        check(fingerprintOf(caves(featureScale = 0.5)) == 4746049469027240919L) { "the small caves moved" }
        check(fingerprintOf(caves(base = HILL)) == -3604322560494311679L) { "the caves under a hill moved" }
    }

    test("the grid's caves are the same however the columns are asked for") {
        val forwards = caves(gridded = true, base = HILL)
        val inOrder = COLUMNS.map { (x, z) -> forwards.columnSpans(x, z).ranges }
        val backwards = caves(gridded = true, base = HILL)
        val reversed = COLUMNS.reversed().map { (x, z) -> backwards.columnSpans(x, z).ranges }.reversed()
        check(inOrder == reversed) { "asking backwards changed the caves" }

        val threaded = caves(gridded = true, base = HILL)
        val pool = Executors.newFixedThreadPool(THREADS)
        try {
            val asks = (0..<THREADS).map { offset ->
                Callable { COLUMNS.indices.map { (it + offset * 97) % COLUMNS.size }.associateWith { COLUMNS[it] } }
            }
            for (ask in pool.invokeAll(asks).map { it.get() }) {
                for ((index, column) in ask) {
                    check(threaded.columnSpans(column.first, column.second).ranges == inOrder[index]) { "column $column changed across threads" }
                }
            }
        } finally {
            pool.shutdown()
        }
    }

    test("the grid's caves take about as much rock as the columns' do") {
        val exact = caveIn(caves(base = HILL))
        val gridded = caveIn(caves(gridded = true, base = HILL))
        check(exact > 0) { "no caves at all" }
        check(abs(gridded - exact).toDouble() / exact < MOST_DIFFERENCE) { "the grid cut $gridded blocks of cave against $exact" }
    }

    test("caves opened past vanilla's threshold take more rock, and held under it less") {
        val vanilla = caveIn(caves(gridded = true, base = HILL))
        val opened = caveIn(caves(gridded = true, base = HILL, opening = 0.06))
        val closed = caveIn(caves(gridded = true, base = HILL, opening = -0.06))
        check(opened > vanilla * 2) { "opened caves cut $opened blocks against vanilla's $vanilla" }
        check(closed < vanilla / 2) { "closed caves cut $closed blocks against vanilla's $vanilla" }
    }
})

private const val SEED = 0x5EEDL
private const val THREADS = 8

/** How far the grid's share of cave may stray from the columns'. */
private const val MOST_DIFFERENCE = 0.25

private val COLUMNS = (-24..<40).flatMap { x -> (-24..<40).map { z -> x to z } }

private fun caves(
    featureScale: Double = 1.0,
    base: TerrainField = Slab(lowY = -64, highY = 90),
    gridded: Boolean = false,
    opening: Double = Caved.VANILLA_OPENING,
) = Caved(base, SEED, fromY = -60, toY = 320, featureScale = featureScale, gridded = gridded, opening = opening)

/** Ground whose top rises from y=40 to a peak, so no two columns' rock ends at the same height. */
private val HILL = Union(listOf(Slab(lowY = -64, highY = 40), Cone(baseX = 8, baseZ = 8, baseRadius = 40.0, baseY = 40, tipY = 150)))

/** Every column of a few chunks, folded into one number. */
private fun fingerprintOf(field: TerrainField): Long {
    var hash = 1L
    for ((x, z) in COLUMNS) {
        for (range in field.columnSpans(x, z).ranges) hash = hash * 31 + range.first * 7919L + range.last
    }
    return hash
}

/** How many blocks of the hill the caves took, over the same columns. */
private fun caveIn(field: TerrainField): Int =
    COLUMNS.sumOf { (x, z) -> HILL.columnSpans(x, z).ranges.sumOf { it.count() } - field.columnSpans(x, z).ranges.sumOf { it.count() } }
