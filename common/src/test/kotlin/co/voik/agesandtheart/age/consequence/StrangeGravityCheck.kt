package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Spending
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/** How far an Age's gravity is from vanilla's, and that the seed sends some Ages lighter and some heavier. */
class StrangeGravityCheck : FunSpec({

    fun bought(steps: Int) = Spending(
        mapOf(Manifestation.STRANGE_GRAVITY to mapOf(Manifestation.GRAVITY_STRENGTH to steps)),
        mapOf(Manifestation.STRANGE_GRAVITY to mapOf(Manifestation.GRAVITY_STRENGTH to 2)),
    )

    test("nothing bought is vanilla's gravity") {
        check(StrangeGravity.factorFor(Spending.NOTHING, 1L) == StrangeGravity.ORDINARY) { "nothing bought moved gravity" }
    }

    test("a little for the first step and a great deal for the second, the same way for one seed") {
        for (seed in 0L..<20L) {
            val first = StrangeGravity.factorFor(bought(1), seed)
            val second = StrangeGravity.factorFor(bought(2), seed)
            check(abs(first - 1.0) in 0.1..0.2 && abs(second - 1.0) in 0.35..0.45) { "seed $seed pulled $first then $second" }
            check((first > 1.0) == (second > 1.0)) { "seed $seed turned from ${first} to ${second}" }
        }
    }

    test("some Ages fall lighter and some heavier") {
        val directions = (0L..<40L).map { StrangeGravity.factorFor(bought(1), it) > 1.0 }.toSet()
        check(directions == setOf(true, false)) { "forty Ages all pulled one way" }
    }
})
