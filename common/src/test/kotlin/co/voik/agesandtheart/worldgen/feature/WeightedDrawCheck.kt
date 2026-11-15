package co.voik.agesandtheart.worldgen.feature

import io.kotest.core.spec.style.FunSpec
import kotlin.random.Random

class WeightedDrawCheck : FunSpec({

    test("each is drawn as often as its weight says, and a weight of a hundredth all but never") {
        val weights = listOf(30.0, 20.0, 20.0, 20.0, 4.0, 4.0, 1.0, 1.0, 0.01)
        val random = Random(7)
        val draws = 1_000_000
        val counts = IntArray(weights.size)
        repeat(draws) { counts[WeightedDraw.indexAt(random.nextDouble(), weights, weights.size)]++ }
        val stoneShare = counts[0].toDouble() / draws
        check(stoneShare in 0.29..0.31) { "the heaviest was drawn ${stoneShare * 100}% of the time" }
        check(counts.last() in 50..150) { "a hundredth's weight was drawn ${counts.last()} times in a million" }
    }

    test("with no weights, or weights that do not match, each is drawn evenly") {
        check((0..<4).map { WeightedDraw.indexAt(it / 4.0 + 0.01, emptyList(), 4) } == listOf(0, 1, 2, 3)) { "an unweighted draw was uneven" }
        check(WeightedDraw.indexAt(0.99, listOf(1.0), 3) == 2) { "mismatched weights were used" }
    }
})
