package co.voik.agesandtheart.age

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That [SHIPPED_LADDERS] is what `art/manifestation/` actually says.
 *
 * The copy stays a literal so `SpendingCheck` and `BefallingCheck` keep their place in `-Pfast`; this is what
 * makes a retuned ladder fail them loudly instead of leaving them green against stale tiers.
 */
@Tags(NEEDS_REGISTRIES)
class ShippedLaddersCheck : FunSpec({

    test("the ladders the checks spell out are the ones that ship") {
        val shipped = Ladder.load(MinecraftRegistries.shippedData())
        check(SHIPPED_LADDERS == shipped) { "SHIPPED_LADDERS is stale; the files say $shipped" }
    }
})
