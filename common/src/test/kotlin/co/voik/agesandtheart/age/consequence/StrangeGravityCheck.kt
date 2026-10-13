package co.voik.agesandtheart.age.consequence

import co.voik.agesandtheart.age.Manifestation
import co.voik.agesandtheart.age.Spending
import io.kotest.core.spec.style.FunSpec
import kotlin.math.abs

/** How far an Age's gravity is from vanilla's, and that the seed sends some Ages lighter and some heavier. */
class StrangeGravityCheck : FunSpec({

    fun bought(steps: Int) = Spending(
        mapOf(Manifestation.STRANGE_GRAVITY to mapOf(Manifestation.GRAVITY_STRENGTH to steps)),
        mapOf(Manifestation.STRANGE_GRAVITY to mapOf(Manifestation.GRAVITY_STRENGTH to 3)),
    )

    test("nothing bought is vanilla's gravity") {
        check(StrangeGravity.factorFor(Spending.NOTHING, 1L) == StrangeGravity.ORDINARY) { "nothing bought moved gravity" }
    }

    test("each step pulls further from vanilla's, the same way for one seed") {
        val heavy = listOf(1.25, 1.75, 2.0)
        val light = listOf(1 / 1.4, 1 / 2.0, 1 / 3.0)
        for (seed in 0L..<20L) {
            val pulls = (1..3).map { StrangeGravity.factorFor(bought(it), seed) }
            val expected = if (pulls.first() > 1.0) heavy else light
            check(pulls.zip(expected).all { (pull, wanted) -> abs(pull - wanted) < 1e-9 }) { "seed $seed pulled $pulls" }
        }
    }

    test("some Ages fall lighter and some heavier") {
        val directions = (0L..<40L).map { StrangeGravity.factorFor(bought(1), it) > 1.0 }.toSet()
        check(directions == setOf(true, false)) { "forty Ages all pulled one way" }
    }
})
