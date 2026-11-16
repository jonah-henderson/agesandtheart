package co.voik.agesandtheart.age.cataclysm

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.age.Manifestation
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** That `art/cataclysm/` reads: the asteroid comes after three days' warning, called down by collapse at its fastest. */
@Tags(NEEDS_REGISTRIES)
class ShippedCataclysmCheck : FunSpec({

    test("the asteroid's rule is the one that ships") {
        val asteroid = CataclysmRule.load(MinecraftRegistries.shippedData()).getValue(CataclysmKind.ASTEROID)
        check(asteroid.timeline.approach == THREE_DAYS) { "the approach is ${asteroid.timeline.approach} ticks" }
        val called = asteroid.inflictedWhen
        check(called == EndCondition.Reach(Manifestation.COLLAPSE, "spread", 1.0)) { "it is called down by $called" }
    }
}) {
    private companion object {
        const val THREE_DAYS = 72_000
    }
}
