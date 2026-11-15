package co.voik.agesandtheart.age.consequence

import io.kotest.core.spec.style.FunSpec

/** A charged creature's blast is a creeper's at its size, as its drops are. */
class ChargedCreaturesCheck : FunSpec({

    test("a charged creature bursts with a creeper's blast at its own size") {
        check(ChargedCreatures.blastRadiusAt(1.0) == 3.0f) { "an ordinary one burst at ${ChargedCreatures.blastRadiusAt(1.0)}" }
        check(ChargedCreatures.blastRadiusAt(2.5) == 7.5f) { "a colossal one burst at ${ChargedCreatures.blastRadiusAt(2.5)}" }
    }

    test("a creeper's own blast follows its size, and never falls to nothing") {
        check(ChargedCreatures.creeperRadiusAt(1.0) == 3) { "an ordinary creeper's blast is ${ChargedCreatures.creeperRadiusAt(1.0)}" }
        check(ChargedCreatures.creeperRadiusAt(2.5) == 8) { "a colossal creeper's blast is ${ChargedCreatures.creeperRadiusAt(2.5)}" }
        check(ChargedCreatures.creeperRadiusAt(0.1) == 1) { "a tiny creeper's blast is ${ChargedCreatures.creeperRadiusAt(0.1)}" }
    }
})
