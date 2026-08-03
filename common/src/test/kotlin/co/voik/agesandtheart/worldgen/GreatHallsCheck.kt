package co.voik.agesandtheart.worldgen

import co.voik.agesandtheart.worldgen.field.Slab
import co.voik.agesandtheart.worldgen.field.Subtract
import io.kotest.core.spec.style.FunSpec

/**
 * The halls, checked for the things a render cannot show and a walk would only find by falling through
 * one: that every storey is open floor to ceiling, that the rock between storeys is never breached, and
 * that a pier stands in reach wherever you are standing.
 *
 * **The floors are the assertion that matters.** A hall is subtracted out of the rock, so a mistake in
 * the arithmetic does not leave a small hole — it merges two storeys into one 120-block drop, which from
 * inside looks like a taller hall and is only wrong once somebody walks off an edge.
 */
class GreatHallsCheck : FunSpec({

    val floorY = -59
    val roofY = 92
    val rock = Slab(lowY = -64, highY = 200)
    val world = Subtract(rock, GreatHalls.voidBetween(floorY, roofY, salt = 0L))
    val storeys = GreatHalls.storeysBetween(floorY, roofY)

    // A grid of 72, so this spans several bays in each direction and lands on and off the piers alike.
    val samples = (-160..160 step 7).flatMap { x -> (-160..160 step 7).map { z -> x to z } }

    test("the band takes whole storeys, with rock under the lowest and over the highest") {
        check(storeys.isNotEmpty()) { "No storey fitted between $floorY and $roofY" }
        check(storeys.first().first > floorY) {
            "The lowest storey starts at ${storeys.first().first}, on the floor of the band at $floorY"
        }
        check(storeys.last().last < roofY) {
            "The highest storey reaches ${storeys.last().last}, through the roof of the band at $roofY"
        }
        for (storey in storeys) {
            check(storey.last - storey.first + 1 == GreatHalls.HALL_HEIGHT) {
                "A storey came out ${storey.last - storey.first + 1} tall, not ${GreatHalls.HALL_HEIGHT}"
            }
        }
    }

    test("consecutive storeys are separated by a full slab of rock") {
        for ((below, above) in storeys.zipWithNext()) {
            val between = above.first - below.last - 1
            check(between == GreatHalls.SLAB_THICKNESS) {
                "Storeys ${below.last} and ${above.first} have $between blocks between them, " +
                    "not ${GreatHalls.SLAB_THICKNESS}"
            }
        }
    }

    test("no column ever joins one storey to the next") {
        for ((x, z) in samples) {
            val solid = world.columnSpans(x, z)
            for ((below, above) in storeys.zipWithNext()) {
                val slab = below.last + 1..above.first - 1
                val breached = slab.filterNot { y -> solid.contains(y) }
                check(breached.isEmpty()) {
                    "($x, $z) has the floor between storeys open at ${breached.joinToString()}"
                }
            }
        }
    }

    test("every storey is open somewhere and held up somewhere") {
        for (storey in storeys) {
            val open = samples.count { (x, z) -> !world.columnSpans(x, z).contains(storey.first) }
            val standing = samples.count { (x, z) -> world.columnSpans(x, z).contains(storey.last) }
            check(open > 0) { "Storey $storey is solid everywhere sampled — nothing was taken out of it" }
            check(standing > 0) { "Storey $storey has no pier reaching its ceiling anywhere sampled" }
        }
    }

    test("a pier is a minority of the floor, so a storey reads as a hall rather than as a mine") {
        val floorOfTheLowest = storeys.first().first
        val onRock = samples.count { (x, z) -> world.columnSpans(x, z).contains(floorOfTheLowest) }
        val share = onRock.toDouble() / samples.size
        check(share < MOST_OF_A_FLOOR_THAT_MAY_BE_PIER) {
            "Piers cover ${(share * 100).toInt()}% of the lowest floor, which is not open hall"
        }
        check(share > NONE_OF_A_FLOOR) { "Nothing stands on the lowest floor at all" }
    }

    test("a band too shallow for one storey takes none rather than a partial one") {
        val tooShallow = GreatHalls.storeysBetween(0, GreatHalls.HALL_HEIGHT)
        check(tooShallow.isEmpty()) { "A band of ${GreatHalls.HALL_HEIGHT + 1} laid $tooShallow" }
    }

}) {
    private companion object {
        const val MOST_OF_A_FLOOR_THAT_MAY_BE_PIER = 0.35
        const val NONE_OF_A_FLOOR = 0.0
    }
}
