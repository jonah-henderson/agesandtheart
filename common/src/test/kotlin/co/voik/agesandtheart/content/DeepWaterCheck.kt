package co.voik.agesandtheart.content

import io.kotest.core.spec.style.FunSpec

/**
 * Where an Age's sea becomes an abyss.
 *
 * **Everything under the line is abyss**, a flooded cave included, and what takes the pressure out of water
 * is an *air pocket* over it rather than shallowness. That is the second correction this rule has taken from
 * a walk: first the per-column threshold went, because it drew ragged spikes; then the water-above test
 * went, because it turned a side passage ordinary the moment its roof came in.
 */
class DeepWaterCheck : FunSpec({

    test("the abyss line sits exactly the measured depth under the sea's surface") {
        val surface = 133
        check(DeepWater.lineBelow(surface) == surface - DeepWater.DEEPEST_VANILLA_SEA) {
            "the line is no longer the measured depth under the surface"
        }
    }

    test("an ordinary sea has no abyss in it at all") {
        // Vanilla's deepest measured column is 76 (`:common:oceandepth`), so a sea of that depth must come
        // out with its floor still above the line — that is the whole of the fence.
        val surface = 62
        val floor = surface - 76
        check(floor > DeepWater.lineBelow(surface)) {
            "a 76-block sea reaches its own abyss line, and that is vanilla's own deepest column"
        }
    }

    test("an air pocket depressurises a skin of water and no more") {
        // The rule is a shell, not a shaft: three blocks under the air and then the abyss resumes. A number
        // that grew would quietly hollow the ceiling out of every flooded cave.
        check(DeepWater.DEPRESSURISED_UNDER_AIR in 1..8) {
            "the shell under an air pocket has grown past a skin"
        }
    }
})
