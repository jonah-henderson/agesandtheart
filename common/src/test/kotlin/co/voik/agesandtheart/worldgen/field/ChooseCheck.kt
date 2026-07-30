package co.voik.agesandtheart.worldgen.field

import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/**
 * Properties of the two randomised combinators, [Choose] and [Chance].
 *
 * **Why these get a check when most primitives do not.** A primitive's output is inspectable — you render it and
 * look. These two produce *a distribution*, and a distribution is exactly the kind of thing that looks plausible
 * while being wrong: a weighted pick that quietly ignores its weights, a count range that occasionally overruns
 * its bound, a `resized` that re-rolls the dice and so hands `Instanced` a different subset at every size. None
 * of those show up in one picture, and all of them are cheap to assert over many seeds.
 *
 * [co.voik.agesandtheart.age.CodecCheck] covers the round-trip for the same reason it covers unused placement
 * kinds: nothing else writes them yet.
 *
 * **The children are one-block [Slab]s at distinct heights**, so a column's spans say exactly which alternatives
 * were placed. That is the whole trick that makes these assertions direct rather than statistical stand-ins.
 */
class ChooseCheck : FunSpec({

    /**
     * The property everything else rests on: a draw is a function of its inputs, so the same node built twice
     * agrees.
     *
     * Ages rebuild from their recipe on every open, so a node that drew differently the second time would give a
     * player a different world each time they walked back through the same book.
     */
    test("the same seed draws the same subset") {
        for (seed in 1L..200L) {
            val alternatives = alternativesAt(0, 10, 20, 30, 40)
            val first = chooseOf(alternatives, leastPlaced = 2, mostPlaced = 4, seed = seed)
            val again = chooseOf(alternatives, leastPlaced = 2, mostPlaced = 4, seed = seed)
            check(placedHeights(first) == placedHeights(again)) {
                "seed $seed drew ${placedHeights(first)} then ${placedHeights(again)} — the draw is not reproducible"
            }
        }
    }

    test("the count stays inside its bounds") {
        for (seed in 1L..SEEDS) {
            val placed = placedHeights(chooseOf(alternativesAt(0, 10, 20, 30, 40), 1, 3, seed)).size
            check(placed in 1..3) { "seed $seed placed $placed of 5, outside the requested 1..3" }
        }
    }

    test("exactly k places exactly k") {
        for (wanted in 0..5) {
            for (seed in 1L..500L) {
                val placed = placedHeights(chooseOf(alternativesAt(0, 10, 20, 30, 40), wanted, wanted, seed)).size
                check(placed == wanted) { "asked for exactly $wanted, seed $seed placed $placed" }
            }
        }
    }

    /**
     * Weights steer *which* alternative is picked — the `one of n, weighted` spelling.
     *
     * Asserted as a frequency because that is what a weight means. A weighted pick that ignored its weights would
     * still satisfy every count assertion above, which is precisely why this one has to exist.
     */
    test("weights steer the choice") {
        val alternatives = listOf(
            Choose.Alternative(marker(0), weight = 3.0),
            Choose.Alternative(marker(10), weight = 1.0),
            Choose.Alternative(marker(20), weight = 1.0),
        )
        val counts = mutableMapOf(0 to 0, 10 to 0, 20 to 0)
        for (seed in 1L..SEEDS) {
            val placed = placedHeights(chooseOf(alternatives, leastPlaced = 1, mostPlaced = 1, seed = seed))
            check(placed.size == 1) { "a one-of choose placed ${placed.size} at seed $seed" }
            counts[placed.single()] = counts.getValue(placed.single()) + 1
        }

        val expected = mapOf(0 to 0.6, 10 to 0.2, 20 to 0.2)
        for ((height, share) in expected) {
            val observed = counts.getValue(height).toDouble() / SEEDS
            check(abs(observed - share) < TOLERANCE) {
                "weight for the marker at y=$height came out at ${"%.3f".format(observed)}, expected about $share"
            }
        }
    }

    test("a zero weight is never placed") {
        val alternatives = listOf(
            Choose.Alternative(marker(0), weight = 1.0),
            Choose.Alternative(marker(10), weight = 0.0),
        )
        for (seed in 1L..SEEDS) {
            val placed = placedHeights(chooseOf(alternatives, leastPlaced = 1, mostPlaced = 2, seed = seed))
            check(10 !in placed) { "a zero-weight alternative was placed at seed $seed" }
        }
    }

    /** A count is a request; the alternatives are what there is to satisfy it with. */
    test("asking for more than exists places everything") {
        for (seed in 1L..500L) {
            val placed = placedHeights(chooseOf(alternativesAt(0, 10), leastPlaced = 5, mostPlaced = 9, seed = seed))
            check(placed == listOf(0, 10)) { "asked for 5..9 of 2 and got $placed at seed $seed" }
        }
    }

    test("chance holds its probability") {
        for (probability in listOf(0.0, 0.1, 0.3, 0.5, 0.9, 1.0)) {
            var present = 0
            for (seed in 1L..SEEDS) {
                if (placedHeights(Chance(marker(0), probability, seed)).isNotEmpty()) present++
            }
            val observed = present.toDouble() / SEEDS
            check(abs(observed - probability) < TOLERANCE) {
                "Chance($probability) was present ${"%.3f".format(observed)} of the time"
            }
        }
    }

    /**
     * A present [Chance] is its child everywhere and an absent one is empty everywhere — never partly one.
     *
     * This is the per-Age granularity written as an assertion. A version keyed on position instead would pass every
     * frequency check above while riddling the shape with holes, so the frequency checks alone cannot catch it.
     */
    test("chance is all or nothing") {
        val child = Slab(lowY = 0, highY = 8)
        val columns = listOf(0 to 0, 1 to 0, 0 to 1, -40 to 91, 512 to -512, 10_000 to 10_000)
        for (seed in 1L..2_000L) {
            val chance = Chance(child, probability = 0.5, seed = seed)
            val answers = columns.map { (x, z) -> chance.columnSpans(x, z).ranges }
            val isPresent = answers.first().isNotEmpty()
            val expected = columns.map { (x, z) ->
                if (isPresent) child.columnSpans(x, z).ranges else emptyList()
            }
            check(answers == expected) {
                "Chance at seed $seed answered inconsistently across columns: $answers"
            }
        }
    }

    /**
     * Resizing carries the alternatives and keeps the subset — it must not re-roll.
     *
     * [Instanced] pre-builds every size it will ever place by calling `resized`, so a `Choose` that drew afresh
     * per size would place a *different* subset at each one. That would look like variety and be undebuggable.
     */
    test("resizing keeps the choice") {
        for (seed in 1L..500L) {
            val alternatives = alternativesAt(0, 10, 20, 30, 40)
            val choose = chooseOf(alternatives, leastPlaced = 2, mostPlaced = 3, seed = seed)
            val resized = choose.resized(2.0, pivotY = 0)
            val expected = Choose(
                alternatives.map { it.copy(field = it.field.resized(2.0, 0)) },
                leastPlaced = 2, mostPlaced = 3, seed = seed,
            )
            check(resized == expected) { "resizing at seed $seed changed the node beyond its children" }
            check(placedHeights(resized).size == placedHeights(choose).size) {
                "resizing at seed $seed changed how many alternatives were placed"
            }
        }
    }
})

private const val SEEDS = 20_000

/** How far an observed frequency may sit from the expected one. At 20k draws the noise is well inside this. */
private const val TOLERANCE = 0.02

/** One solid block at [height] — a marker, so a column's spans name which alternatives were placed. */
private fun marker(height: Int): TerrainField = Slab(lowY = height, highY = height)

/** Which markers a field placed, read straight off a column. */
private fun placedHeights(field: TerrainField): List<Int> =
    field.columnSpans(0, 0).ranges.map { it.first }

private fun alternativesAt(vararg heights: Int): List<Choose.Alternative> =
    heights.map { Choose.Alternative(marker(it)) }

private fun chooseOf(
    alternatives: List<Choose.Alternative>,
    leastPlaced: Int,
    mostPlaced: Int,
    seed: Long,
) = Choose(alternatives, leastPlaced, mostPlaced, seed)
