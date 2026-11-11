package co.voik.agesandtheart.worldgen.field

import io.kotest.core.spec.style.FunSpec

/** That relief stretches only what stands above the sea, and never carries it past the ceiling. */
class RelievedCheck : FunSpec({

    fun topOf(field: TerrainField) = field.columnSpans(0, 0).highestSolidY

    test("what stands above the pivot is stretched or squashed, and what lies below it is left alone") {
        val ground = Slab(lowY = 0, highY = 100)
        check(topOf(Relieved(ground, PIVOT, factor = 2.0, ceilingY = CEILING)) == PIVOT + (100 - PIVOT) * 2) { "a doubled relief" }
        check(topOf(Relieved(ground, PIVOT, factor = 0.5, ceilingY = CEILING)) in PIVOT + 18..PIVOT + 19) { "a halved relief" }
        val underTheSea = Slab(lowY = 0, highY = PIVOT - 10)
        check(topOf(Relieved(underTheSea, PIVOT, factor = 2.0, ceilingY = CEILING)) == PIVOT - 10) { "the sea floor moved" }
    }

    test("a stretch is held under the ceiling, and a column already near it is not squashed by a raise") {
        val tall = Slab(lowY = 0, highY = 180)
        check(topOf(Relieved(tall, PIVOT, factor = 2.5, ceilingY = CEILING)) == CEILING) { "the stretch passed the ceiling" }
        val pastIt = Slab(lowY = 0, highY = CEILING + 10)
        check(topOf(Relieved(pastIt, PIVOT, factor = 2.5, ceilingY = CEILING)) == CEILING + 10) { "a raise squashed what stood past the ceiling" }
    }
})

private const val PIVOT = 63
private const val CEILING = 240
