package co.voik.agesandtheart.age

import co.voik.agesandtheart.MinecraftRegistries
import co.voik.agesandtheart.NEEDS_REGISTRIES
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/**
 * That [SHIPPED_PRICES] is what `art/manifestation/` actually says.
 *
 * The copy stays a literal so `SpendingCheck` and `BefallingCheck` keep their place in `-Pfast`; this is
 * what makes a retuned price file fail them loudly instead of leaving them green against stale numbers.
 */
@Tags(NEEDS_REGISTRIES)
class ShippedPricesCheck : FunSpec({

    test("the price list the checks spell out is the one that ships") {
        val shipped = Price.load(MinecraftRegistries.shippedData())
        check(SHIPPED_PRICES == shipped) { "SHIPPED_PRICES is stale; the files say $shipped" }
    }
})
