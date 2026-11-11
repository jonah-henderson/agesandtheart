package co.voik.agesandtheart.age.word

import co.voik.agesandtheart.NEEDS_REGISTRIES
import co.voik.agesandtheart.ShippedCorpus
import co.voik.agesandtheart.age.aspect.Aspect
import co.voik.agesandtheart.age.aspect.Colour
import co.voik.agesandtheart.age.aspect.Features
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec

/** A colour said of the algae is the colour it glows, and algae nobody coloured keeps its own. */
@Tags(NEEDS_REGISTRIES)
class AlgaeColourCheck : FunSpec({

    fun algaeIn(vararg pages: String) =
        Features.algaeColourIn(ShippedCorpus.resolved(SEED, "age", *pages).composition.optionsFor(Aspect.FEATURES))

    test("a colour before algae colours it") {
        for (colour in listOf("blue", "red", "green")) {
            val said = algaeIn(colour, "algae")
            check(said == Colour.named(colour)) { "'$colour algae' glowed $said" }
        }
    }

    test("algae nobody coloured keeps its own") {
        check(algaeIn("algae") == null) { "plain algae was coloured ${algaeIn("algae")}" }
        check(algaeIn("red", "fish", "algae") == null) { "'red fish algae' coloured the algae" }
    }
})

private const val SEED = 20261111L
